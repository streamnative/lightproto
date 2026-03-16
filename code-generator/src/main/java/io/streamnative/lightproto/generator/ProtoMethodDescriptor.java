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

public class ProtoMethodDescriptor {
    private final String name;
    private final String inputType;
    private final String outputType;
    private final boolean clientStreaming;
    private final boolean serverStreaming;
    private final String doc;

    public ProtoMethodDescriptor(String name, String inputType, String outputType,
                                 boolean clientStreaming, boolean serverStreaming) {
        this(name, inputType, outputType, clientStreaming, serverStreaming, null);
    }

    public ProtoMethodDescriptor(String name, String inputType, String outputType,
                                 boolean clientStreaming, boolean serverStreaming, String doc) {
        this.name = name;
        this.inputType = inputType;
        this.outputType = outputType;
        this.clientStreaming = clientStreaming;
        this.serverStreaming = serverStreaming;
        this.doc = doc;
    }

    public String getName() {
        return name;
    }

    public String getInputType() {
        return inputType;
    }

    public String getOutputType() {
        return outputType;
    }

    public boolean isClientStreaming() {
        return clientStreaming;
    }

    public boolean isServerStreaming() {
        return serverStreaming;
    }

    public String getDoc() {
        return doc;
    }
}
