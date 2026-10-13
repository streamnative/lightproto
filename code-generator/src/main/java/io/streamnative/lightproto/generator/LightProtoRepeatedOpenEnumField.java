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

/**
 * A repeated open enum field: the elements are held as their numbers, as in
 * {@link LightProtoOpenEnumField}, so parsing keeps each element in place, whether the enum
 * defines its number or not.
 */
public class LightProtoRepeatedOpenEnumField extends LightProtoRepeatedNumberField {

    private final String addValueMethod;

    public LightProtoRepeatedOpenEnumField(ProtoFieldDescriptor field, int index) {
        super(field, index);
        this.addValueMethod = "_" + Util.camelCase("add", singularName, "value");
    }

    @Override
    protected String elementType() {
        return "int";
    }

    @Override
    protected String parseAddMethod() {
        return addValueMethod;
    }

    @Override
    public void getter(PrintWriter w) {
        String valueAt = Util.camelCase("get", singularName, "value", "at");
        w.format("private static final int %s_PACKED = (%s << LightProtoCodec.TAG_TYPE_BITS) | LightProtoCodec.WIRETYPE_LENGTH_DELIMITED;\n", tagName(), fieldNumber());
        w.format("/** Returns the number of elements in the {@code %s} list. */\n", field.getName());
        w.format("public int %s() {\n", Util.camelCase("get", pluralName, "count"));
        w.format("    return _%sCount;\n", pluralName);
        w.format("}\n");
        w.format("/**\n");
        w.format(" * Returns the element at the given index in the {@code %s} list, or {@code UNRECOGNIZED}\n", field.getName());
        w.format(" * for a number the enum doesn't define: {@link #%s(int)} returns the number.\n", valueAt);
        w.format(" */\n");
        w.format("public %s %s(int idx) {\n", field.getJavaType(), Util.camelCase("get", singularName, "at"));
        LightProtoOpenEnumField.returnEnum(w, field.getJavaType(), valueAt + "(idx)");
        w.format("}\n");
        w.format("/** Returns the number of the element at the given index in the {@code %s} list, which the enum may not define. */\n", field.getName());
        w.format("public int %s(int idx) {\n", valueAt);
        w.format("    if (idx < 0 || idx >= _%sCount) {\n", pluralName);
        w.format("        throw new IndexOutOfBoundsException(\"Index \" + idx + \" is out of the list size (\" + _%sCount + \") for field '%s'\");\n", pluralName, field.getName());
        w.format("    }\n");
        w.format("    return %s[idx];\n", pluralName);
        w.format("}\n");
    }

    @Override
    public void setter(PrintWriter w, String enclosingType) {
        w.format("/** Adds a value to the {@code %s} list. */\n", field.getName());
        w.format("public void %s(%s %s) {\n", Util.camelCase("add", singularName), field.getJavaType(), singularName);
        // getValue() throws for UNRECOGNIZED, which has no number to write
        w.format("    %s(%s.getValue());\n", addValueMethod, singularName);
        w.format("}\n");
        w.format("private void %s(int %s) {\n", addValueMethod, singularName);
        w.format("    if (%s == null) {\n", pluralName);
        w.format("        %s = new int[4];\n", pluralName);
        w.format("    }\n");
        w.format("    if (%s.length == _%sCount) {\n", pluralName, pluralName);
        w.format("        %s = java.util.Arrays.copyOf(%s, _%sCount * 2);\n", pluralName, pluralName, pluralName);
        w.format("    }\n");
        w.format("    _cachedSize = -1;\n");
        w.format("    %s[_%sCount++] = %s;\n", pluralName, pluralName, singularName);
        w.format("}\n");
    }

    @Override
    public void copy(PrintWriter w) {
        w.format("for (int i = 0; i < _other._%sCount; i++) {\n", pluralName);
        w.format("    %s(_other.%s[i]);\n", addValueMethod, pluralName);
        w.format("}\n");
    }

    @Override
    public void serializeJson(PrintWriter w) {
        w.format("_b.writeByte('[');\n");
        w.format("for (int i = 0; i < _%sCount; i++) {\n", pluralName);
        w.format("    if (i > 0) { _b.writeByte(','); }\n");
        w.format("    %s\n", LightProtoOpenEnumField.writeJson(field.getJavaType(), pluralName + "[i]"));
        w.format("}\n");
        w.format("_b.writeByte(']');\n");
    }

    @Override
    public void parseJson(PrintWriter w) {
        w.format("                _r.expect((byte) '[');\n");
        w.format("                if (!_r.tryConsume((byte) ']')) {\n");
        w.format("                    do {\n");
        w.format("                        %s(%s);\n", addValueMethod, LightProtoOpenEnumField.parseJsonNumber(field.getJavaType()));
        w.format("                    } while (_r.tryConsume((byte) ','));\n");
        w.format("                    _r.expect((byte) ']');\n");
        w.format("                }\n");
    }

    @Override
    public void serializeTextFormat(PrintWriter w) {
        w.format("for (int i = 0; i < _%sCount; i++) {\n", pluralName);
        w.format("    LightProtoCodec.writeTextFormatIndent(_sb, _indent);\n");
        w.format("    _sb.append(\"%s: \");\n", field.getName());
        w.format("    %s\n", LightProtoOpenEnumField.writeTextFormat(field.getJavaType(), pluralName + "[i]"));
        w.format("    _sb.append('\\n');\n");
        w.format("}\n");
    }

    @Override
    public void parseTextFormat(PrintWriter w) {
        String add = String.format("%s(%s);", addValueMethod, LightProtoOpenEnumField.parseTextFormatNumber(field.getJavaType()));
        w.format("                _r.consumeFieldSeparator();\n");
        w.format("                if (_r.atArrayStart()) {\n");
        w.format("                    _r.expect('[');\n");
        w.format("                    if (!_r.tryConsume(']')) {\n");
        w.format("                        do {\n");
        w.format("                            %s\n", add);
        w.format("                        } while (_r.tryConsume(','));\n");
        w.format("                        _r.expect(']');\n");
        w.format("                    }\n");
        w.format("                } else {\n");
        w.format("                    %s\n", add);
        w.format("                }\n");
    }
}
