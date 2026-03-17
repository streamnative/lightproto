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
    protected String nonDefaultCondition() {
        return ccName + ".getValue() != 0";
    }
}
