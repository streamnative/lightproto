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

import java.io.PrintWriter;

public abstract class LightProtoField {

    protected final ProtoFieldDescriptor field;
    protected final int index;
    protected final String ccName;

    protected LightProtoField(ProtoFieldDescriptor field, int index) {
        this.field = field;
        this.index = index;
        this.ccName = Util.camelCase(field.getName());
    }

    public static LightProtoField create(ProtoFieldDescriptor field, int index) {
        if (field.isMapField()) {
            return new LightProtoMapField(field, index);
        }
        if (field.isRepeated()) {
            if (field.isMessageField()) {
                return new LightProtoRepeatedMessageField(field, index);
            } else if (field.isStringField()) {
                return new LightProtoRepeatedStringField(field, index);
            } else if (field.isEnumField()) {
                return new LightProtoRepeatedEnumField(field, index);
            } else if (field.isNumberField() || field.isBoolField()) {
                return new LightProtoRepeatedNumberField(field, index);
            } else if (field.isBytesField()) {
                return new LightProtoRepeatedBytesField(field, index);
            }
        } else if (field.isMessageField()) {
            return new LightProtoMessageField(field, index);
        } else if (field.isBytesField()) {
            return new LightProtoBytesField(field, index);
        } else if (field.isStringField()) {
            return new LightProtoStringField(field, index);
        } else if (field.isEnumField()) {
            return new LightProtoEnumField(field, index);
        } else if (field.isNumberField()) {
            return new LightProtoNumberField(field, index);
        } else if (field.isBoolField()) {
            return new LightProtoBooleanField(field, index);
        }

        throw new IllegalArgumentException("Unknown field: " + field);
    }

    public int index() {
        return index;
    }

    public boolean isRepeated() {
        return field.isRepeated();
    }

    public boolean isEnum() {
        return field.isEnumField();
    }

    public boolean isRequired() {
        return field.isRequired();
    }

    public void docs(PrintWriter w) {
        String doc = field.getDoc();
        if (doc != null && !doc.isEmpty()) {
            Util.writeJavadoc(w, doc, "        ");
        }
    }

    abstract public void declaration(PrintWriter w);

    public void tags(PrintWriter w) {
        w.format("        private static final int %s = %d;\n", fieldNumber(), field.getNumber());
        w.format("        private static final int %s = (%s << LightProtoCodec.TAG_TYPE_BITS) | %s;\n", tagName(), fieldNumber(), typeTag());
        w.format("        private static final int %s_SIZE = LightProtoCodec.computeVarIntSize(%s);\n", tagName(), tagName());
        if (!field.isRepeated() && !field.isOneofMember() && !field.hasImplicitPresence()) {
            w.format("        private static final int %s = 1 << (%d %% 32);\n", fieldMask(), index);
        }
    }

    public void has(PrintWriter w) {
        if (field.hasImplicitPresence()) {
            return; // No has() for proto3 implicit presence fields
        }
        w.format("        /** Returns whether the {@code %s} field is set. */\n", field.getName());
        w.format("        public boolean %s() {\n", Util.camelCase("has", field.getName()));
        if (field.isOneofMember()) {
            w.format("            return _%sCase == %s;\n", Util.camelCase(field.getOneofName()), fieldNumber());
        } else {
            w.format("            return (_bitField%d & %s) != 0;\n", bitFieldIndex(), fieldMask());
        }
        w.format("        }\n");
    }

    abstract public void clear(PrintWriter w);

    public void fieldClear(PrintWriter w, String enclosingType) {
        w.format("        /** Clear the {@code %s} field. */\n", field.getName());
        w.format("        public %s %s() {\n", enclosingType, Util.camelCase("clear", field.getName()));
        if (field.isOneofMember()) {
            w.format("            if (_%sCase == %s) {\n", Util.camelCase(field.getOneofName()), fieldNumber());
            w.format("                _%sCase = 0;\n", Util.camelCase(field.getOneofName()));
            clear(w);
            w.format("            }\n");
        } else if (!field.hasImplicitPresence()) {
            w.format("            _bitField%d &= ~%s;\n", bitFieldIndex(), fieldMask());
            clear(w);
        } else {
            clear(w);
        }
        w.format("            return this;\n");
        w.format("        }\n");
    }

    abstract public void setter(PrintWriter w, String enclosingType);

    abstract public void getter(PrintWriter w);

    abstract public void serializedSize(PrintWriter w);

    abstract public void serialize(PrintWriter w);

    abstract public void serializeJson(PrintWriter w);

    abstract public void parseJson(PrintWriter w);

    abstract public void parse(PrintWriter w);

    abstract public void copy(PrintWriter w);

    /**
     * Generate code that compares this field's value in {@code this} vs {@code _other},
     * writing {@code if (...) return false;} when they differ.
     * Called only when both objects are known to have the field present.
     */
    abstract public void equalsCode(PrintWriter w);

    /**
     * Generate code that folds this field's value into the running hash variable {@code _h}.
     * Called only when the field is known to be present.
     */
    abstract public void hashCodeCode(PrintWriter w);

    /**
     * Generate code to eagerly resolve any lazily-deserialized data for this field,
     * so the object no longer depends on _parsedBuffer.
     * Default: no-op (already eagerly deserialized).
     */
    public void materialize(PrintWriter w) {
        // Numbers, booleans, enums are already eagerly deserialized — nothing to do.
    }

    public boolean isPackable() {
        return field.isPackable();
    }

    public void parsePacked(PrintWriter w) {
    }

    abstract protected String typeTag();

    protected String writeTagExpr(String tag) {
        if (field.getNumber() <= 15) {
            return String.format("_addr = LightProtoCodec.writeRawByte(_base, _addr, %s)", tag);
        } else {
            return String.format("_addr = LightProtoCodec.writeRawVarInt(_base, _addr, %s)", tag);
        }
    }

    protected String tagName() {
        return "_" + Util.upperCase(field.getName(), "tag");
    }

    protected String fieldNumber() {
        return "_" + Util.upperCase(field.getName(), "fieldNumber");
    }

    protected String fieldMask() {
        return "_" + Util.upperCase(field.getName(), "mask");
    }

    protected int bitFieldIndex() {
        return index / 32;
    }

    protected void writeSetPresence(PrintWriter w) {
        if (field.hasImplicitPresence()) {
            return; // No presence tracking for proto3 implicit presence
        }
        if (field.isOneofMember()) {
            w.format("    _%sCase = %s;\n", Util.camelCase(field.getOneofName()), fieldNumber());
        } else {
            w.format("    _bitField%d |= %s;\n", bitFieldIndex(), fieldMask());
        }
    }

    /**
     * Returns the Java expression to guard serialization, or null if always serialized.
     * For explicit presence: has() check. For implicit presence: non-default check.
     */
    public String serializeCondition() {
        if (field.isRequired() || field.isRepeated()) {
            return null;
        }
        if (field.hasImplicitPresence()) {
            return nonDefaultCondition();
        }
        return Util.camelCase("has", field.getName()) + "()";
    }

    /**
     * Returns the Java expression that is true when this field has a non-default value.
     * Only called for proto3 implicit presence fields.
     */
    protected String nonDefaultCondition() {
        throw new UnsupportedOperationException("nonDefaultCondition not implemented for " + getClass().getSimpleName());
    }

    public boolean isOneofMember() {
        return field.isOneofMember();
    }

    public String oneofCaseFieldName() {
        return "_" + Util.camelCase(field.getOneofName()) + "Case";
    }
}
