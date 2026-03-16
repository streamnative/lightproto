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

import java.util.List;

public class ProtoEnumDescriptor {
    private final String name;
    private final List<Value> values;
    private final String doc;

    public ProtoEnumDescriptor(String name, List<Value> values) {
        this(name, values, null);
    }

    public ProtoEnumDescriptor(String name, List<Value> values, String doc) {
        this.name = name;
        this.values = values;
        this.doc = doc;
    }

    public String getName() {
        return name;
    }

    public List<Value> getSortedValues() {
        return values;
    }

    public String getDoc() {
        return doc;
    }

    public static class Value {
        private final String name;
        private final int number;
        private final String doc;

        public Value(String name, int number) {
            this(name, number, null);
        }

        public Value(String name, int number, String doc) {
            this.name = name;
            this.number = number;
            this.doc = doc;
        }

        public String getName() {
            return name;
        }

        public int getNumber() {
            return number;
        }

        public String getDoc() {
            return doc;
        }
    }
}
