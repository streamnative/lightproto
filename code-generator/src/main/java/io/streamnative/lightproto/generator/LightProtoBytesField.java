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

public class LightProtoBytesField extends LightProtoField {

    public LightProtoBytesField(ProtoFieldDescriptor field, int index) {
        super(field, index);
    }

    @Override
    public void declaration(PrintWriter w) {
        w.format("private io.netty.buffer.ByteBuf %s = null;\n", ccName);
        w.format("private int _%sIdx = -1;\n", ccName);
        w.format("private int _%sLen = -1;\n", ccName);
    }

    @Override
    public void parse(PrintWriter w) {
        w.format("_%sLen = LightProtoCodec.readVarInt(_buffer);\n", ccName);
        w.format("_%sIdx = _buffer.readerIndex();\n", ccName);
        w.format("_buffer.skipBytes(_%sLen);\n", ccName);
    }

    @Override
    public void copy(PrintWriter w) {
        w.format("%s(_other.%s());\n", Util.camelCase("set", ccName), Util.camelCase("get", ccName));
    }

    @Override
    public void setter(PrintWriter w, String enclosingType) {
        w.format("/** Set the {@code %s} field from a byte array. */\n", field.getName());
        w.format("public %s %s(byte[] %s) {\n", enclosingType, Util.camelCase("set", ccName), ccName);
        w.format("    %s(io.netty.buffer.Unpooled.wrappedBuffer(%s));\n", Util.camelCase("set", ccName), ccName);
        w.format("    return this;\n");
        w.format("}\n");

        w.format("/** Set the {@code %s} field from a ByteBuf. */\n", field.getName());
        w.format("public %s %s(io.netty.buffer.ByteBuf %s) {\n", enclosingType, Util.camelCase("set", ccName), ccName);
        w.format("    this.%s = %s;\n", ccName, ccName);
        writeSetPresence(w);
        w.format("    _%sIdx = -1;\n", ccName);
        w.format("    _%sLen = %s.readableBytes();\n", ccName, ccName);
        w.format("    _cachedSize = -1;\n");
        w.format("    return this;\n");
        w.format("}\n");
    }

    @Override
    public void getter(PrintWriter w) {
        w.format("/** Returns the size in bytes of the {@code %s} field. */\n", field.getName());
        w.format("public int %s() {\n", Util.camelCase("get", ccName, "size"));
        if (field.isRequired()) {
            w.format("    if (!%s()) {\n", Util.camelCase("has", ccName));
            w.format("        throw new IllegalStateException(\"Field '%s' is not set\");\n", field.getName());
            w.format("    }\n");
        } else {
            w.format("    if (_%sLen < 0) { return 0; }\n", ccName);
        }
        w.format("    return _%sLen;\n", ccName);
        w.format("}\n");

        w.format("/** Returns the {@code %s} field as a byte array. */\n", field.getName());
        w.format("public byte[] %s() {\n", Util.camelCase("get", ccName));
        if (!field.isRequired()) {
            w.format("    if (_%sLen < 0) { return new byte[0]; }\n", ccName);
        }
        w.format("    io.netty.buffer.ByteBuf _b = %s();\n", Util.camelCase("get", ccName, "slice"));
        w.format("    byte[] res = new byte[_b.readableBytes()];\n");
        w.format("    _b.getBytes(0, res);\n");
        w.format("    return res;\n");
        w.format("}\n");

        w.format("/** Returns the {@code %s} field as a ByteBuf slice. */\n", field.getName());
        w.format("public io.netty.buffer.ByteBuf %s() {\n", Util.camelCase("get", ccName, "slice"));
        if (field.isRequired()) {
            w.format("    if (!%s()) {\n", Util.camelCase("has", ccName));
            w.format("        throw new IllegalStateException(\"Field '%s' is not set\");\n", field.getName());
            w.format("    }\n");
        } else {
            w.format("    if (_%sLen < 0) { return io.netty.buffer.Unpooled.EMPTY_BUFFER; }\n", ccName);
        }
        w.format("    if (%s == null) {\n", ccName);
        w.format("        return _parsedBuffer.slice(_%sIdx, _%sLen);\n", ccName, ccName);
        w.format("    } else {\n");
        w.format("        return %s.slice(0, _%sLen);\n", ccName, ccName);
        w.format("    }\n");
        w.format("}\n");
    }

    @Override
    protected String nonDefaultCondition() {
        return "_" + ccName + "Len > 0";
    }

    @Override
    public void clear(PrintWriter w) {
        w.format("%s = null;\n", ccName);
        w.format("_%sIdx = -1;\n", ccName);
        w.format("_%sLen = -1;\n", ccName);
    }

    @Override
    public void serializedSize(PrintWriter w) {
        w.format("_size += %s_SIZE;\n", tagName());
        w.format("_size += LightProtoCodec.computeVarIntSize(_%sLen) + _%sLen;\n", ccName, ccName);
    }

    @Override
    public void serialize(PrintWriter w) {
        w.format("%s;\n", writeTagExpr(tagName()));
        w.format("_addr = LightProtoCodec.writeRawVarInt(_base, _addr, _%sLen);\n", ccName);
        w.format("_b.writerIndex((int)(_addr - _baseOffset));\n");
        w.format("if (_%sIdx == -1) {\n", ccName);
        w.format("    _b.writeBytes(%s);\n", ccName);
        w.format("} else {\n");
        w.format("    _parsedBuffer.getBytes(_%sIdx, _b, _%sLen);\n", ccName, ccName);
        w.format("}\n");
        w.format("_addr = _baseOffset + _b.writerIndex();\n");
    }


    @Override
    public void materialize(PrintWriter w) {
        w.format("if (_%sIdx >= 0) {\n", ccName);
        w.format("    byte[] _tmp = new byte[_%sLen];\n", ccName);
        w.format("    _parsedBuffer.getBytes(_%sIdx, _tmp);\n", ccName);
        w.format("    %s = io.netty.buffer.Unpooled.wrappedBuffer(_tmp);\n", ccName);
        w.format("    _%sIdx = -1;\n", ccName);
        w.format("}\n");
    }

    @Override
    protected String typeTag() {
        return "LightProtoCodec.WIRETYPE_LENGTH_DELIMITED";
    }
}
