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

import io.protostuff.parser.MessageField;

import java.io.PrintWriter;

public class LightProtoRepeatedMessageField extends LightProtoAbstractRepeated<MessageField> {

    protected final String pluralName;
    protected final String singularName;

    public LightProtoRepeatedMessageField(MessageField field, int index) {
        super(field, index);
        this.pluralName = Util.plural(ccName);
        this.singularName = Util.singular(ccName);
    }

    @Override
    public void declaration(PrintWriter w) {
        w.format("private %s[] %s = null;\n", field.getJavaType(), pluralName);
        w.format("private int _%sCount = 0;\n", pluralName);
    }

    @Override
    public void getter(PrintWriter w) {
        w.format("public int %s() {\n", Util.camelCase("get", pluralName, "count"));
        w.format("    return _%sCount;\n", pluralName);
        w.format("}\n");
        w.format("public %s %s(int idx) {\n", field.getJavaType(), Util.camelCase("get", singularName, "at"));
        w.format("    if (idx < 0 || idx >= _%sCount) {\n", pluralName);
        w.format("        throw new IndexOutOfBoundsException(\"Index \" + idx + \" is out of the list size (\" + _%sCount + \") for field '%s'\");\n", pluralName, field.getName());
        w.format("    }\n");
        w.format("    return %s[idx];\n", pluralName);
        w.format("}\n");

        w.format("public java.util.List<%s> %s() {\n", field.getJavaType(), Util.camelCase("get", pluralName, "list"));
        w.format("    if (_%sCount == 0) {\n", pluralName);
        w.format("        return java.util.Collections.emptyList();\n");
        w.format("    } else {\n");
        w.format("        return java.util.Arrays.asList(java.util.Arrays.copyOfRange(%s, 0, _%sCount));\n", pluralName, pluralName);
        w.format("    }\n");
        w.format("}\n");
    }

    @Override
    public void parse(PrintWriter w) {
        w.format("int _%sSize = LightProtoCodec.readVarInt(_buffer);\n", ccName);
        w.format("%s().parseFrom(_buffer, _%sSize);\n", Util.camelCase("add", singularName), ccName);
    }

    @Override
    public void serialize(PrintWriter w) {
        w.format("for (int i = 0; i < _%sCount; i++) {\n", pluralName);
        w.format("    %s _item = %s[i];\n", field.getJavaType(), pluralName);
        w.format("    %s;\n", writeTagExpr(tagName()));
        w.format("    _addr = LightProtoCodec.writeRawVarInt(_base, _addr, _item.getSerializedSize());\n");
        w.format("    _b.writerIndex((int)(_addr - _baseOffset));\n");
        w.format("    _item.writeTo(_b);\n");
        w.format("    _addr = _baseOffset + _b.writerIndex();\n");
        w.format("}\n");
    }

    @Override
    public void copy(PrintWriter w) {
        w.format("for (int i = 0; i < _other.%s(); i++) {\n", Util.camelCase("get", pluralName, "count"));
        w.format("    %s().copyFrom(_other.%s(i));\n", Util.camelCase("add", singularName), Util.camelCase("get", singularName, "at"));
        w.format("}\n");
    }

    @Override
    public void setter(PrintWriter w, String enclosingType) {
        w.format("public %s %s() {\n", field.getJavaType(), Util.camelCase("add", singularName));
        w.format("    if (%s == null) {\n", pluralName);
        w.format("        %s = new %s[4];\n", pluralName, field.getJavaType());
        w.format("    }\n");
        w.format("    if (%s.length == _%sCount) {\n", pluralName, pluralName);
        w.format("        %s = java.util.Arrays.copyOf(%s, _%sCount * 2);\n", pluralName, pluralName, pluralName);
        w.format("    }\n");
        w.format("    if (%s[_%sCount] == null) {\n", pluralName, pluralName);
        w.format("        %s[_%sCount] = new %s();\n", pluralName, pluralName, field.getJavaType());
        w.format("    }\n");
        w.format("    _cachedSize = -1;\n");
        w.format("    return %s[_%sCount++];\n", pluralName, pluralName);
        w.format("}\n");


        w.format("public %s %s(Iterable<%s> %s) {\n", enclosingType, Util.camelCase("addAll", pluralName), field.getJavaType(), pluralName);
        w.format("    for (%s _o : %s) {\n", field.getJavaType(), pluralName);
        w.format("        %s().copyFrom(_o);\n", Util.camelCase("add", singularName));
        w.format("    }\n");
        w.format("    return this;\n");
        w.format("}\n");


    }

    @Override
    public void serializedSize(PrintWriter w) {
        String tmpName = Util.camelCase("_msgSize", field.getName());

        w.format("for (int i = 0; i < _%sCount; i++) {\n", pluralName);
        w.format("     %s _item = %s[i];\n", field.getJavaType(), pluralName);
        w.format("     _size += %s_SIZE;\n", tagName());
        w.format("     int %s = _item.getSerializedSize();\n", tmpName);
        w.format("     _size += LightProtoCodec.computeVarIntSize(%s) + %s;\n", tmpName, tmpName);
        w.format("}\n");
    }

    @Override
    public void clear(PrintWriter w) {
        w.format("for (int i = 0; i < _%sCount; i++) {\n", pluralName);
        w.format("    %s[i].clear();\n", pluralName);
        w.format("}\n");
        w.format("_%sCount = 0;\n", pluralName);
    }

    @Override
    protected String typeTag() {
        return "LightProtoCodec.WIRETYPE_LENGTH_DELIMITED";
    }
}
