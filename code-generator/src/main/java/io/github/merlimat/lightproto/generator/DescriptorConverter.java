/**
 * Copyright 2026 StreamNative
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.merlimat.lightproto.generator;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts Google protobuf {@link FileDescriptorProto} to the internal model
 * used by the LightProto code generator.
 */
public class DescriptorConverter {

    private static final Map<FieldDescriptorProto.Type, String> PROTO_TYPE_NAMES = Map.ofEntries(
            Map.entry(FieldDescriptorProto.Type.TYPE_DOUBLE, "double"),
            Map.entry(FieldDescriptorProto.Type.TYPE_FLOAT, "float"),
            Map.entry(FieldDescriptorProto.Type.TYPE_INT64, "int64"),
            Map.entry(FieldDescriptorProto.Type.TYPE_UINT64, "uint64"),
            Map.entry(FieldDescriptorProto.Type.TYPE_INT32, "int32"),
            Map.entry(FieldDescriptorProto.Type.TYPE_FIXED64, "fixed64"),
            Map.entry(FieldDescriptorProto.Type.TYPE_FIXED32, "fixed32"),
            Map.entry(FieldDescriptorProto.Type.TYPE_BOOL, "bool"),
            Map.entry(FieldDescriptorProto.Type.TYPE_STRING, "string"),
            Map.entry(FieldDescriptorProto.Type.TYPE_MESSAGE, "message"),
            Map.entry(FieldDescriptorProto.Type.TYPE_BYTES, "bytes"),
            Map.entry(FieldDescriptorProto.Type.TYPE_UINT32, "uint32"),
            Map.entry(FieldDescriptorProto.Type.TYPE_ENUM, "enum"),
            Map.entry(FieldDescriptorProto.Type.TYPE_SFIXED32, "sfixed32"),
            Map.entry(FieldDescriptorProto.Type.TYPE_SFIXED64, "sfixed64"),
            Map.entry(FieldDescriptorProto.Type.TYPE_SINT32, "sint32"),
            Map.entry(FieldDescriptorProto.Type.TYPE_SINT64, "sint64")
    );

    private static final Map<FieldDescriptorProto.Type, String> JAVA_TYPE_NAMES = Map.ofEntries(
            Map.entry(FieldDescriptorProto.Type.TYPE_DOUBLE, "double"),
            Map.entry(FieldDescriptorProto.Type.TYPE_FLOAT, "float"),
            Map.entry(FieldDescriptorProto.Type.TYPE_INT64, "long"),
            Map.entry(FieldDescriptorProto.Type.TYPE_UINT64, "long"),
            Map.entry(FieldDescriptorProto.Type.TYPE_INT32, "int"),
            Map.entry(FieldDescriptorProto.Type.TYPE_FIXED64, "long"),
            Map.entry(FieldDescriptorProto.Type.TYPE_FIXED32, "int"),
            Map.entry(FieldDescriptorProto.Type.TYPE_BOOL, "boolean"),
            Map.entry(FieldDescriptorProto.Type.TYPE_STRING, "String"),
            Map.entry(FieldDescriptorProto.Type.TYPE_BYTES, "byte[]"),
            Map.entry(FieldDescriptorProto.Type.TYPE_UINT32, "int"),
            Map.entry(FieldDescriptorProto.Type.TYPE_SFIXED32, "int"),
            Map.entry(FieldDescriptorProto.Type.TYPE_SFIXED64, "long"),
            Map.entry(FieldDescriptorProto.Type.TYPE_SINT32, "int"),
            Map.entry(FieldDescriptorProto.Type.TYPE_SINT64, "long")
    );

    public static ProtoFileDescriptor convert(FileDescriptorProto fileProto) {
        String javaPackage = resolveJavaPackage(fileProto);
        String syntax = fileProto.getSyntax().isEmpty() ? "proto2" : fileProto.getSyntax();

        List<ProtoEnumDescriptor> enums = fileProto.getEnumTypeList().stream()
                .map(DescriptorConverter::convertEnum)
                .collect(Collectors.toList());

        List<ProtoMessageDescriptor> messages = fileProto.getMessageTypeList().stream()
                .map(DescriptorConverter::convertMessage)
                .collect(Collectors.toList());

        return new ProtoFileDescriptor(javaPackage, syntax, messages, enums);
    }

    private static String resolveJavaPackage(FileDescriptorProto fileProto) {
        if (fileProto.hasOptions() && fileProto.getOptions().hasJavaPackage()) {
            return fileProto.getOptions().getJavaPackage();
        }
        return fileProto.getPackage();
    }

    private static ProtoMessageDescriptor convertMessage(DescriptorProto messageProto) {
        List<ProtoEnumDescriptor> nestedEnums = messageProto.getEnumTypeList().stream()
                .map(DescriptorConverter::convertEnum)
                .collect(Collectors.toList());

        List<ProtoMessageDescriptor> nestedMessages = messageProto.getNestedTypeList().stream()
                .map(DescriptorConverter::convertMessage)
                .collect(Collectors.toList());

        // Build oneof descriptors
        List<ProtoOneofDescriptor> oneofs = new ArrayList<>();
        for (int i = 0; i < messageProto.getOneofDeclCount(); i++) {
            OneofDescriptorProto oneofProto = messageProto.getOneofDecl(i);
            oneofs.add(new ProtoOneofDescriptor(oneofProto.getName(), i));
        }

        List<ProtoFieldDescriptor> fields = messageProto.getFieldList().stream()
                .map(f -> convertField(f, oneofs))
                .collect(Collectors.toList());

        return new ProtoMessageDescriptor(messageProto.getName(), fields, nestedMessages, nestedEnums, oneofs);
    }

    private static ProtoFieldDescriptor convertField(FieldDescriptorProto fieldProto,
                                                        List<ProtoOneofDescriptor> oneofs) {
        String name = fieldProto.getName();
        int number = fieldProto.getNumber();

        FieldDescriptorProto.Type type = fieldProto.getType();
        String protoType = PROTO_TYPE_NAMES.get(type);

        String javaType;
        if (type == FieldDescriptorProto.Type.TYPE_MESSAGE || type == FieldDescriptorProto.Type.TYPE_ENUM) {
            javaType = resolveTypeName(fieldProto.getTypeName());
        } else {
            javaType = JAVA_TYPE_NAMES.get(type);
        }

        ProtoFieldDescriptor.Label label;
        switch (fieldProto.getLabel()) {
            case LABEL_REQUIRED:
                label = ProtoFieldDescriptor.Label.REQUIRED;
                break;
            case LABEL_REPEATED:
                label = ProtoFieldDescriptor.Label.REPEATED;
                break;
            default:
                label = ProtoFieldDescriptor.Label.OPTIONAL;
                break;
        }

        boolean packed = fieldProto.hasOptions() && fieldProto.getOptions().hasPacked()
                && fieldProto.getOptions().getPacked();

        boolean defaultValueSet = fieldProto.hasDefaultValue();
        String defaultValue = null;
        String defaultValueAsString = null;

        if (defaultValueSet) {
            String rawDefault = fieldProto.getDefaultValue();
            if (type == FieldDescriptorProto.Type.TYPE_STRING) {
                defaultValue = rawDefault;
                defaultValueAsString = "\"" + rawDefault + "\"";
            } else if (type == FieldDescriptorProto.Type.TYPE_BOOL) {
                defaultValue = rawDefault;
                defaultValueAsString = rawDefault;
            } else if (type == FieldDescriptorProto.Type.TYPE_ENUM) {
                defaultValue = rawDefault;
                defaultValueAsString = javaType + "." + rawDefault;
            } else if (type == FieldDescriptorProto.Type.TYPE_FLOAT) {
                defaultValue = rawDefault;
                defaultValueAsString = rawDefault + "f";
            } else if (type == FieldDescriptorProto.Type.TYPE_INT64 || type == FieldDescriptorProto.Type.TYPE_UINT64
                    || type == FieldDescriptorProto.Type.TYPE_SINT64 || type == FieldDescriptorProto.Type.TYPE_FIXED64
                    || type == FieldDescriptorProto.Type.TYPE_SFIXED64) {
                defaultValue = rawDefault;
                defaultValueAsString = rawDefault + "l";
            } else {
                defaultValue = rawDefault;
                defaultValueAsString = rawDefault;
            }
        }

        // No docs available from binary descriptor sets
        List<String> docs = Collections.emptyList();

        // Oneof membership
        int oneofIndex = -1;
        String oneofName = null;
        if (fieldProto.hasOneofIndex()) {
            oneofIndex = fieldProto.getOneofIndex();
            if (oneofIndex >= 0 && oneofIndex < oneofs.size()) {
                oneofName = oneofs.get(oneofIndex).getName();
            }
        }

        return new ProtoFieldDescriptor(name, number, protoType, javaType, label, packed,
                defaultValueSet, defaultValue, defaultValueAsString, docs, oneofIndex, oneofName);
    }

    private static ProtoEnumDescriptor convertEnum(EnumDescriptorProto enumProto) {
        List<ProtoEnumDescriptor.Value> values = enumProto.getValueList().stream()
                .sorted(Comparator.comparingInt(EnumValueDescriptorProto::getNumber))
                .map(v -> new ProtoEnumDescriptor.Value(v.getName(), v.getNumber()))
                .collect(Collectors.toList());

        return new ProtoEnumDescriptor(enumProto.getName(), values);
    }

    /**
     * Resolves a fully-qualified proto type name to a simple Java class name.
     * Proto type names from descriptors are like ".package.OuterMessage.InnerMessage".
     * We take the last component as the Java type name.
     */
    private static String resolveTypeName(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return typeName;
        }
        int lastDot = typeName.lastIndexOf('.');
        if (lastDot >= 0) {
            return typeName.substring(lastDot + 1);
        }
        return typeName;
    }
}
