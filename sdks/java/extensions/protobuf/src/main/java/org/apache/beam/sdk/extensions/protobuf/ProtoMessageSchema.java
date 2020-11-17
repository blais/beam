/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.extensions.protobuf;

import static org.apache.beam.sdk.extensions.protobuf.ProtoByteBuddyUtils.getProtoGetter;
import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkArgument;

import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.annotations.Experimental.Kind;
import org.apache.beam.sdk.extensions.protobuf.ProtoByteBuddyUtils.ProtoTypeConversionsFactory;
import org.apache.beam.sdk.schemas.FieldValueGetter;
import org.apache.beam.sdk.schemas.FieldValueTypeInformation;
import org.apache.beam.sdk.schemas.GetterBasedSchemaProvider;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.Schema.Field;
import org.apache.beam.sdk.schemas.SchemaUserTypeCreator;
import org.apache.beam.sdk.schemas.logicaltypes.OneOfType;
import org.apache.beam.sdk.schemas.utils.FieldValueTypeSupplier;
import org.apache.beam.sdk.schemas.utils.JavaBeanUtils;
import org.apache.beam.sdk.schemas.utils.ReflectUtils;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.SimpleFunction;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.Lists;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.Maps;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.Multimap;
import org.checkerframework.checker.nullness.qual.Nullable;

@Experimental(Kind.SCHEMAS)
@SuppressWarnings({
  "nullness", // TODO(https://issues.apache.org/jira/browse/BEAM-10402)
  "rawtypes" // TODO(https://issues.apache.org/jira/browse/BEAM-10556)
})
public class ProtoMessageSchema extends GetterBasedSchemaProvider {

  private static final class ProtoClassFieldValueTypeSupplier implements FieldValueTypeSupplier {
    @Override
    public List<FieldValueTypeInformation> get(Class<?> clazz) {
      throw new RuntimeException("Unexpected call.");
    }

    @Override
    public List<FieldValueTypeInformation> get(Class<?> clazz, Schema schema) {
      Multimap<String, Method> methods = ReflectUtils.getMethodsMap(clazz);
      List<FieldValueTypeInformation> types =
          Lists.newArrayListWithCapacity(schema.getFieldCount());
      for (Field field : schema.getFields()) {
        if (field.getType().isLogicalType(OneOfType.IDENTIFIER)) {
          // This is a OneOf. Look for the getters for each OneOf option.
          OneOfType oneOfType = field.getType().getLogicalType(OneOfType.class);
          Map<String, FieldValueTypeInformation> oneOfTypes = Maps.newHashMap();
          for (Field oneOfField : oneOfType.getOneOfSchema().getFields()) {
            Method method = getProtoGetter(methods, oneOfField.getName(), oneOfField.getType());
            oneOfTypes.put(
                oneOfField.getName(),
                FieldValueTypeInformation.forGetter(method).withName(field.getName()));
          }
          // Add an entry that encapsulates information about all possible getters.
          types.add(
              FieldValueTypeInformation.forOneOf(
                      field.getName(), field.getType().getNullable(), oneOfTypes)
                  .withName(field.getName()));
        } else {
          // This is a simple field. Add the getter.
          Method method = getProtoGetter(methods, field.getName(), field.getType());
          types.add(FieldValueTypeInformation.forGetter(method).withName(field.getName()));
        }
      }
      return types;
    }
  }

  @Override
  public <T> @Nullable Schema schemaFor(TypeDescriptor<T> typeDescriptor) {
    checkForDynamicType(typeDescriptor);
    return ProtoSchemaTranslator.getSchema((Class<Message>) typeDescriptor.getRawType());
  }

  @Override
  public List<FieldValueGetter> fieldValueGetters(Class<?> targetClass, Schema schema) {
    return ProtoByteBuddyUtils.getGetters(
        targetClass,
        schema,
        new ProtoClassFieldValueTypeSupplier(),
        new ProtoTypeConversionsFactory());
  }

  @Override
  public List<FieldValueTypeInformation> fieldValueTypeInformations(
      Class<?> targetClass, Schema schema) {
    return JavaBeanUtils.getFieldTypes(targetClass, schema, new ProtoClassFieldValueTypeSupplier());
  }

  @Override
  public SchemaUserTypeCreator schemaTypeCreator(Class<?> targetClass, Schema schema) {
    SchemaUserTypeCreator creator =
        ProtoByteBuddyUtils.getBuilderCreator(
            targetClass, schema, new ProtoClassFieldValueTypeSupplier());
    if (creator == null) {
      throw new RuntimeException("Cannot create creator for " + targetClass);
    }
    return creator;
  }

  // Other modules are not allowed to use non-vendored Message class
  @SuppressWarnings({
    "rawtypes", // TODO(https://issues.apache.org/jira/browse/BEAM-10556)
    "unchecked"
  })
  public static <T> SimpleFunction<byte[], Row> getProtoBytesToRowFn(Class<T> clazz) {
    checkForMessageType(clazz);
    return new ProtoBytesToRowFn(clazz);
  }

  private static class ProtoBytesToRowFn<T extends Message> extends SimpleFunction<byte[], Row> {
    private final ProtoCoder<T> protoCoder;
    private final SerializableFunction<T, Row> toRowFunction;

    public ProtoBytesToRowFn(Class<T> clazz) {
      this.protoCoder = ProtoCoder.of(clazz);
      this.toRowFunction = new ProtoMessageSchema().toRowFunction(TypeDescriptor.of(clazz));
    }

    @Override
    public Row apply(byte[] bytes) {
      try {
        T message = protoCoder.getParser().parseFrom(bytes);
        return toRowFunction.apply(message);
      } catch (IOException e) {
        throw new IllegalArgumentException("Could not decode row from proto payload.", e);
      }
    }
  }

  // Other modules are not allowed to use non-vendored Message class
  @SuppressWarnings({
    "rawtypes", // TODO(https://issues.apache.org/jira/browse/BEAM-10556)
    "unchecked"
  })
  public static <T> SimpleFunction<Row, byte[]> getRowToProtoBytesFn(Class<T> clazz) {
    checkForMessageType(clazz);
    return new RowToProtoBytesFn(clazz);
  }

  private static class RowToProtoBytesFn<T extends Message> extends SimpleFunction<Row, byte[]> {
    private final SerializableFunction<Row, T> toMessageFunction;
    private final Schema protoSchema;

    public RowToProtoBytesFn(Class<T> clazz) {
      ProtoMessageSchema messageSchema = new ProtoMessageSchema();
      TypeDescriptor<T> typeDescriptor = TypeDescriptor.of(clazz);
      this.toMessageFunction = messageSchema.fromRowFunction(typeDescriptor);
      this.protoSchema = messageSchema.schemaFor(typeDescriptor);
    }

    @Override
    public byte[] apply(Row row) {
      if (!protoSchema.equivalent(row.getSchema())) {
        row = switchFieldsOrder(row);
      }
      Message message = toMessageFunction.apply(row);
      return message.toByteArray();
    }

    private Row switchFieldsOrder(Row row) {
      Row.Builder convertedRow = Row.withSchema(protoSchema);
      protoSchema
          .getFields()
          .forEach(field -> convertedRow.addValue(row.getValue(field.getName())));
      return convertedRow.build();
    }
  }

  private <T> void checkForDynamicType(TypeDescriptor<T> typeDescriptor) {
    if (typeDescriptor.getRawType().equals(DynamicMessage.class)) {
      throw new RuntimeException(
          "DynamicMessage is not allowed for the standard ProtoSchemaProvider, use ProtoDynamicMessageSchema  instead.");
    }
  }

  private static <T> void checkForMessageType(Class<T> clazz) {
    checkArgument(
        Message.class.isAssignableFrom(clazz),
        "%s is not a subtype of %s",
        clazz.getName(),
        Message.class.getSimpleName());
  }
}