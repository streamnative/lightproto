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

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Internal model representing a protobuf field descriptor, decoupled from any specific parser.
 * Method names are chosen to minimize changes in the existing generator code.
 */
public class ProtoFieldDescriptor {

    public enum Label {
        REQUIRED, OPTIONAL, REPEATED
    }

    private static final Set<String> NUMBER_TYPES = Set.of(
            "int32", "int64", "uint32", "uint64", "sint32", "sint64",
            "fixed32", "fixed64", "sfixed32", "sfixed64", "float", "double"
    );

    private static final Set<String> PACKABLE_TYPES = Set.of(
            "int32", "int64", "uint32", "uint64", "sint32", "sint64",
            "fixed32", "fixed64", "sfixed32", "sfixed64", "float", "double",
            "bool", "enum"
    );

    private final String name;
    private final int number;
    private final String protoType;   // "int32", "string", "bool", "message", "enum", etc.
    private final String javaType;    // "int", "String", "boolean", class name for message/enum
    private final Label label;
    private final boolean packed;
    private final boolean defaultValueSet;
    private final String defaultValue;        // Raw default value (e.g. "hello" for strings, null for no-default)
    private final String defaultValueAsString; // Default value as code literal (e.g. "5" for int, "true" for bool)
    private final List<String> docs;

    public ProtoFieldDescriptor(String name, int number, String protoType, String javaType,
                                Label label, boolean packed,
                                boolean defaultValueSet, String defaultValue,
                                String defaultValueAsString, List<String> docs) {
        this.name = name;
        this.number = number;
        this.protoType = protoType;
        this.javaType = javaType;
        this.label = label;
        this.packed = packed;
        this.defaultValueSet = defaultValueSet;
        this.defaultValue = defaultValue;
        this.defaultValueAsString = defaultValueAsString;
        this.docs = docs != null ? docs : Collections.emptyList();
    }

    public String getName() {
        return name;
    }

    public int getNumber() {
        return number;
    }

    public String getProtoType() {
        return protoType;
    }

    public String getJavaType() {
        return javaType;
    }

    public boolean isRepeated() {
        return label == Label.REPEATED;
    }

    public boolean isRequired() {
        return label == Label.REQUIRED;
    }

    public boolean isMessageField() {
        return "message".equals(protoType);
    }

    public boolean isStringField() {
        return "string".equals(protoType);
    }

    public boolean isEnumField() {
        return "enum".equals(protoType);
    }

    public boolean isBoolField() {
        return "bool".equals(protoType);
    }

    public boolean isBytesField() {
        return "bytes".equals(protoType);
    }

    public boolean isNumberField() {
        return NUMBER_TYPES.contains(protoType);
    }

    public boolean isPackable() {
        return isRepeated() && PACKABLE_TYPES.contains(protoType);
    }

    /**
     * Whether this field uses packed encoding.
     * Replaces the old {@code field.getOption("packed") == Boolean.TRUE} pattern.
     */
    public boolean isPacked() {
        return packed;
    }

    public boolean isDefaultValueSet() {
        return defaultValueSet;
    }

    /**
     * Returns the raw default value. For strings this returns the unquoted string
     * (or null if no default). For numbers, returns the numeric literal as string.
     */
    public Object getDefaultValue() {
        return defaultValue;
    }

    public String getDefaultValueAsString() {
        return defaultValueAsString;
    }

    public List<String> getDocs() {
        return docs;
    }
}
