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
 * An open enum field (see {@link ProtoFieldDescriptor#isOpenEnum()}). The value is held as its
 * number, so a number the enum doesn't define is kept by parsing and written back, as in
 * protobuf-java: the getter returns {@code UNRECOGNIZED} for it, and {@code get<Field>Value()}
 * the number. Reads, writes, sizes, equals() and hashCode() work on the number, as for an int32.
 */
public class LightProtoOpenEnumField extends LightProtoNumberField {

    public LightProtoOpenEnumField(ProtoFieldDescriptor field, int index) {
        super(field, index);
    }

    @Override
    public void declaration(PrintWriter w) {
        w.format("private int %s;\n", ccName);
    }

    @Override
    public void getter(PrintWriter w) {
        String valueGetter = Util.camelCase("get", field.getName(), "value");
        w.format("        /**\n");
        w.format("         * Returns the value of the {@code %s} field, or {@code UNRECOGNIZED} for a number\n", field.getName());
        w.format("         * the enum doesn't define: {@link #%s()} returns the number.\n", valueGetter);
        w.format("         */\n");
        w.format("        public %s %s() {\n", field.getJavaType(), Util.camelCase("get", field.getName()));
        returnEnum(w, field.getJavaType(), valueGetter + "()");
        w.format("        }\n");
        w.format("        /** Returns the number of the {@code %s} field's value, which the enum may not define. */\n", field.getName());
        w.format("        public int %s() {\n", valueGetter);
        w.format("            if (!(%s)) {\n", presenceCondition());
        w.format("                return 0;\n");
        w.format("            }\n");
        w.format("            return %s;\n", ccName);
        w.format("        }\n");
    }

    @Override
    public void setter(PrintWriter w, String enclosingType) {
        w.format("/** Set the {@code %s} field. */\n", field.getName());
        w.format("public %s %s(%s %s) {\n", enclosingType, Util.camelCase("set", field.getName()), field.getJavaType(), ccName);
        // getValue() throws for UNRECOGNIZED, which has no number to write
        w.format("    this.%s = %s.getValue();\n", ccName, ccName);
        writeSetPresence(w);
        w.format("    _cachedSize = -1;\n");
        w.format("    return this;\n");
        w.format("}\n");
    }

    @Override
    public void parse(PrintWriter w) {
        // Any number is kept, so, unlike a closed enum field, this one is always present after parsing
        writeSetPresence(w);
        w.format("%s = %s;\n", ccName, parseNumber(field));
    }

    @Override
    public void copy(PrintWriter w) {
        w.format("%s = _other.%s;\n", ccName, ccName);
        writeSetPresence(w);
    }

    @Override
    protected String cmpValueExpr(String qualifier) {
        // The number: the enum constants would make two numbers the enum doesn't define equal
        if (field.hasImplicitPresence()) {
            return qualifier + Util.camelCase("get", field.getName(), "value") + "()";
        }
        return qualifier + ccName;
    }

    @Override
    public void serializeJson(PrintWriter w) {
        w.format("%s\n", writeJson(field.getJavaType(), ccName));
    }

    @Override
    public void parseJson(PrintWriter w) {
        w.format("                %s = %s;\n", ccName, parseJsonNumber(field.getJavaType()));
        writeSetPresence(w);
        w.format("                _cachedSize = -1;\n");
    }

    @Override
    public void serializeTextFormat(PrintWriter w) {
        w.format("LightProtoCodec.writeTextFormatIndent(_sb, _indent);\n");
        w.format("_sb.append(\"%s: \");\n", field.getName());
        w.format("%s\n", writeTextFormat(field.getJavaType(), ccName));
        w.format("_sb.append('\\n');\n");
    }

    @Override
    public void parseTextFormat(PrintWriter w) {
        w.format("                _r.consumeFieldSeparator();\n");
        w.format("                %s = %s;\n", ccName, parseTextFormatNumber(field.getJavaType()));
        writeSetPresence(w);
        w.format("                _cachedSize = -1;\n");
    }

    /** Emits statements returning the constant of {@code enumType} for {@code number}, or UNRECOGNIZED. */
    static void returnEnum(PrintWriter w, String enumType, String number) {
        w.format("    %s _e = %s.valueOf(%s);\n", enumType, enumType, number);
        w.format("    return _e != null ? _e : %s.UNRECOGNIZED;\n", enumType);
    }

    /** Emits the declaration of {@code var}, the constant of {@code enumType} for {@code number}, or UNRECOGNIZED. */
    static void declareEnum(PrintWriter w, String enumType, String var, String number) {
        w.format("    %s %s = %s.valueOf(%s);\n", enumType, var, enumType, number);
        w.format("    if (%s == null) {\n", var);
        w.format("        %s = %s.UNRECOGNIZED;\n", var, enumType);
        w.format("    }\n");
    }

    /** A statement writing {@code number} to the JSON buffer {@code _b}: its name, or the number the enum doesn't define. */
    static String writeJson(String enumType, String number) {
        return String.format("LightProtoCodec.writeJsonEnum(_b, %s.valueOf(%s), %s);", enumType, number, number);
    }

    /** A statement writing {@code number} to the TextFormat builder {@code _sb}: its name, or the number. */
    static String writeTextFormat(String enumType, String number) {
        return String.format("LightProtoCodec.writeTextFormatEnum(_sb, %s.valueOf(%s), %s);", enumType, number, number);
    }

    /**
     * An expression reading an enum value from the JsonReader {@code _r} as its number: a name of
     * the enum, or any number, as protobuf's JSON parser accepts for an open enum.
     */
    static String parseJsonNumber(String enumType) {
        return String.format("(_r.peek() == '\"' ? %s.valueOf(_r.readString()).getValue() : _r.readInt())", enumType);
    }

    /** An expression reading an enum value from the TextFormatReader {@code _r} as its number: a name, or any number. */
    static String parseTextFormatNumber(String enumType) {
        return String.format("(_r.atNumber() ? _r.readInt() : %s.valueOf(_r.readIdentifier()).getValue())", enumType);
    }
}
