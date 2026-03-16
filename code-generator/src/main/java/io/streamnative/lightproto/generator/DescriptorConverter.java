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
package io.streamnative.lightproto.generator;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.DescriptorProtos.SourceCodeInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * Converts a single proto file descriptor without cross-package type resolution.
     * Types from other packages will use simple (unqualified) names.
     */
    public static ProtoFileDescriptor convert(FileDescriptorProto fileProto) {
        return convert(fileProto, Collections.emptyMap());
    }

    /**
     * Converts a proto file descriptor with cross-package type resolution.
     * Types from other packages will use fully-qualified Java class names.
     *
     * @param fileProto the file descriptor to convert
     * @param typeRegistry maps fully-qualified proto type names (e.g. ".pulsar.proto.Subscription")
     *                     to their fully-qualified Java class names
     */
    public static ProtoFileDescriptor convert(FileDescriptorProto fileProto,
                                              Map<String, String> typeRegistry) {
        String javaPackage = resolveJavaPackage(fileProto);
        String syntax = fileProto.getSyntax().isEmpty() ? "proto2" : fileProto.getSyntax();
        String protoPackage = fileProto.getPackage();

        TypeResolver resolver = new TypeResolver(javaPackage, typeRegistry);

        // Build comments map from SourceCodeInfo
        Map<List<Integer>, String> comments = buildCommentsMap(fileProto);

        List<ProtoEnumDescriptor> enums = new ArrayList<>();
        for (int i = 0; i < fileProto.getEnumTypeCount(); i++) {
            enums.add(convertEnum(fileProto.getEnumType(i), comments, new int[]{5, i}));
        }

        List<ProtoMessageDescriptor> messages = new ArrayList<>();
        for (int i = 0; i < fileProto.getMessageTypeCount(); i++) {
            messages.add(convertMessage(fileProto.getMessageType(i), syntax, resolver,
                    comments, new int[]{4, i}));
        }

        List<ProtoServiceDescriptor> services = new ArrayList<>();
        for (int i = 0; i < fileProto.getServiceCount(); i++) {
            services.add(convertService(fileProto.getService(i), protoPackage, resolver,
                    comments, i));
        }

        return new ProtoFileDescriptor(javaPackage, syntax, messages, enums, services);
    }

    /**
     * Builds a map from SourceCodeInfo path to leading comment text.
     */
    private static Map<List<Integer>, String> buildCommentsMap(FileDescriptorProto fileProto) {
        Map<List<Integer>, String> comments = new HashMap<>();
        if (fileProto.hasSourceCodeInfo()) {
            for (SourceCodeInfo.Location loc : fileProto.getSourceCodeInfo().getLocationList()) {
                if (loc.hasLeadingComments()) {
                    String comment = loc.getLeadingComments().trim();
                    if (!comment.isEmpty()) {
                        comments.put(loc.getPathList(), comment);
                    }
                }
            }
        }
        return comments;
    }

    /**
     * Looks up a doc comment for the given path.
     */
    private static String getDoc(Map<List<Integer>, String> comments, int... path) {
        List<Integer> key = new ArrayList<>(path.length);
        for (int p : path) {
            key.add(p);
        }
        return comments.get(key);
    }

    /**
     * Concatenates a parent path with additional path components.
     */
    private static int[] appendPath(int[] parentPath, int... extra) {
        int[] result = new int[parentPath.length + extra.length];
        System.arraycopy(parentPath, 0, result, 0, parentPath.length);
        System.arraycopy(extra, 0, result, parentPath.length, extra.length);
        return result;
    }

    /**
     * Builds a type registry from a list of file descriptors. Maps fully-qualified proto type names
     * to their fully-qualified Java class names.
     */
    public static Map<String, String> buildTypeRegistry(List<FileDescriptorProto> allFiles) {
        Map<String, String> registry = new HashMap<>();
        for (FileDescriptorProto file : allFiles) {
            String javaPackage = resolveJavaPackage(file);
            String protoPackage = file.getPackage();
            String prefix = protoPackage.isEmpty() ? "." : "." + protoPackage + ".";

            for (DescriptorProto msg : file.getMessageTypeList()) {
                registerMessageTypes(registry, prefix, javaPackage, "", msg);
            }
            for (EnumDescriptorProto e : file.getEnumTypeList()) {
                registry.put(prefix + e.getName(), javaPackage + "." + e.getName());
            }
        }
        return registry;
    }

    private static void registerMessageTypes(Map<String, String> registry,
                                             String protoPrefix, String javaPackage,
                                             String javaPrefix, DescriptorProto msg) {
        String protoName = protoPrefix + msg.getName();
        String javaName = javaPackage + "." + (javaPrefix.isEmpty() ? "" : javaPrefix + ".") + msg.getName();
        registry.put(protoName, javaName);

        String nestedJavaPrefix = javaPrefix.isEmpty() ? msg.getName() : javaPrefix + "." + msg.getName();
        for (DescriptorProto nested : msg.getNestedTypeList()) {
            registerMessageTypes(registry, protoName + ".", javaPackage, nestedJavaPrefix, nested);
        }
        for (EnumDescriptorProto e : msg.getEnumTypeList()) {
            registry.put(protoName + "." + e.getName(),
                    javaPackage + "." + nestedJavaPrefix + "." + e.getName());
        }
    }

    private static String resolveJavaPackage(FileDescriptorProto fileProto) {
        if (fileProto.hasOptions() && fileProto.getOptions().hasJavaPackage()) {
            return fileProto.getOptions().getJavaPackage();
        }
        return fileProto.getPackage();
    }

    private static ProtoMessageDescriptor convertMessage(DescriptorProto messageProto, String syntax,
                                                         TypeResolver resolver,
                                                         Map<List<Integer>, String> comments,
                                                         int[] parentPath) {
        String messageDoc = getDoc(comments, parentPath);

        List<ProtoEnumDescriptor> nestedEnums = new ArrayList<>();
        for (int i = 0; i < messageProto.getEnumTypeCount(); i++) {
            nestedEnums.add(convertEnum(messageProto.getEnumType(i), comments,
                    appendPath(parentPath, 4, i)));
        }

        // Detect map_entry synthetic messages
        Map<String, DescriptorProto> mapEntryTypes = new HashMap<>();
        for (DescriptorProto nested : messageProto.getNestedTypeList()) {
            if (nested.hasOptions() && nested.getOptions().getMapEntry()) {
                mapEntryTypes.put(nested.getName(), nested);
            }
        }

        // Filter out map_entry messages from nested messages
        List<ProtoMessageDescriptor> nestedMessages = new ArrayList<>();
        int nestedIndex = 0;
        for (int i = 0; i < messageProto.getNestedTypeCount(); i++) {
            DescriptorProto nested = messageProto.getNestedType(i);
            if (!mapEntryTypes.containsKey(nested.getName())) {
                nestedMessages.add(convertMessage(nested, syntax, resolver,
                        comments, appendPath(parentPath, 3, i)));
            }
        }

        // Identify synthetic oneofs (created by protoc for proto3 optional fields)
        Set<Integer> syntheticOneofIndices = new HashSet<>();
        for (FieldDescriptorProto f : messageProto.getFieldList()) {
            if (f.getProto3Optional() && f.hasOneofIndex()) {
                syntheticOneofIndices.add(f.getOneofIndex());
            }
        }

        // Build oneof descriptors, filtering out synthetic oneofs
        List<ProtoOneofDescriptor> oneofs = new ArrayList<>();
        for (int i = 0; i < messageProto.getOneofDeclCount(); i++) {
            if (syntheticOneofIndices.contains(i)) {
                continue;
            }
            OneofDescriptorProto oneofProto = messageProto.getOneofDecl(i);
            oneofs.add(new ProtoOneofDescriptor(oneofProto.getName(), i));
        }

        List<ProtoFieldDescriptor> fields = new ArrayList<>();
        for (int i = 0; i < messageProto.getFieldCount(); i++) {
            String fieldDoc = getDoc(comments, appendPath(parentPath, 2, i));
            fields.add(convertField(messageProto.getField(i), oneofs, mapEntryTypes, syntax,
                    syntheticOneofIndices, resolver, fieldDoc));
        }

        return new ProtoMessageDescriptor(messageProto.getName(), fields, nestedMessages,
                nestedEnums, oneofs, messageDoc);
    }

    private static final Set<String> PACKABLE_PROTO_TYPES = Set.of(
            "int32", "int64", "uint32", "uint64", "sint32", "sint64",
            "fixed32", "fixed64", "sfixed32", "sfixed64", "float", "double",
            "bool", "enum"
    );

    private static ProtoFieldDescriptor convertField(FieldDescriptorProto fieldProto,
                                                        List<ProtoOneofDescriptor> oneofs,
                                                        Map<String, DescriptorProto> mapEntryTypes,
                                                        String syntax,
                                                        Set<Integer> syntheticOneofIndices,
                                                        TypeResolver resolver,
                                                        String doc) {
        boolean proto3 = "proto3".equals(syntax);
        boolean proto3Optional = fieldProto.getProto3Optional();

        String name = fieldProto.getName();
        int number = fieldProto.getNumber();

        FieldDescriptorProto.Type type = fieldProto.getType();
        String protoType = PROTO_TYPE_NAMES.get(type);

        String javaType;
        if (type == FieldDescriptorProto.Type.TYPE_MESSAGE || type == FieldDescriptorProto.Type.TYPE_ENUM) {
            javaType = resolver.resolveTypeName(fieldProto.getTypeName());
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

        // Packed encoding: proto3 defaults to packed for packable repeated fields
        boolean packed;
        if (proto3) {
            boolean packable = label == ProtoFieldDescriptor.Label.REPEATED
                    && PACKABLE_PROTO_TYPES.contains(protoType);
            boolean explicitlyUnpacked = fieldProto.hasOptions() && fieldProto.getOptions().hasPacked()
                    && !fieldProto.getOptions().getPacked();
            packed = packable && !explicitlyUnpacked;
        } else {
            packed = fieldProto.hasOptions() && fieldProto.getOptions().hasPacked()
                    && fieldProto.getOptions().getPacked();
        }

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

        // Oneof membership — skip synthetic oneofs (created for proto3 optional)
        int oneofIndex = -1;
        String oneofName = null;
        if (fieldProto.hasOneofIndex() && !syntheticOneofIndices.contains(fieldProto.getOneofIndex())) {
            oneofIndex = fieldProto.getOneofIndex();
            for (ProtoOneofDescriptor oneof : oneofs) {
                if (oneof.getIndex() == oneofIndex) {
                    oneofName = oneof.getName();
                    break;
                }
            }
        }

        // Check for map field
        boolean isMapField = false;
        ProtoFieldDescriptor mapKeyField = null;
        ProtoFieldDescriptor mapValueField = null;
        if (label == ProtoFieldDescriptor.Label.REPEATED
                && type == FieldDescriptorProto.Type.TYPE_MESSAGE) {
            // For map entry lookup, use simple name (map entries are always nested in the same message)
            String entryTypeName = resolveSimpleTypeName(fieldProto.getTypeName());
            DescriptorProto entryProto = mapEntryTypes.get(entryTypeName);
            if (entryProto != null) {
                isMapField = true;
                for (FieldDescriptorProto entryField : entryProto.getFieldList()) {
                    ProtoFieldDescriptor entryFieldDesc = convertSimpleField(entryField, resolver);
                    if (entryField.getNumber() == 1) {
                        mapKeyField = entryFieldDesc;
                    } else if (entryField.getNumber() == 2) {
                        mapValueField = entryFieldDesc;
                    }
                }
            }
        }

        return new ProtoFieldDescriptor(name, number, protoType, javaType, label, packed,
                defaultValueSet, defaultValue, defaultValueAsString, doc,
                oneofIndex, oneofName, isMapField, mapKeyField, mapValueField,
                proto3, proto3Optional);
    }

    /**
     * Converts a field descriptor to a simple ProtoFieldDescriptor (used for map key/value fields).
     */
    private static ProtoFieldDescriptor convertSimpleField(FieldDescriptorProto fieldProto,
                                                           TypeResolver resolver) {
        FieldDescriptorProto.Type type = fieldProto.getType();
        String protoType = PROTO_TYPE_NAMES.get(type);
        String javaType;
        if (type == FieldDescriptorProto.Type.TYPE_MESSAGE || type == FieldDescriptorProto.Type.TYPE_ENUM) {
            javaType = resolver.resolveTypeName(fieldProto.getTypeName());
        } else {
            javaType = JAVA_TYPE_NAMES.get(type);
        }
        return new ProtoFieldDescriptor(fieldProto.getName(), fieldProto.getNumber(),
                protoType, javaType, ProtoFieldDescriptor.Label.OPTIONAL, false,
                false, null, null, null);
    }

    private static ProtoEnumDescriptor convertEnum(EnumDescriptorProto enumProto,
                                                    Map<List<Integer>, String> comments,
                                                    int[] parentPath) {
        String enumDoc = getDoc(comments, parentPath);

        List<ProtoEnumDescriptor.Value> values = new ArrayList<>();
        List<EnumValueDescriptorProto> sortedValues = enumProto.getValueList().stream()
                .sorted(Comparator.comparingInt(EnumValueDescriptorProto::getNumber))
                .collect(Collectors.toList());

        // We need the original index for the path lookup, not the sorted index
        for (int i = 0; i < enumProto.getValueCount(); i++) {
            EnumValueDescriptorProto v = enumProto.getValue(i);
            String valueDoc = getDoc(comments, appendPath(parentPath, 2, i));
            values.add(new ProtoEnumDescriptor.Value(v.getName(), v.getNumber(), valueDoc));
        }

        // Sort by number after adding docs
        values.sort(Comparator.comparingInt(ProtoEnumDescriptor.Value::getNumber));

        return new ProtoEnumDescriptor(enumProto.getName(), values, enumDoc);
    }

    private static ProtoServiceDescriptor convertService(ServiceDescriptorProto serviceProto,
                                                         String protoPackage,
                                                         TypeResolver resolver,
                                                         Map<List<Integer>, String> comments,
                                                         int serviceIndex) {
        String serviceDoc = getDoc(comments, 6, serviceIndex);

        List<ProtoMethodDescriptor> methods = new ArrayList<>();
        for (int i = 0; i < serviceProto.getMethodCount(); i++) {
            MethodDescriptorProto m = serviceProto.getMethod(i);
            String methodDoc = getDoc(comments, 6, serviceIndex, 2, i);
            methods.add(new ProtoMethodDescriptor(
                    m.getName(),
                    resolver.resolveTypeName(m.getInputType()),
                    resolver.resolveTypeName(m.getOutputType()),
                    m.getClientStreaming(),
                    m.getServerStreaming(),
                    methodDoc));
        }
        return new ProtoServiceDescriptor(serviceProto.getName(), protoPackage, methods, serviceDoc);
    }

    /**
     * Resolves a fully-qualified proto type name to a simple (last component) name.
     * Used only for map entry type lookup where the entry is always in the same message.
     */
    private static String resolveSimpleTypeName(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return typeName;
        }
        int lastDot = typeName.lastIndexOf('.');
        if (lastDot >= 0) {
            return typeName.substring(lastDot + 1);
        }
        return typeName;
    }

    /**
     * Resolves proto type names to Java type names, using fully-qualified names
     * when the type is from a different Java package.
     */
    static class TypeResolver {
        private final String currentJavaPackage;
        private final Map<String, String> typeRegistry;

        TypeResolver(String currentJavaPackage, Map<String, String> typeRegistry) {
            this.currentJavaPackage = currentJavaPackage;
            this.typeRegistry = typeRegistry;
        }

        String resolveTypeName(String typeName) {
            if (typeName == null || typeName.isEmpty()) {
                return typeName;
            }

            // Look up the type in the registry
            String fqJavaName = typeRegistry.get(typeName);
            if (fqJavaName != null) {
                // Check if the type is in the same Java package
                int lastDot = fqJavaName.lastIndexOf('.');
                if (lastDot >= 0) {
                    String typePackage = fqJavaName.substring(0, lastDot);
                    if (typePackage.equals(currentJavaPackage)) {
                        // Same package: use simple name
                        return fqJavaName.substring(lastDot + 1);
                    }
                }
                // Different package: use fully-qualified name
                return fqJavaName;
            }

            // Fallback: extract simple name (for types not in the registry)
            return resolveSimpleTypeName(typeName);
        }
    }
}
