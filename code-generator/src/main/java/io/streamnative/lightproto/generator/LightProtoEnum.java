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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public class LightProtoEnum {
    private final ProtoEnumDescriptor eg;

    public LightProtoEnum(ProtoEnumDescriptor eg) {
        this.eg = eg;
    }

    public String getName() {
        return eg.getName();
    }

    public void generate(PrintWriter w) {
        Util.writeJavadoc(w, eg.getDoc(), "    ");
        w.format("    public enum %s {\n", eg.getName());
        eg.getSortedValues().forEach(v -> {
                Util.writeJavadoc(w, v.getDoc(), "        ");
                w.format("        %s(%d),\n", v.getName(), v.getNumber());
        });
        if (eg.isOpen()) {
            // As in protobuf-java: the -1 is never used, since getValue() rejects the constant
            w.println("        /**");
            w.println("         * What the getter of an open enum field returns for a number this enum doesn't");
            w.println("         * define, which the field's get...Value() accessor returns. It has no number of");
            w.println("         * its own: getValue() throws, so setters reject it.");
            w.println("         */");
            w.println("        UNRECOGNIZED(-1),");
        }
        w.println("        ;");
        w.println("        private final int value;");
        w.format("        private %s(int value) {\n", eg.getName());
        w.println("            this.value = value;");
        w.println("        }");
        w.println("        public int getValue() {");
        if (eg.isOpen()) {
            w.println("            if (this == UNRECOGNIZED) {");
            w.println("                throw new IllegalArgumentException(\"Can't get the number of an unknown enum value.\");");
            w.println("            }");
        }
        w.println("            return value;");
        w.println("        }");

        List<ProtoEnumDescriptor.Value> values = eg.getSortedValues();
        int first = values.get(0).getNumber();
        int last = values.get(values.size() - 1).getNumber();
        if (eg.isOpen() && (long) last - first + 1 <= 2L * values.size()) {
            // The getters of open enum fields map the number they hold on every call: an array
            // lookup does it in a few instructions, where a switch is a chain of compares. Used
            // when at least half of the numbers between the first and the last are values.
            Map<Integer, String> names = new HashMap<>();
            values.forEach(v -> names.putIfAbsent(v.getNumber(), v.getName()));
            StringJoiner slots = new StringJoiner(", ");
            for (long n = first; n <= last; n++) {
                slots.add(names.getOrDefault((int) n, "null"));
            }
            w.format("        private static final %s[] _BY_NUMBER = {%s};\n", eg.getName(), slots);
            w.format("        public static %s valueOf(int n) {\n", eg.getName());
            String i = "n";
            if (first != 0) {
                w.format("            int i = n - (%d);\n", first);
                i = "i";
            }
            w.format("            return Integer.compareUnsigned(%s, _BY_NUMBER.length) < 0 ? _BY_NUMBER[%s] : null;\n", i, i);
            w.println("        }");
        } else {
            w.format("        public static %s valueOf(int n) {\n", eg.getName());
            w.format("            switch (n) {\n");
            values.forEach(v ->
                    w.format("                case %d: return %s;\n", v.getNumber(), v.getName()));
            w.println("                default: return null;\n");
            w.println("            }");
            w.println("        }");
        }
        eg.getSortedValues().forEach(v ->
                w.format("     public static final int %s_VALUE = %d;\n", v.getName(), v.getNumber()));
        w.println("    }");
        w.println();
    }
}
