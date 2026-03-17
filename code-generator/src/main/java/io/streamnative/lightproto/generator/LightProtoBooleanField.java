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
            w.format("%s = false;\n", ccName);
        }
    }

    @Override
    protected String nonDefaultCondition() {
        return ccName;
    }
}
