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

public class LightProtoMapField extends LightProtoAbstractRepeated {

    private static final int MAP_INDEX_THRESHOLD = 10;

    private final ProtoFieldDescriptor keyField;
    private final ProtoFieldDescriptor valueField;

    public LightProtoMapField(ProtoFieldDescriptor field, int index) {
        super(field, index);
        this.keyField = field.getMapKeyField();
        this.valueField = field.getMapValueField();
    }

    private boolean isStringKey() {
        return keyField.isStringField();
    }

    private boolean isStringValue() {
        return valueField.isStringField();
    }

    private boolean isBytesValue() {
        return valueField.isBytesField();
    }

    private boolean isMessageValue() {
        return valueField.isMessageField();
    }

    private boolean isEnumValue() {
        return valueField.isEnumField();
    }

    private String keyArrayType() {
        if (isStringKey()) {
            return "LightProtoCodec.StringHolder[]";
        }
        return keyField.getJavaType() + "[]";
    }

    private String valueArrayType() {
        if (isStringValue()) {
            return "LightProtoCodec.StringHolder[]";
        } else if (isBytesValue()) {
            return "LightProtoCodec.BytesHolder[]";
        } else if (isMessageValue()) {
            return valueField.getJavaType() + "[]";
        }
        return valueField.getJavaType() + "[]";
    }

    private static String boxedType(String javaType) {
        switch (javaType) {
            case "int": return "Integer";
            case "long": return "Long";
            case "boolean": return "Boolean";
            case "float": return "Float";
            case "double": return "Double";
            default: return javaType;
        }
    }

    private String keyBoxed() {
        return boxedType(keyField.getJavaType());
    }

    private String valueBoxed() {
        if (isBytesValue()) {
            return "byte[]";
        }
        return boxedType(valueField.getJavaType());
    }

    private String keyTagConstant() {
        return tagName() + "_KEY_TAG";
    }

    private String valueTagConstant() {
        return tagName() + "_VALUE_TAG";
    }

    @Override
    public void declaration(PrintWriter w) {
        w.format("private %s _%sKeys = null;\n", keyArrayType(), ccName);
        w.format("private %s _%sValues = null;\n", valueArrayType(), ccName);
        w.format("private int _%sCount = 0;\n", ccName);
        w.format("private java.util.HashMap<%s, Integer> _%sIndex = null;\n", keyBoxed(), ccName);
    }

    @Override
    public void tags(PrintWriter w) {
        super.tags(w);
        String keyWireType = isStringKey() ? "LightProtoCodec.WIRETYPE_LENGTH_DELIMITED"
                : LightProtoNumberField.typeTag(keyField);
        String valueWireType;
        if (isStringValue() || isBytesValue() || isMessageValue()) {
            valueWireType = "LightProtoCodec.WIRETYPE_LENGTH_DELIMITED";
        } else {
            valueWireType = LightProtoNumberField.typeTag(valueField);
        }
        w.format("        private static final int %s = (1 << LightProtoCodec.TAG_TYPE_BITS) | %s;\n",
                keyTagConstant(), keyWireType);
        w.format("        private static final int %s = (2 << LightProtoCodec.TAG_TYPE_BITS) | %s;\n",
                valueTagConstant(), valueWireType);
    }

    @Override
    public void getter(PrintWriter w) {
        // getCount()
        w.format("/** Returns the number of entries in the {@code %s} map. */\n", field.getName());
        w.format("public int %s() {\n", Util.camelCase("get", ccName, "count"));
        w.format("    return _%sCount;\n", ccName);
        w.format("}\n");

        // get(key) - returns value, throws if not found
        String valueReturnType = isBytesValue() ? "byte[]" : valueField.getJavaType();
        w.format("/** Returns the value for the given key in the {@code %s} map. */\n", field.getName());
        w.format("public %s %s(%s key) {\n", valueReturnType, Util.camelCase("get", ccName), keyField.getJavaType());
        w.format("    int _idx = _find%sKeyIndex(key);\n", Util.camelCaseFirstUpper(ccName));
        w.format("    if (_idx < 0) {\n");
        w.format("        throw new IllegalArgumentException(\"Key not found in map field '%s'\");\n", field.getName());
        w.format("    }\n");
        generateReturnValueAt(w, "_idx");
        w.format("}\n");

        // forEach(BiConsumer)
        w.format("/** Iterates over all entries in the {@code %s} map. */\n", field.getName());
        w.format("public void %s(java.util.function.BiConsumer<%s, %s> consumer) {\n",
                Util.camelCase("forEach", ccName), keyBoxed(), valueBoxed());
        w.format("    for (int _i = 0; _i < _%sCount; _i++) {\n", ccName);
        generateResolveKeyExpr(w, "_i", "_k");
        generateResolveValueExpr(w, "_i", "_v");
        w.format("        consumer.accept(_k, _v);\n");
        w.format("    }\n");
        w.format("}\n");

        // Private: _findKeyIndex
        generateFindKeyIndex(w);

        // Private: _ensureCapacity
        generateEnsureCapacity(w);
    }

    private void generateReturnValueAt(PrintWriter w, String idxVar) {
        if (isStringValue()) {
            w.format("    LightProtoCodec.StringHolder _sh = _%sValues[%s];\n", ccName, idxVar);
            w.format("    if (_sh.s == null) {\n");
            w.format("        _sh.s = LightProtoCodec.readString(_parsedBuffer, _sh.idx, _sh.len);\n");
            w.format("    }\n");
            w.format("    return _sh.s;\n");
        } else if (isBytesValue()) {
            w.format("    LightProtoCodec.BytesHolder _bh = _%sValues[%s];\n", ccName, idxVar);
            w.format("    if (_bh.idx == -1) {\n");
            w.format("        byte[] _res = new byte[_bh.len];\n");
            w.format("        _bh.b.getBytes(0, _res);\n");
            w.format("        return _res;\n");
            w.format("    } else {\n");
            w.format("        byte[] _res = new byte[_bh.len];\n");
            w.format("        _parsedBuffer.getBytes(_bh.idx, _res);\n");
            w.format("        return _res;\n");
            w.format("    }\n");
        } else {
            w.format("    return _%sValues[%s];\n", ccName, idxVar);
        }
    }

    private void generateResolveKeyExpr(PrintWriter w, String idxExpr, String varName) {
        if (isStringKey()) {
            w.format("        LightProtoCodec.StringHolder _ksh = _%sKeys[%s];\n", ccName, idxExpr);
            w.format("        if (_ksh.s == null) {\n");
            w.format("            _ksh.s = LightProtoCodec.readString(_parsedBuffer, _ksh.idx, _ksh.len);\n");
            w.format("        }\n");
            w.format("        %s %s = _ksh.s;\n", keyField.getJavaType(), varName);
        } else {
            w.format("        %s %s = _%sKeys[%s];\n", keyField.getJavaType(), varName, ccName, idxExpr);
        }
    }

    private void generateResolveValueExpr(PrintWriter w, String idxExpr, String varName) {
        if (isStringValue()) {
            w.format("        LightProtoCodec.StringHolder _vsh = _%sValues[%s];\n", ccName, idxExpr);
            w.format("        if (_vsh.s == null) {\n");
            w.format("            _vsh.s = LightProtoCodec.readString(_parsedBuffer, _vsh.idx, _vsh.len);\n");
            w.format("        }\n");
            w.format("        %s %s = _vsh.s;\n", "String", varName);
        } else if (isBytesValue()) {
            w.format("        LightProtoCodec.BytesHolder _vbh = _%sValues[%s];\n", ccName, idxExpr);
            w.format("        byte[] %s;\n", varName);
            w.format("        if (_vbh.idx == -1) {\n");
            w.format("            %s = new byte[_vbh.len];\n", varName);
            w.format("            _vbh.b.getBytes(0, %s);\n", varName);
            w.format("        } else {\n");
            w.format("            %s = new byte[_vbh.len];\n", varName);
            w.format("            _parsedBuffer.getBytes(_vbh.idx, %s);\n", varName);
            w.format("        }\n");
        } else {
            w.format("        %s %s = _%sValues[%s];\n", valueBoxed(), varName, ccName, idxExpr);
        }
    }

    private void generateFindKeyIndex(PrintWriter w) {
        w.format("private int _find%sKeyIndex(%s key) {\n", Util.camelCaseFirstUpper(ccName), keyField.getJavaType());
        w.format("    if (_%sCount == 0) return -1;\n", ccName);
        w.format("    if (_%sCount > %d) {\n", ccName, MAP_INDEX_THRESHOLD);
        w.format("        if (_%sIndex == null) {\n", ccName);
        w.format("            _%sIndex = new java.util.HashMap<>();\n", ccName);
        w.format("            for (int _i = 0; _i < _%sCount; _i++) {\n", ccName);
        if (isStringKey()) {
            w.format("                LightProtoCodec.StringHolder _sh = _%sKeys[_i];\n", ccName);
            w.format("                if (_sh.s == null) {\n");
            w.format("                    _sh.s = LightProtoCodec.readString(_parsedBuffer, _sh.idx, _sh.len);\n");
            w.format("                }\n");
            w.format("                _%sIndex.put(_sh.s, _i);\n", ccName);
        } else {
            w.format("                _%sIndex.put(_%sKeys[_i], _i);\n", ccName, ccName);
        }
        w.format("            }\n");
        w.format("        }\n");
        w.format("        Integer _idx = _%sIndex.get(key);\n", ccName);
        w.format("        return _idx != null ? _idx : -1;\n");
        w.format("    }\n");

        // Linear scan for small maps
        w.format("    for (int _i = 0; _i < _%sCount; _i++) {\n", ccName);
        if (isStringKey()) {
            w.format("        LightProtoCodec.StringHolder _sh = _%sKeys[_i];\n", ccName);
            w.format("        if (_sh.s == null) {\n");
            w.format("            _sh.s = LightProtoCodec.readString(_parsedBuffer, _sh.idx, _sh.len);\n");
            w.format("        }\n");
            w.format("        if (key.equals(_sh.s)) return _i;\n");
        } else if (keyField.getJavaType().equals("boolean")) {
            w.format("        if (_%sKeys[_i] == key) return _i;\n", ccName);
        } else {
            w.format("        if (_%sKeys[_i] == key) return _i;\n", ccName);
        }
        w.format("    }\n");
        w.format("    return -1;\n");
        w.format("}\n");
    }

    private void generateEnsureCapacity(PrintWriter w) {
        w.format("private void _ensure%sCapacity() {\n", Util.camelCaseFirstUpper(ccName));
        w.format("    if (_%sKeys == null) {\n", ccName);
        w.format("        _%sKeys = new %s;\n", ccName, keyArrayAlloc("4"));
        w.format("        _%sValues = new %s;\n", ccName, valueArrayAlloc("4"));
        w.format("    }\n");
        w.format("    if (_%sKeys.length == _%sCount) {\n", ccName, ccName);
        w.format("        _%sKeys = java.util.Arrays.copyOf(_%sKeys, _%sCount * 2);\n", ccName, ccName, ccName);
        w.format("        _%sValues = java.util.Arrays.copyOf(_%sValues, _%sCount * 2);\n", ccName, ccName, ccName);
        w.format("    }\n");
        w.format("}\n");
    }

    private String keyArrayAlloc(String size) {
        if (isStringKey()) {
            return String.format("LightProtoCodec.StringHolder[%s]", size);
        }
        return String.format("%s[%s]", keyField.getJavaType(), size);
    }

    private String valueArrayAlloc(String size) {
        if (isStringValue()) {
            return String.format("LightProtoCodec.StringHolder[%s]", size);
        } else if (isBytesValue()) {
            return String.format("LightProtoCodec.BytesHolder[%s]", size);
        } else if (isMessageValue()) {
            return String.format("%s[%s]", valueField.getJavaType(), size);
        }
        return String.format("%s[%s]", valueField.getJavaType(), size);
    }

    @Override
    public void setter(PrintWriter w, String enclosingType) {
        if (isMessageValue()) {
            // put(key) -> returns message for population
            w.format("/** Puts an entry in the {@code %s} map, returning the value message for population. */\n", field.getName());
            w.format("public %s %s(%s key) {\n",
                    valueField.getJavaType(), Util.camelCase("put", ccName), keyField.getJavaType());
            w.format("    int _idx = _find%sKeyIndex(key);\n", Util.camelCaseFirstUpper(ccName));
            w.format("    if (_idx >= 0) {\n");
            w.format("        _cachedSize = -1;\n");
            w.format("        return _%sValues[_idx];\n", ccName);
            w.format("    }\n");
            w.format("    _ensure%sCapacity();\n", Util.camelCaseFirstUpper(ccName));
            generateStoreKey(w, "key", ccName + "Count");
            w.format("    if (_%sValues[_%sCount] == null) {\n", ccName, ccName);
            w.format("        _%sValues[_%sCount] = new %s();\n", ccName, ccName, valueField.getJavaType());
            w.format("    } else {\n");
            w.format("        _%sValues[_%sCount].clear();\n", ccName, ccName);
            w.format("    }\n");
            w.format("    if (_%sIndex != null) _%sIndex.put(key, _%sCount);\n", ccName, ccName, ccName);
            w.format("    _cachedSize = -1;\n");
            w.format("    return _%sValues[_%sCount++];\n", ccName, ccName);
            w.format("}\n");
        } else {
            // put(key, value) -> void
            String valueParamType = isBytesValue() ? "byte[]" : valueField.getJavaType();
            w.format("/** Puts an entry in the {@code %s} map. */\n", field.getName());
            w.format("public void %s(%s key, %s value) {\n",
                    Util.camelCase("put", ccName), keyField.getJavaType(), valueParamType);
            w.format("    int _idx = _find%sKeyIndex(key);\n", Util.camelCaseFirstUpper(ccName));
            w.format("    if (_idx >= 0) {\n");
            generateStoreValueAtIdx(w, "value", "_idx");
            w.format("        _cachedSize = -1;\n");
            w.format("        return;\n");
            w.format("    }\n");
            w.format("    _ensure%sCapacity();\n", Util.camelCaseFirstUpper(ccName));
            generateStoreKey(w, "key", ccName + "Count");
            generateStoreValueAtIdx(w, "value", "_" + ccName + "Count");
            w.format("    if (_%sIndex != null) _%sIndex.put(key, _%sCount);\n", ccName, ccName, ccName);
            w.format("    _%sCount++;\n", ccName);
            w.format("    _cachedSize = -1;\n");
            w.format("}\n");
        }
    }

    private void generateStoreKey(PrintWriter w, String keyExpr, String idxExpr) {
        if (isStringKey()) {
            w.format("    LightProtoCodec.StringHolder _ksh = _%sKeys[_%s];\n", ccName, idxExpr);
            w.format("    if (_ksh == null) {\n");
            w.format("        _ksh = new LightProtoCodec.StringHolder();\n");
            w.format("        _%sKeys[_%s] = _ksh;\n", ccName, idxExpr);
            w.format("    }\n");
            w.format("    _ksh.s = %s;\n", keyExpr);
            w.format("    _ksh.idx = -1;\n");
            w.format("    _ksh.len = LightProtoCodec.computeStringUTF8Size(%s);\n", keyExpr);
        } else {
            w.format("    _%sKeys[_%s] = %s;\n", ccName, idxExpr, keyExpr);
        }
    }

    private void generateStoreValueAtIdx(PrintWriter w, String valueExpr, String idxExpr) {
        if (isStringValue()) {
            w.format("        LightProtoCodec.StringHolder _vsh = _%sValues[%s];\n", ccName, idxExpr);
            w.format("        if (_vsh == null) {\n");
            w.format("            _vsh = new LightProtoCodec.StringHolder();\n");
            w.format("            _%sValues[%s] = _vsh;\n", ccName, idxExpr);
            w.format("        }\n");
            w.format("        _vsh.s = %s;\n", valueExpr);
            w.format("        _vsh.idx = -1;\n");
            w.format("        _vsh.len = LightProtoCodec.computeStringUTF8Size(%s);\n", valueExpr);
        } else if (isBytesValue()) {
            w.format("        LightProtoCodec.BytesHolder _vbh = _%sValues[%s];\n", ccName, idxExpr);
            w.format("        if (_vbh == null) {\n");
            w.format("            _vbh = new LightProtoCodec.BytesHolder();\n");
            w.format("            _%sValues[%s] = _vbh;\n", ccName, idxExpr);
            w.format("        }\n");
            w.format("        _vbh.b = io.netty.buffer.Unpooled.wrappedBuffer(%s);\n", valueExpr);
            w.format("        _vbh.idx = -1;\n");
            w.format("        _vbh.len = %s.length;\n", valueExpr);
        } else {
            w.format("        _%sValues[%s] = %s;\n", ccName, idxExpr, valueExpr);
        }
    }

    @Override
    public void parse(PrintWriter w) {
        // Read entry size and end position
        w.format("int _%sEntrySize = LightProtoCodec.readVarInt(_buffer);\n", ccName);
        w.format("int _%sEntryEnd = _buffer.readerIndex() + _%sEntrySize;\n", ccName, ccName);

        // Declare temp variables for key and value
        generateKeyTempDecl(w);
        generateValueTempDecl(w);

        // Ensure capacity before parsing (message values parse directly into the array)
        w.format("_ensure%sCapacity();\n", Util.camelCaseFirstUpper(ccName));

        // Parse entry fields
        w.format("while (_buffer.readerIndex() < _%sEntryEnd) {\n", ccName);
        w.format("    int _%sEntryTag = LightProtoCodec.readVarInt(_buffer);\n", ccName);
        w.format("    switch (_%sEntryTag) {\n", ccName);
        w.format("        case %s:\n", keyTagConstant());
        generateKeyTempParse(w);
        w.format("            break;\n");
        w.format("        case %s:\n", valueTagConstant());
        generateValueTempParse(w);
        w.format("            break;\n");
        w.format("        default:\n");
        w.format("            LightProtoCodec.skipUnknownField(_%sEntryTag, _buffer);\n", ccName);
        w.format("            break;\n");
        w.format("    }\n");
        w.format("}\n");

        // Store into arrays
        generateKeyTempStore(w);
        generateValueTempStore(w);
        w.format("_%sCount++;\n", ccName);
    }

    private void generateKeyTempDecl(PrintWriter w) {
        if (isStringKey()) {
            w.format("int _%sKeyLen = 0;\n", ccName);
            w.format("int _%sKeyIdx = -1;\n", ccName);
        } else {
            w.format("%s _%sKey = %s;\n", keyField.getJavaType(), ccName, defaultForType(keyField));
        }
    }

    private void generateValueTempDecl(PrintWriter w) {
        if (isStringValue()) {
            w.format("int _%sValueLen = 0;\n", ccName);
            w.format("int _%sValueIdx = -1;\n", ccName);
        } else if (isBytesValue()) {
            w.format("int _%sValueLen = 0;\n", ccName);
            w.format("int _%sValueIdx = -1;\n", ccName);
        } else if (isMessageValue()) {
            // Message value: no temp needed, will be parsed directly
        } else {
            w.format("%s _%sValue = %s;\n", valueField.getJavaType(), ccName, defaultForType(valueField));
        }
    }

    private void generateKeyTempParse(PrintWriter w) {
        if (isStringKey()) {
            w.format("            _%sKeyLen = LightProtoCodec.readVarInt(_buffer);\n", ccName);
            w.format("            _%sKeyIdx = _buffer.readerIndex();\n", ccName);
            w.format("            _buffer.skipBytes(_%sKeyLen);\n", ccName);
        } else {
            w.format("            _%sKey = %s;\n", ccName, LightProtoNumberField.parseNumber(keyField));
        }
    }

    private void generateValueTempParse(PrintWriter w) {
        if (isStringValue()) {
            w.format("            _%sValueLen = LightProtoCodec.readVarInt(_buffer);\n", ccName);
            w.format("            _%sValueIdx = _buffer.readerIndex();\n", ccName);
            w.format("            _buffer.skipBytes(_%sValueLen);\n", ccName);
        } else if (isBytesValue()) {
            w.format("            _%sValueLen = LightProtoCodec.readVarInt(_buffer);\n", ccName);
            w.format("            _%sValueIdx = _buffer.readerIndex();\n", ccName);
            w.format("            _buffer.skipBytes(_%sValueLen);\n", ccName);
        } else if (isMessageValue()) {
            w.format("            int _%sMsgSize = LightProtoCodec.readVarInt(_buffer);\n", ccName);
            w.format("            if (_%sValues[_%sCount] == null) {\n", ccName, ccName);
            w.format("                _%sValues[_%sCount] = new %s();\n", ccName, ccName, valueField.getJavaType());
            w.format("            }\n");
            w.format("            _%sValues[_%sCount].parseFrom(_buffer, _%sMsgSize);\n", ccName, ccName, ccName);
        } else {
            w.format("            _%sValue = %s;\n", ccName, LightProtoNumberField.parseNumber(valueField));
        }
    }

    private void generateKeyTempStore(PrintWriter w) {
        if (isStringKey()) {
            w.format("LightProtoCodec.StringHolder _%sKsh = _%sKeys[_%sCount];\n", ccName, ccName, ccName);
            w.format("if (_%sKsh == null) {\n", ccName);
            w.format("    _%sKsh = new LightProtoCodec.StringHolder();\n", ccName);
            w.format("    _%sKeys[_%sCount] = _%sKsh;\n", ccName, ccName, ccName);
            w.format("}\n");
            w.format("_%sKsh.s = null;\n", ccName);
            w.format("_%sKsh.idx = _%sKeyIdx;\n", ccName, ccName);
            w.format("_%sKsh.len = _%sKeyLen;\n", ccName, ccName);
        } else {
            w.format("_%sKeys[_%sCount] = _%sKey;\n", ccName, ccName, ccName);
        }
    }

    private void generateValueTempStore(PrintWriter w) {
        if (isStringValue()) {
            w.format("LightProtoCodec.StringHolder _%sVsh = _%sValues[_%sCount];\n", ccName, ccName, ccName);
            w.format("if (_%sVsh == null) {\n", ccName);
            w.format("    _%sVsh = new LightProtoCodec.StringHolder();\n", ccName);
            w.format("    _%sValues[_%sCount] = _%sVsh;\n", ccName, ccName, ccName);
            w.format("}\n");
            w.format("_%sVsh.s = null;\n", ccName);
            w.format("_%sVsh.idx = _%sValueIdx;\n", ccName, ccName);
            w.format("_%sVsh.len = _%sValueLen;\n", ccName, ccName);
        } else if (isBytesValue()) {
            w.format("LightProtoCodec.BytesHolder _%sVbh = _%sValues[_%sCount];\n", ccName, ccName, ccName);
            w.format("if (_%sVbh == null) {\n", ccName);
            w.format("    _%sVbh = new LightProtoCodec.BytesHolder();\n", ccName);
            w.format("    _%sValues[_%sCount] = _%sVbh;\n", ccName, ccName, ccName);
            w.format("}\n");
            w.format("_%sVbh.b = null;\n", ccName);
            w.format("_%sVbh.idx = _%sValueIdx;\n", ccName, ccName);
            w.format("_%sVbh.len = _%sValueLen;\n", ccName, ccName);
        } else if (isMessageValue()) {
            // Already stored during parse (parseFrom was called on the array element)
        } else {
            w.format("_%sValues[_%sCount] = _%sValue;\n", ccName, ccName, ccName);
        }
    }

    private String defaultForType(ProtoFieldDescriptor f) {
        if (f.isBoolField()) return "false";
        if (f.getJavaType().equals("long")) return "0L";
        if (f.getJavaType().equals("float")) return "0.0f";
        if (f.getJavaType().equals("double")) return "0.0";
        if (f.isEnumField()) return "null";
        return "0";
    }

    @Override
    public void serializeJson(PrintWriter w) {
        w.format("_b.writeByte('{');\n");
        w.format("for (int _i = 0; _i < _%sCount; _i++) {\n", ccName);
        w.format("    if (_i > 0) { _b.writeByte(','); }\n");

        // Write key (protobuf JSON always quotes map keys as strings)
        if (isStringKey()) {
            w.format("    LightProtoCodec.StringHolder _ksh = _%sKeys[_i];\n", ccName);
            w.format("    if (_ksh.s == null) {\n");
            w.format("        _ksh.s = LightProtoCodec.readString(_parsedBuffer, _ksh.idx, _ksh.len);\n");
            w.format("    }\n");
            w.format("    LightProtoCodec.writeJsonString(_b, _ksh.s);\n");
        } else if (keyField.getJavaType().equals("boolean")) {
            w.format("    _b.writeByte('\"');\n");
            w.format("    LightProtoCodec.writeJsonAscii(_b, Boolean.toString(_%sKeys[_i]));\n", ccName);
            w.format("    _b.writeByte('\"');\n");
        } else if (keyField.getJavaType().equals("long")) {
            w.format("    _b.writeByte('\"');\n");
            w.format("    LightProtoCodec.writeJsonAscii(_b, Long.toString(_%sKeys[_i]));\n", ccName);
            w.format("    _b.writeByte('\"');\n");
        } else {
            w.format("    _b.writeByte('\"');\n");
            w.format("    LightProtoCodec.writeJsonAscii(_b, Integer.toString(_%sKeys[_i]));\n", ccName);
            w.format("    _b.writeByte('\"');\n");
        }
        w.format("    _b.writeByte(':');\n");

        // Write value
        if (isStringValue()) {
            w.format("    LightProtoCodec.StringHolder _vsh = _%sValues[_i];\n", ccName);
            w.format("    if (_vsh.s == null) {\n");
            w.format("        _vsh.s = LightProtoCodec.readString(_parsedBuffer, _vsh.idx, _vsh.len);\n");
            w.format("    }\n");
            w.format("    LightProtoCodec.writeJsonString(_b, _vsh.s);\n");
        } else if (isBytesValue()) {
            w.format("    LightProtoCodec.BytesHolder _vbh = _%sValues[_i];\n", ccName);
            w.format("    if (_vbh.idx == -1) {\n");
            w.format("        LightProtoCodec.writeJsonBase64(_b, _vbh.b, 0, _vbh.len);\n");
            w.format("    } else {\n");
            w.format("        LightProtoCodec.writeJsonBase64(_b, _parsedBuffer, _vbh.idx, _vbh.len);\n");
            w.format("    }\n");
        } else if (isMessageValue()) {
            w.format("    _%sValues[_i].writeJsonTo(_b);\n", ccName);
        } else if (isEnumValue()) {
            w.format("    _b.writeByte('\"');\n");
            w.format("    LightProtoCodec.writeJsonAscii(_b, _%sValues[_i].name());\n", ccName);
            w.format("    _b.writeByte('\"');\n");
        } else if (valueField.getProtoType().equals("bool")) {
            w.format("    LightProtoCodec.writeJsonAscii(_b, Boolean.toString(_%sValues[_i]));\n", ccName);
        } else if (valueField.getJavaType().equals("long")) {
            w.format("    _b.writeByte('\"');\n");
            w.format("    LightProtoCodec.writeJsonAscii(_b, Long.toString(_%sValues[_i]));\n", ccName);
            w.format("    _b.writeByte('\"');\n");
        } else if (valueField.getJavaType().equals("float")) {
            w.format("    LightProtoCodec.writeJsonAscii(_b, Float.toString(_%sValues[_i]));\n", ccName);
        } else if (valueField.getJavaType().equals("double")) {
            w.format("    LightProtoCodec.writeJsonAscii(_b, Double.toString(_%sValues[_i]));\n", ccName);
        } else {
            w.format("    LightProtoCodec.writeJsonAscii(_b, Integer.toString(_%sValues[_i]));\n", ccName);
        }

        w.format("}\n");
        w.format("_b.writeByte('}');\n");
    }

    @Override
    public void parseJson(PrintWriter w) {
        w.format("                _r.expect((byte) '{');\n");
        w.format("                if (!_r.tryConsume((byte) '}')) {\n");
        w.format("                    do {\n");

        // Read key (always a quoted string in protobuf JSON)
        if (isStringKey()) {
            w.format("                        String _key = _r.readString();\n");
        } else if (keyField.getJavaType().equals("boolean")) {
            w.format("                        boolean _key = Boolean.parseBoolean(_r.readString());\n");
        } else if (keyField.getJavaType().equals("long")) {
            w.format("                        long _key = Long.parseLong(_r.readString());\n");
        } else {
            w.format("                        int _key = Integer.parseInt(_r.readString());\n");
        }
        w.format("                        _r.expect((byte) ':');\n");

        // Read value
        if (isMessageValue()) {
            w.format("                        %s(_key).parseFromJson(_r.buf());\n", Util.camelCase("put", ccName));
        } else if (isStringValue()) {
            w.format("                        %s(_key, _r.readString());\n", Util.camelCase("put", ccName));
        } else if (isBytesValue()) {
            w.format("                        %s(_key, _r.readBase64Bytes());\n", Util.camelCase("put", ccName));
        } else if (isEnumValue()) {
            w.format("                        { %s _v = %s.valueOf(_r.readString());\n",
                    valueField.getJavaType(), valueField.getJavaType());
            w.format("                        if (_v != null) { %s(_key, _v); } }\n", Util.camelCase("put", ccName));
        } else if (valueField.getProtoType().equals("bool")) {
            w.format("                        %s(_key, _r.readBool());\n", Util.camelCase("put", ccName));
        } else if (valueField.getProtoType().equals("float")) {
            w.format("                        %s(_key, _r.readFloat());\n", Util.camelCase("put", ccName));
        } else if (valueField.getProtoType().equals("double")) {
            w.format("                        %s(_key, _r.readDouble());\n", Util.camelCase("put", ccName));
        } else if (valueField.getJavaType().equals("long")) {
            w.format("                        %s(_key, _r.readLong());\n", Util.camelCase("put", ccName));
        } else {
            w.format("                        %s(_key, _r.readInt());\n", Util.camelCase("put", ccName));
        }

        w.format("                    } while (_r.tryConsume((byte) ','));\n");
        w.format("                    _r.expect((byte) '}');\n");
        w.format("                }\n");
    }

    @Override
    public void serialize(PrintWriter w) {
        w.format("for (int _i = 0; _i < _%sCount; _i++) {\n", ccName);

        // Compute entry size
        w.format("    int _entrySize = 0;\n");

        // Key size: 1 (tag) + data size
        w.format("    _entrySize += 1;\n"); // key tag is always 1 byte
        generateKeyDataSize(w, "_i");

        // Value size: 1 (tag) + data size
        w.format("    _entrySize += 1;\n"); // value tag is always 1 byte
        generateValueDataSize(w, "_i");

        // Write outer tag + entry size
        w.format("    %s;\n", writeTagExpr(tagName()));
        w.format("    _addr = LightProtoCodec.writeRawVarInt(_base, _addr, _entrySize);\n");

        // Write key tag + key data
        w.format("    _addr = LightProtoCodec.writeRawByte(_base, _addr, %s);\n", keyTagConstant());
        generateSerializeKeyData(w, "_i");

        // Write value tag + value data
        w.format("    _addr = LightProtoCodec.writeRawByte(_base, _addr, %s);\n", valueTagConstant());
        generateSerializeValueData(w, "_i");

        w.format("}\n");
    }

    private void generateKeyDataSize(PrintWriter w, String idxVar) {
        if (isStringKey()) {
            w.format("    _entrySize += LightProtoCodec.computeVarIntSize(_%sKeys[%s].len) + _%sKeys[%s].len;\n",
                    ccName, idxVar, ccName, idxVar);
        } else {
            w.format("    _entrySize += %s;\n",
                    LightProtoNumberField.serializedSizeOfNumber(keyField, String.format("_%sKeys[%s]", ccName, idxVar)));
        }
    }

    private void generateValueDataSize(PrintWriter w, String idxVar) {
        if (isStringValue()) {
            w.format("    _entrySize += LightProtoCodec.computeVarIntSize(_%sValues[%s].len) + _%sValues[%s].len;\n",
                    ccName, idxVar, ccName, idxVar);
        } else if (isBytesValue()) {
            w.format("    _entrySize += LightProtoCodec.computeVarIntSize(_%sValues[%s].len) + _%sValues[%s].len;\n",
                    ccName, idxVar, ccName, idxVar);
        } else if (isMessageValue()) {
            w.format("    int _msgSize_%s = _%sValues[%s].getSerializedSize();\n", idxVar, ccName, idxVar);
            w.format("    _entrySize += LightProtoCodec.computeVarIntSize(_msgSize_%s) + _msgSize_%s;\n", idxVar, idxVar);
        } else {
            w.format("    _entrySize += %s;\n",
                    LightProtoNumberField.serializedSizeOfNumber(valueField, String.format("_%sValues[%s]", ccName, idxVar)));
        }
    }

    private void generateSerializeKeyData(PrintWriter w, String idxVar) {
        if (isStringKey()) {
            w.format("    LightProtoCodec.StringHolder _ksh = _%sKeys[%s];\n", ccName, idxVar);
            w.format("    _addr = LightProtoCodec.writeRawVarInt(_base, _addr, _ksh.len);\n");
            w.format("    if (_ksh.idx == -1) {\n");
            w.format("        long _r = LightProtoCodec.writeRawString(_base, _addr, _ksh.s, _ksh.len);\n");
            w.format("        if (_r >= 0) {\n");
            w.format("            _addr = _r;\n");
            w.format("        } else {\n");
            w.format("            _b.writerIndex((int)(_addr - _baseOffset));\n");
            w.format("            LightProtoCodec.writeString(_b, _ksh.s, _ksh.len);\n");
            w.format("            _addr = _baseOffset + _b.writerIndex();\n");
            w.format("        }\n");
            w.format("    } else {\n");
            w.format("        _b.writerIndex((int)(_addr - _baseOffset));\n");
            w.format("        _parsedBuffer.getBytes(_ksh.idx, _b, _ksh.len);\n");
            w.format("        _addr = _baseOffset + _b.writerIndex();\n");
            w.format("    }\n");
        } else {
            LightProtoNumberField.serializeNumber(w, keyField, String.format("_%sKeys[%s]", ccName, idxVar));
        }
    }

    private void generateSerializeValueData(PrintWriter w, String idxVar) {
        if (isStringValue()) {
            w.format("    LightProtoCodec.StringHolder _vsh = _%sValues[%s];\n", ccName, idxVar);
            w.format("    _addr = LightProtoCodec.writeRawVarInt(_base, _addr, _vsh.len);\n");
            w.format("    if (_vsh.idx == -1) {\n");
            w.format("        long _r = LightProtoCodec.writeRawString(_base, _addr, _vsh.s, _vsh.len);\n");
            w.format("        if (_r >= 0) {\n");
            w.format("            _addr = _r;\n");
            w.format("        } else {\n");
            w.format("            _b.writerIndex((int)(_addr - _baseOffset));\n");
            w.format("            LightProtoCodec.writeString(_b, _vsh.s, _vsh.len);\n");
            w.format("            _addr = _baseOffset + _b.writerIndex();\n");
            w.format("        }\n");
            w.format("    } else {\n");
            w.format("        _b.writerIndex((int)(_addr - _baseOffset));\n");
            w.format("        _parsedBuffer.getBytes(_vsh.idx, _b, _vsh.len);\n");
            w.format("        _addr = _baseOffset + _b.writerIndex();\n");
            w.format("    }\n");
        } else if (isBytesValue()) {
            w.format("    LightProtoCodec.BytesHolder _vbh = _%sValues[%s];\n", ccName, idxVar);
            w.format("    _addr = LightProtoCodec.writeRawVarInt(_base, _addr, _vbh.len);\n");
            w.format("    _b.writerIndex((int)(_addr - _baseOffset));\n");
            w.format("    if (_vbh.idx == -1) {\n");
            w.format("        _vbh.b.getBytes(0, _b, _vbh.len);\n");
            w.format("    } else {\n");
            w.format("        _parsedBuffer.getBytes(_vbh.idx, _b, _vbh.len);\n");
            w.format("    }\n");
            w.format("    _addr = _baseOffset + _b.writerIndex();\n");
        } else if (isMessageValue()) {
            w.format("    _addr = LightProtoCodec.writeRawVarInt(_base, _addr, _%sValues[%s].getSerializedSize());\n",
                    ccName, idxVar);
            w.format("    _b.writerIndex((int)(_addr - _baseOffset));\n");
            w.format("    _%sValues[%s].writeTo(_b);\n", ccName, idxVar);
            w.format("    _addr = _baseOffset + _b.writerIndex();\n");
        } else {
            LightProtoNumberField.serializeNumber(w, valueField, String.format("_%sValues[%s]", ccName, idxVar));
        }
    }

    @Override
    public void serializedSize(PrintWriter w) {
        w.format("for (int _i = 0; _i < _%sCount; _i++) {\n", ccName);
        w.format("    int _entrySize = 0;\n");

        // Key: 1 (tag) + data size
        w.format("    _entrySize += 1;\n");
        generateKeyDataSize(w, "_i");

        // Value: 1 (tag) + data size
        w.format("    _entrySize += 1;\n");
        generateValueDataSize(w, "_i");

        w.format("    _size += %s_SIZE + LightProtoCodec.computeVarIntSize(_entrySize) + _entrySize;\n", tagName());
        w.format("}\n");
    }

    @Override
    public void copy(PrintWriter w) {
        w.format("for (int _i = 0; _i < _other._%sCount; _i++) {\n", ccName);

        // Resolve key from _other
        if (isStringKey()) {
            w.format("    LightProtoCodec.StringHolder _ksh = _other._%sKeys[_i];\n", ccName);
            w.format("    if (_ksh.s == null) {\n");
            w.format("        _ksh.s = LightProtoCodec.readString(_other._parsedBuffer, _ksh.idx, _ksh.len);\n");
            w.format("    }\n");
            w.format("    %s _key = _ksh.s;\n", keyField.getJavaType());
        } else {
            w.format("    %s _key = _other._%sKeys[_i];\n", keyField.getJavaType(), ccName);
        }

        // Copy based on value type
        if (isMessageValue()) {
            w.format("    %s(_key).copyFrom(_other._%sValues[_i]);\n",
                    Util.camelCase("put", ccName), ccName);
        } else if (isStringValue()) {
            w.format("    LightProtoCodec.StringHolder _vsh = _other._%sValues[_i];\n", ccName);
            w.format("    if (_vsh.s == null) {\n");
            w.format("        _vsh.s = LightProtoCodec.readString(_other._parsedBuffer, _vsh.idx, _vsh.len);\n");
            w.format("    }\n");
            w.format("    %s(_key, _vsh.s);\n", Util.camelCase("put", ccName));
        } else if (isBytesValue()) {
            w.format("    LightProtoCodec.BytesHolder _vbh = _other._%sValues[_i];\n", ccName);
            w.format("    byte[] _val;\n");
            w.format("    if (_vbh.idx == -1) {\n");
            w.format("        _val = new byte[_vbh.len];\n");
            w.format("        _vbh.b.getBytes(0, _val);\n");
            w.format("    } else {\n");
            w.format("        _val = new byte[_vbh.len];\n");
            w.format("        _other._parsedBuffer.getBytes(_vbh.idx, _val);\n");
            w.format("    }\n");
            w.format("    %s(_key, _val);\n", Util.camelCase("put", ccName));
        } else {
            w.format("    %s(_key, _other._%sValues[_i]);\n", Util.camelCase("put", ccName), ccName);
        }

        w.format("}\n");
    }

    @Override
    public void clear(PrintWriter w) {
        if (isStringKey()) {
            w.format("for (int _i = 0; _i < _%sCount; _i++) {\n", ccName);
            w.format("    LightProtoCodec.StringHolder _sh = _%sKeys[_i];\n", ccName);
            w.format("    _sh.s = null;\n");
            w.format("    _sh.idx = -1;\n");
            w.format("    _sh.len = -1;\n");
            w.format("}\n");
        }
        if (isStringValue()) {
            w.format("for (int _i = 0; _i < _%sCount; _i++) {\n", ccName);
            w.format("    LightProtoCodec.StringHolder _sh = _%sValues[_i];\n", ccName);
            w.format("    _sh.s = null;\n");
            w.format("    _sh.idx = -1;\n");
            w.format("    _sh.len = -1;\n");
            w.format("}\n");
        } else if (isBytesValue()) {
            w.format("for (int _i = 0; _i < _%sCount; _i++) {\n", ccName);
            w.format("    LightProtoCodec.BytesHolder _bh = _%sValues[_i];\n", ccName);
            w.format("    _bh.b = null;\n");
            w.format("    _bh.idx = -1;\n");
            w.format("    _bh.len = -1;\n");
            w.format("}\n");
        } else if (isMessageValue()) {
            w.format("for (int _i = 0; _i < _%sCount; _i++) {\n", ccName);
            w.format("    _%sValues[_i].clear();\n", ccName);
            w.format("}\n");
        }
        w.format("_%sCount = 0;\n", ccName);
        w.format("_%sIndex = null;\n", ccName);
    }

    @Override
    public void materialize(PrintWriter w) {
        if (isStringKey()) {
            w.format("for (int _i = 0; _i < _%sCount; _i++) {\n", ccName);
            w.format("    LightProtoCodec.StringHolder _ksh = _%sKeys[_i];\n", ccName);
            w.format("    if (_ksh.s == null && _ksh.idx >= 0) {\n");
            w.format("        _ksh.s = LightProtoCodec.readString(_parsedBuffer, _ksh.idx, _ksh.len);\n");
            w.format("        _ksh.idx = -1;\n");
            w.format("    }\n");
            w.format("}\n");
        }
        if (isStringValue()) {
            w.format("for (int _i = 0; _i < _%sCount; _i++) {\n", ccName);
            w.format("    LightProtoCodec.StringHolder _vsh = _%sValues[_i];\n", ccName);
            w.format("    if (_vsh.s == null && _vsh.idx >= 0) {\n");
            w.format("        _vsh.s = LightProtoCodec.readString(_parsedBuffer, _vsh.idx, _vsh.len);\n");
            w.format("        _vsh.idx = -1;\n");
            w.format("    }\n");
            w.format("}\n");
        } else if (isBytesValue()) {
            w.format("for (int _i = 0; _i < _%sCount; _i++) {\n", ccName);
            w.format("    LightProtoCodec.BytesHolder _vbh = _%sValues[_i];\n", ccName);
            w.format("    if (_vbh.b == null && _vbh.idx >= 0) {\n");
            w.format("        byte[] _tmp = new byte[_vbh.len];\n");
            w.format("        _parsedBuffer.getBytes(_vbh.idx, _tmp);\n");
            w.format("        _vbh.b = io.netty.buffer.Unpooled.wrappedBuffer(_tmp);\n");
            w.format("        _vbh.idx = -1;\n");
            w.format("    }\n");
            w.format("}\n");
        } else if (isMessageValue()) {
            w.format("for (int _i = 0; _i < _%sCount; _i++) {\n", ccName);
            w.format("    _%sValues[_i].materialize();\n", ccName);
            w.format("}\n");
        }
    }

    @Override
    public void equalsCode(PrintWriter w) {
        w.format("if (_%sCount != _other._%sCount) return false;\n", ccName, ccName);
        w.format("for (int _i = 0; _i < _%sCount; _i++) {\n", ccName);

        // Resolve key
        if (isStringKey()) {
            w.format("    LightProtoCodec.StringHolder _ksh = _%sKeys[_i];\n", ccName);
            w.format("    if (_ksh.s == null) {\n");
            w.format("        _ksh.s = LightProtoCodec.readString(_parsedBuffer, _ksh.idx, _ksh.len);\n");
            w.format("    }\n");
            w.format("    int _oIdx = _other._find%sKeyIndex(_ksh.s);\n", Util.camelCaseFirstUpper(ccName));
        } else {
            w.format("    int _oIdx = _other._find%sKeyIndex(_%sKeys[_i]);\n", Util.camelCaseFirstUpper(ccName), ccName);
        }
        w.format("    if (_oIdx < 0) return false;\n");

        // Compare values
        if (isStringValue()) {
            w.format("    LightProtoCodec.StringHolder _vsh1 = _%sValues[_i];\n", ccName);
            w.format("    if (_vsh1.s == null) { _vsh1.s = LightProtoCodec.readString(_parsedBuffer, _vsh1.idx, _vsh1.len); }\n");
            w.format("    LightProtoCodec.StringHolder _vsh2 = _other._%sValues[_oIdx];\n", ccName);
            w.format("    if (_vsh2.s == null) { _vsh2.s = LightProtoCodec.readString(_other._parsedBuffer, _vsh2.idx, _vsh2.len); }\n");
            w.format("    if (!java.util.Objects.equals(_vsh1.s, _vsh2.s)) return false;\n");
        } else if (isBytesValue()) {
            w.format("    LightProtoCodec.BytesHolder _vbh1 = _%sValues[_i];\n", ccName);
            w.format("    io.netty.buffer.ByteBuf _bs1 = _vbh1.b != null ? _vbh1.b.slice(0, _vbh1.len) : _parsedBuffer.slice(_vbh1.idx, _vbh1.len);\n");
            w.format("    LightProtoCodec.BytesHolder _vbh2 = _other._%sValues[_oIdx];\n", ccName);
            w.format("    io.netty.buffer.ByteBuf _bs2 = _vbh2.b != null ? _vbh2.b.slice(0, _vbh2.len) : _other._parsedBuffer.slice(_vbh2.idx, _vbh2.len);\n");
            w.format("    if (!io.netty.buffer.ByteBufUtil.equals(_bs1, _bs2)) return false;\n");
        } else if (isMessageValue()) {
            w.format("    if (!_%sValues[_i].equals(_other._%sValues[_oIdx])) return false;\n", ccName, ccName);
        } else if (isEnumValue()) {
            w.format("    if (_%sValues[_i] != _other._%sValues[_oIdx]) return false;\n", ccName, ccName);
        } else {
            // Primitive values (int, long, float, double, bool)
            String type = valueField.getProtoType();
            if (type.equals("float")) {
                w.format("    if (Float.floatToIntBits(_%sValues[_i]) != Float.floatToIntBits(_other._%sValues[_oIdx])) return false;\n", ccName, ccName);
            } else if (type.equals("double")) {
                w.format("    if (Double.doubleToLongBits(_%sValues[_i]) != Double.doubleToLongBits(_other._%sValues[_oIdx])) return false;\n", ccName, ccName);
            } else {
                w.format("    if (_%sValues[_i] != _other._%sValues[_oIdx]) return false;\n", ccName, ccName);
            }
        }

        w.format("}\n");
    }

    @Override
    public void hashCodeCode(PrintWriter w) {
        // Order-independent hash: sum the hash of each entry
        w.format("int _%sHash = 0;\n", ccName);
        w.format("for (int _i = 0; _i < _%sCount; _i++) {\n", ccName);
        w.format("    int _eH = 0;\n");

        // Hash key
        if (isStringKey()) {
            w.format("    LightProtoCodec.StringHolder _ksh = _%sKeys[_i];\n", ccName);
            w.format("    if (_ksh.s == null) { _ksh.s = LightProtoCodec.readString(_parsedBuffer, _ksh.idx, _ksh.len); }\n");
            w.format("    _eH = 31 * _eH + _ksh.s.hashCode();\n");
        } else {
            String keyType = keyField.getProtoType();
            if (keyType.equals("int64") || keyType.equals("uint64") || keyType.equals("sint64")
                    || keyType.equals("fixed64") || keyType.equals("sfixed64")) {
                w.format("    _eH = 31 * _eH + Long.hashCode(_%sKeys[_i]);\n", ccName);
            } else if (keyType.equals("bool")) {
                w.format("    _eH = 31 * _eH + (_%sKeys[_i] ? 1231 : 1237);\n", ccName);
            } else {
                w.format("    _eH = 31 * _eH + _%sKeys[_i];\n", ccName);
            }
        }

        // Hash value
        if (isStringValue()) {
            w.format("    LightProtoCodec.StringHolder _vsh = _%sValues[_i];\n", ccName);
            w.format("    if (_vsh.s == null) { _vsh.s = LightProtoCodec.readString(_parsedBuffer, _vsh.idx, _vsh.len); }\n");
            w.format("    _eH = 31 * _eH + _vsh.s.hashCode();\n");
        } else if (isBytesValue()) {
            w.format("    LightProtoCodec.BytesHolder _vbh = _%sValues[_i];\n", ccName);
            w.format("    io.netty.buffer.ByteBuf _bs = _vbh.b != null ? _vbh.b.slice(0, _vbh.len) : _parsedBuffer.slice(_vbh.idx, _vbh.len);\n");
            w.format("    _eH = 31 * _eH + io.netty.buffer.ByteBufUtil.hashCode(_bs);\n");
        } else if (isMessageValue()) {
            w.format("    _eH = 31 * _eH + _%sValues[_i].hashCode();\n", ccName);
        } else if (isEnumValue()) {
            w.format("    _eH = 31 * _eH + _%sValues[_i].getValue();\n", ccName);
        } else {
            String valType = valueField.getProtoType();
            if (valType.equals("float")) {
                w.format("    _eH = 31 * _eH + Float.floatToIntBits(_%sValues[_i]);\n", ccName);
            } else if (valType.equals("double")) {
                w.format("    _eH = 31 * _eH + Long.hashCode(Double.doubleToLongBits(_%sValues[_i]));\n", ccName);
            } else if (valType.equals("int64") || valType.equals("uint64") || valType.equals("sint64")
                    || valType.equals("fixed64") || valType.equals("sfixed64")) {
                w.format("    _eH = 31 * _eH + Long.hashCode(_%sValues[_i]);\n", ccName);
            } else if (valType.equals("bool")) {
                w.format("    _eH = 31 * _eH + (_%sValues[_i] ? 1231 : 1237);\n", ccName);
            } else {
                w.format("    _eH = 31 * _eH + _%sValues[_i];\n", ccName);
            }
        }

        w.format("    _%sHash += _eH;\n", ccName);
        w.format("}\n");
        w.format("_h = 31 * _h + _%sHash;\n", ccName);
    }

    @Override
    protected String typeTag() {
        return "LightProtoCodec.WIRETYPE_LENGTH_DELIMITED";
    }
}
