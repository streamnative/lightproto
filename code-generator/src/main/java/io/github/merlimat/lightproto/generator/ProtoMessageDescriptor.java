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
package io.github.merlimat.lightproto.generator;

import java.util.Collections;
import java.util.List;

public class ProtoMessageDescriptor {
    private final String name;
    private final List<ProtoFieldDescriptor> fields;
    private final List<ProtoMessageDescriptor> nestedMessages;
    private final List<ProtoEnumDescriptor> nestedEnums;
    private final List<ProtoOneofDescriptor> oneofs;

    public ProtoMessageDescriptor(String name,
                                  List<ProtoFieldDescriptor> fields,
                                  List<ProtoMessageDescriptor> nestedMessages,
                                  List<ProtoEnumDescriptor> nestedEnums) {
        this(name, fields, nestedMessages, nestedEnums, Collections.emptyList());
    }

    public ProtoMessageDescriptor(String name,
                                  List<ProtoFieldDescriptor> fields,
                                  List<ProtoMessageDescriptor> nestedMessages,
                                  List<ProtoEnumDescriptor> nestedEnums,
                                  List<ProtoOneofDescriptor> oneofs) {
        this.name = name;
        this.fields = fields;
        this.nestedMessages = nestedMessages;
        this.nestedEnums = nestedEnums;
        this.oneofs = oneofs;
    }

    public String getName() {
        return name;
    }

    public List<ProtoFieldDescriptor> getFields() {
        return fields;
    }

    public int getFieldCount() {
        return fields.size();
    }

    public List<ProtoMessageDescriptor> getNestedMessages() {
        return nestedMessages;
    }

    public List<ProtoEnumDescriptor> getNestedEnumGroups() {
        return nestedEnums;
    }

    public List<ProtoOneofDescriptor> getOneofs() {
        return oneofs;
    }
}
