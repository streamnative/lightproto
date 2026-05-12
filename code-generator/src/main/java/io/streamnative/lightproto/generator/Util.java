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

import static com.google.common.base.CaseFormat.LOWER_CAMEL;
import static com.google.common.base.CaseFormat.LOWER_UNDERSCORE;

public class Util {

    public static String camelCase(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String s = parts[i];
            if (s == null || s.isEmpty()) {
                continue;
            }
            if (s.contains("_")) {
                s = LOWER_UNDERSCORE.to(LOWER_CAMEL, s);
            }

            if (i != 0) {
                sb.append(Character.toUpperCase(s.charAt(0)));
                sb.append(s.substring(1));
            } else {
                sb.append(s);
            }
        }

        return sb.toString();
    }

    public static String camelCaseFirstUpper(String... parts) {
        String s = camelCase(parts);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static String upperCase(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String s = LOWER_CAMEL.to(LOWER_UNDERSCORE, parts[i]);
            if (i != 0) {
                sb.append('_');
            }

            sb.append(s);
        }

        return sb.toString().toUpperCase();
    }

    // pluralize/singular rules vendored from JiBX NameUtilities
    // (Copyright (c) 2008-2010, Dennis M. Sosnoski, BSD 3-clause license).
    public static String plural(String name) {
        if (name.endsWith("List") || (name.endsWith("s") && !name.endsWith("ss"))) {
            return name;
        }
        if (name.endsWith("y") && !name.endsWith("ay") && !name.endsWith("ey") && !name.endsWith("iy")
                && !name.endsWith("oy") && !name.endsWith("uy")) {
            if (name.equalsIgnoreCase("any")) {
                return name;
            }
            return name.substring(0, name.length() - 1) + "ies";
        } else if (name.endsWith("ss")) {
            return name + "es";
        } else {
            return name + 's';
        }
    }

    public static String singular(String name) {
        if (name.endsWith("ies")) {
            return name.substring(0, name.length() - 3) + 'y';
        } else if (name.endsWith("sses")) {
            return name.substring(0, name.length() - 2);
        } else if (name.endsWith("s") && !name.endsWith("ss")) {
            return name.substring(0, name.length() - 1);
        } else if (name.endsWith("List")) {
            return name.substring(0, name.length() - 4);
        } else {
            return name;
        }
    }

    public static void writeJavadoc(PrintWriter w, String doc, String indent) {
        if (doc == null || doc.isEmpty()) {
            return;
        }
        w.format("%s/**\n", indent);
        for (String line : doc.split("\n")) {
            if (line.isEmpty()) {
                w.format("%s *\n", indent);
            } else {
                w.format("%s * %s\n", indent, line);
            }
        }
        w.format("%s */\n", indent);
    }
}
