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

public class LightProtoRepeatedEnumField extends LightProtoRepeatedNumberField {

    public LightProtoRepeatedEnumField(ProtoFieldDescriptor field, int index) {
        super(field, index);
    }

    @Override
    public void parse(PrintWriter w) {
        w.format("%s _%s = %s;\n", field.getJavaType(), ccName, LightProtoNumberField.parseNumber(field));
        w.format("if (_%s != null) {\n", ccName);
        w.format("   %s(_%s);\n", Util.camelCase("add", singularName), ccName);
        w.format("}\n");
    }

    @Override
    public void hashCodeCode(PrintWriter w) {
        w.format("_h = 31 * _h + _%sCount;\n", pluralName);
        w.format("for (int _i = 0; _i < _%sCount; _i++) {\n", pluralName);
        w.format("    _h = 31 * _h + %s[_i].getValue();\n", pluralName);
        w.format("}\n");
    }

    @Override
    public void serializeTextFormat(PrintWriter w) {
        w.format("for (int i = 0; i < _%sCount; i++) {\n", pluralName);
        w.format("    LightProtoCodec.writeTextFormatIndent(_sb, _indent);\n");
        w.format("    _sb.append(\"%s: \").append(%s[i].name()).append('\\n');\n",
                field.getName(), pluralName);
        w.format("}\n");
    }

    @Override
    public void parseTextFormat(PrintWriter w) {
        w.format("                _r.consumeFieldSeparator();\n");
        w.format("                if (_r.atArrayStart()) {\n");
        w.format("                    _r.expect('[');\n");
        w.format("                    if (!_r.tryConsume(']')) {\n");
        w.format("                        do {\n");
        w.format("                            { %s _v = %s.valueOf(_r.readIdentifier());\n",
                field.getJavaType(), field.getJavaType());
        w.format("                            if (_v != null) { %s(_v); } }\n",
                Util.camelCase("add", singularName));
        w.format("                        } while (_r.tryConsume(','));\n");
        w.format("                        _r.expect(']');\n");
        w.format("                    }\n");
        w.format("                } else {\n");
        w.format("                    { %s _v = %s.valueOf(_r.readIdentifier());\n",
                field.getJavaType(), field.getJavaType());
        w.format("                    if (_v != null) { %s(_v); } }\n",
                Util.camelCase("add", singularName));
        w.format("                }\n");
    }

    public void parsePacked(PrintWriter w) {
        w.format("int _%s = LightProtoCodec.readVarInt(_buffer);\n", Util.camelCase(singularName, "size"));
        w.format("int _%s = _buffer.readerIndex() + _%s;\n", Util.camelCase(singularName, "endIdx"), Util.camelCase(singularName, "size"));
        w.format("while (_buffer.readerIndex() < _%s) {\n", Util.camelCase(singularName, "endIdx"));
        w.format("    %s _%sPacked = %s;\n", field.getJavaType(), ccName, LightProtoNumberField.parseNumber(field));
        w.format("    if (_%sPacked != null) {\n", ccName);
        w.format("        %s(_%sPacked);\n", Util.camelCase("add", singularName), ccName);
        w.format("    }\n");
        w.format("}\n");
    }
}
