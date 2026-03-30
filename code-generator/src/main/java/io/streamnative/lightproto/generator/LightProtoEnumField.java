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

public class LightProtoEnumField extends LightProtoNumberField {

    public LightProtoEnumField(ProtoFieldDescriptor field, int index) {
        super(field, index);
    }

    @Override
    public void declaration(PrintWriter w) {
        if (field.isDefaultValueSet()) {
            super.declaration(w);
        } else {
            w.format("private %s %s = %s.valueOf(0);\n", field.getJavaType(), ccName, field.getJavaType());
        }
    }

    @Override
    public void getter(PrintWriter w) {
        w.format("        /** Returns the value of the {@code %s} field. */\n", field.getName());
        w.format("        public %s %s() {\n", field.getJavaType(), Util.camelCase("get", field.getName()));
        if (field.isRequired()) {
            w.format("            if (!%s()) {\n", Util.camelCase("has", ccName));
            w.format("                throw new IllegalStateException(\"Field '%s' is not set\");\n", field.getName());
            w.format("            }\n");
        }
        w.format("            return %s;\n", ccName);
        w.format("        }\n");
    }

    @Override
    public void clear(PrintWriter w) {
        if (field.isDefaultValueSet()) {
            w.format("%s = %s;\n", ccName, field.getDefaultValueAsString());
        } else {
            w.format("%s = %s.valueOf(0);\n", ccName, field.getJavaType());
        }
    }

    @Override
    public void parse(PrintWriter w) {
        w.format("%s _%s = %s;\n", field.getJavaType(), ccName, parseNumber(field));
        w.format("if (_%s != null) {\n", ccName);
        writeSetPresence(w);
        w.format("    %s = _%s;\n", ccName, ccName);
        w.format("}\n");
    }

    @Override
    public void serializeJson(PrintWriter w) {
        w.format("_b.writeByte('\"');\n");
        w.format("LightProtoCodec.writeJsonAscii(_b, %s.name());\n", ccName);
        w.format("_b.writeByte('\"');\n");
    }

    @Override
    public void parseJson(PrintWriter w) {
        w.format("                { %s _v = %s.valueOf(_r.readString());\n", field.getJavaType(), field.getJavaType());
        w.format("                if (_v != null) { %s(_v); } }\n", Util.camelCase("set", field.getName()));
    }

    @Override
    public void equalsCode(PrintWriter w) {
        w.format("if (%s != _other.%s) return false;\n", ccName, ccName);
    }

    @Override
    public void hashCodeCode(PrintWriter w) {
        w.format("_h = 31 * _h + %s.getValue();\n", ccName);
    }

    @Override
    protected String nonDefaultCondition() {
        return ccName + ".getValue() != 0";
    }
}
