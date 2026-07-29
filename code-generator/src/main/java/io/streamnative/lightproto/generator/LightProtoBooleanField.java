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

public class LightProtoBooleanField extends LightProtoNumberField {

    public LightProtoBooleanField(ProtoFieldDescriptor field, int index) {
        super(field, index);
    }

    @Override
    public void getter(PrintWriter w) {
        w.format("        /** Returns the value of the {@code %s} field. */\n", field.getName());
        w.format("        public %s %s() {\n", field.getJavaType(), Util.camelCase("is", ccName));
        w.format("            if (!(%s)) {\n", presenceCondition());
        w.format("                return %s;\n", defaultValueExpr());
        w.format("            }\n");
        w.format("            return %s;\n", ccName);
        w.format("        }\n");
    }

    @Override
    protected String defaultValueExpr() {
        if (field.isDefaultValueSet()) {
            return field.getDefaultValueAsString();
        }
        return "false";
    }

    @Override
    protected String cmpValueExpr(String qualifier) {
        if (field.hasImplicitPresence()) {
            return qualifier + Util.camelCase("is", ccName) + "()";
        }
        return qualifier + ccName;
    }

    @Override
    public void clear(PrintWriter w) {
        // No value reset needed: the getter is guarded by the presence condition.
    }

    @Override
    public void serializeJson(PrintWriter w) {
        w.format("LightProtoCodec.writeJsonAscii(_b, Boolean.toString(%s));\n", ccName);
    }

    @Override
    public void parseJson(PrintWriter w) {
        w.format("                %s(_r.readBool());\n", Util.camelCase("set", field.getName()));
    }

    @Override
    public void serializeTextFormat(PrintWriter w) {
        w.format("LightProtoCodec.writeTextFormatIndent(_sb, _indent);\n");
        w.format("_sb.append(\"%s: \").append(Boolean.toString(%s)).append('\\n');\n",
                field.getName(), ccName);
    }

    @Override
    public void parseTextFormat(PrintWriter w) {
        w.format("                _r.consumeFieldSeparator();\n");
        w.format("                %s(_r.readBool());\n", Util.camelCase("set", field.getName()));
    }

    @Override
    public void equalsCode(PrintWriter w) {
        w.format("if (%s != %s) return false;\n", cmpValueExpr(""), cmpValueExpr("_other."));
    }

    @Override
    public void hashCodeCode(PrintWriter w) {
        w.format("_h = 31 * _h + (%s ? 1231 : 1237);\n", cmpValueExpr(""));
    }

    @Override
    protected String nonDefaultCondition() {
        return ccName;
    }
}
