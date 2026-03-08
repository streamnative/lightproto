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

import java.util.List;

public class ProtoFileDescriptor {
    private final String javaPackageName;
    private final String syntax;
    private final List<ProtoMessageDescriptor> messages;
    private final List<ProtoEnumDescriptor> enums;

    public ProtoFileDescriptor(String javaPackageName, String syntax,
                               List<ProtoMessageDescriptor> messages,
                               List<ProtoEnumDescriptor> enums) {
        this.javaPackageName = javaPackageName;
        this.syntax = syntax;
        this.messages = messages;
        this.enums = enums;
    }

    public String getJavaPackageName() {
        return javaPackageName;
    }

    public String getSyntax() {
        return syntax;
    }

    public List<ProtoMessageDescriptor> getMessages() {
        return messages;
    }

    public List<ProtoEnumDescriptor> getEnumGroups() {
        return enums;
    }
}
