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

public class LightProtoMessageField extends LightProtoField {

    public LightProtoMessageField(ProtoFieldDescriptor field, int index) {
        super(field, index);
    }

    @Override
    public void declaration(PrintWriter w) {
        w.format("private %s %s;\n", field.getJavaType(), ccName);
    }

    @Override
    public void setter(PrintWriter w, String enclosingType) {
        w.format("/** Returns the {@code %s} sub-message, creating it if not already set. */\n", field.getName());
        w.format("public %s %s() {\n", field.getJavaType(), Util.camelCase("set", ccName));
        w.format("    if (%s == null) {\n", ccName);
        w.format("        %s = new %s();\n", ccName, field.getJavaType());
        w.format("    }\n");
        writeSetPresence(w);
        w.format("    _cachedSize = -1;\n");
        w.format("    return %s;\n", ccName);
        w.format("}\n");
    }

    @Override
    public void copy(PrintWriter w) {
        w.format("%s().copyFrom(_other.%s);\n", Util.camelCase("set", ccName), ccName);
    }

    public void getter(PrintWriter w) {
        w.format("/** Returns the value of the {@code %s} field. */\n", field.getName());
        w.format("public %s %s() {\n", field.getJavaType(), Util.camelCase("get", field.getName()));
        if (field.isRequired()) {
            w.format("    if (!%s()) {\n", Util.camelCase("has", ccName));
            w.format("        throw new IllegalStateException(\"Field '%s' is not set\");\n", field.getName());
            w.format("    }\n");
        } else {
            w.format("    if (%s == null) {\n", ccName);
            w.format("        %s = new %s();\n", ccName, field.getJavaType());
            w.format("    }\n");
        }
        w.format("    return %s;\n", ccName);
        w.format("}\n");
    }

    @Override
    public void parse(PrintWriter w) {
        w.format("int %sSize = LightProtoCodec.readVarInt(_buffer);\n", ccName);
        w.format("%s().parseFrom(_buffer, %sSize);\n", Util.camelCase("set", ccName), ccName);
    }

    @Override
    public void serializedSize(PrintWriter w) {
        String tmpName = Util.camelCase("_msgSize", ccName);
        w.format("_size += LightProtoCodec.computeVarIntSize(%s);\n", tagName());
        w.format("int %s = %s.getSerializedSize();\n", tmpName, ccName);
        w.format("_size += LightProtoCodec.computeVarIntSize(%s) + %s;\n", tmpName, tmpName);
    }

    @Override
    public void serialize(PrintWriter w) {
        w.format("%s;\n", writeTagExpr(tagName()));
        w.format("_addr = LightProtoCodec.writeRawVarInt(_base, _addr, %s.getSerializedSize());\n", ccName);
        w.format("_b.writerIndex((int)(_addr - _baseOffset));\n");
        w.format("%s.writeTo(_b);\n", ccName);
        w.format("_addr = _baseOffset + _b.writerIndex();\n");
    }

    @Override
    public void clear(PrintWriter w) {
        w.format("if (%s()){\n", Util.camelCase("has", ccName));
        w.format("    %s.clear();\n", ccName);
        w.format("}\n");
    }

    @Override
    public void materialize(PrintWriter w) {
        w.format("if (%s()) {\n", Util.camelCase("has", ccName));
        w.format("    %s.materialize();\n", ccName);
        w.format("}\n");
    }

    @Override
    protected String typeTag() {
        return "LightProtoCodec.WIRETYPE_LENGTH_DELIMITED";
    }
}
