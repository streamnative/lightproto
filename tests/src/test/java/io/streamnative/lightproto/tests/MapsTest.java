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
package io.streamnative.lightproto.tests;

import com.google.protobuf.ByteString;
import com.google.protobuf.TextFormat;
import com.google.protobuf.util.JsonFormat;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MapsTest {

    private byte[] b1 = new byte[4096];
    private ByteBuf bb1 = Unpooled.wrappedBuffer(b1);

    // One entry with a non-default key and value in each map
    private static final byte[] NON_DEFAULT_ENTRIES = MapsProtos.MapMessage.newBuilder()
            .putStringToInt("x", 1)
            .putIntToString(1, "x")
            .putStringToMsg("x", MapsProtos.MapNestedValue.newBuilder().setId(1).setName("x").build())
            .putStringToBytes("x", ByteString.copyFrom(new byte[]{1}))
            .putBoolToString(true, "x")
            .putStringToDouble("x", 1.0)
            .putStringToEnum("x", MapsProtos.MapEnumValue.MAP_ENUM_ONE)
            .build()
            .toByteArray();

    @BeforeEach
    public void setup() {
        bb1.clear();
    }

    @Test
    public void testEmptyMap() throws Exception {
        MapMessage lp = new MapMessage();
        assertEquals(0, lp.getStringToIntCount());
        assertEquals(0, lp.getIntToStringCount());
        assertEquals(0, lp.getStringToMsgCount());
        assertEquals(0, lp.getStringToBytesCount());
        assertEquals(0, lp.getBoolToStringCount());
        assertEquals(0, lp.getStringToDoubleCount());
        assertEquals(0, lp.getSerializedSize());

        verifyRoundtrip(lp);
    }

    @Test
    public void testStringToInt() throws Exception {
        MapMessage lp = new MapMessage();
        lp.putStringToInt("hello", 42);
        lp.putStringToInt("world", 99);

        assertEquals(2, lp.getStringToIntCount());
        assertEquals(42, lp.getStringToInt("hello"));
        assertEquals(99, lp.getStringToInt("world"));

        verifyRoundtrip(lp);
    }

    @Test
    public void testIntToString() throws Exception {
        MapMessage lp = new MapMessage();
        lp.putIntToString(1, "one");
        lp.putIntToString(2, "two");
        lp.putIntToString(3, "three");

        assertEquals(3, lp.getIntToStringCount());
        assertEquals("one", lp.getIntToString(1));
        assertEquals("two", lp.getIntToString(2));
        assertEquals("three", lp.getIntToString(3));

        verifyRoundtrip(lp);
    }

    @Test
    public void testStringToMsg() throws Exception {
        MapMessage lp = new MapMessage();
        lp.putStringToMsg("first").setId(1).setName("alpha");
        lp.putStringToMsg("second").setId(2).setName("beta");

        assertEquals(2, lp.getStringToMsgCount());
        assertEquals(1, lp.getStringToMsg("first").getId());
        assertEquals("alpha", lp.getStringToMsg("first").getName());
        assertEquals(2, lp.getStringToMsg("second").getId());
        assertEquals("beta", lp.getStringToMsg("second").getName());

        verifyRoundtrip(lp);
    }

    @Test
    public void testStringToBytes() throws Exception {
        MapMessage lp = new MapMessage();
        byte[] data1 = {1, 2, 3};
        byte[] data2 = {4, 5, 6, 7, 8};
        lp.putStringToBytes("a", data1);
        lp.putStringToBytes("b", data2);

        assertEquals(2, lp.getStringToBytesCount());
        assertArrayEquals(data1, lp.getStringToBytes("a"));
        assertArrayEquals(data2, lp.getStringToBytes("b"));

        verifyRoundtrip(lp);
    }

    @Test
    public void testBoolToString() throws Exception {
        MapMessage lp = new MapMessage();
        lp.putBoolToString(true, "yes");
        lp.putBoolToString(false, "no");

        assertEquals(2, lp.getBoolToStringCount());
        assertEquals("yes", lp.getBoolToString(true));
        assertEquals("no", lp.getBoolToString(false));

        verifyRoundtrip(lp);
    }

    @Test
    public void testStringToDouble() throws Exception {
        MapMessage lp = new MapMessage();
        lp.putStringToDouble("pi", 3.14159);
        lp.putStringToDouble("e", 2.71828);

        assertEquals(2, lp.getStringToDoubleCount());
        assertEquals(3.14159, lp.getStringToDouble("pi"), 0.001);
        assertEquals(2.71828, lp.getStringToDouble("e"), 0.001);

        verifyRoundtrip(lp);
    }

    @Test
    public void testDuplicateKeyOverwrites() {
        MapMessage lp = new MapMessage();
        lp.putStringToInt("key", 1);
        lp.putStringToInt("key", 2);

        assertEquals(1, lp.getStringToIntCount());
        assertEquals(2, lp.getStringToInt("key"));
    }

    @Test
    public void testGetMissingKeyThrows() {
        MapMessage lp = new MapMessage();
        lp.putStringToInt("existing", 1);

        assertThrows(IllegalArgumentException.class, () -> lp.getStringToInt("missing"));
    }

    @Test
    public void testForEach() {
        MapMessage lp = new MapMessage();
        lp.putStringToInt("a", 1);
        lp.putStringToInt("b", 2);
        lp.putStringToInt("c", 3);

        Map<String, Integer> collected = new HashMap<>();
        lp.forEachStringToInt(collected::put);

        assertEquals(3, collected.size());
        assertEquals(1, collected.get("a"));
        assertEquals(2, collected.get("b"));
        assertEquals(3, collected.get("c"));
    }

    @Test
    public void testClear() throws Exception {
        MapMessage lp = new MapMessage();
        lp.putStringToInt("a", 1);
        lp.putIntToString(1, "one");
        lp.setName("test");

        lp.clearStringToInt();
        assertEquals(0, lp.getStringToIntCount());
        // Other fields unaffected
        assertEquals(1, lp.getIntToStringCount());
        assertTrue(lp.hasName());
    }

    @Test
    public void testFullClear() {
        MapMessage lp = new MapMessage();
        lp.putStringToInt("a", 1);
        lp.putIntToString(1, "one");
        lp.setName("test");

        lp.clear();
        assertEquals(0, lp.getStringToIntCount());
        assertEquals(0, lp.getIntToStringCount());
        assertFalse(lp.hasName());
    }

    @Test
    public void testCopyFrom() {
        MapMessage src = new MapMessage();
        src.putStringToInt("a", 1);
        src.putStringToInt("b", 2);
        src.putIntToString(10, "ten");
        src.putStringToMsg("msg").setId(42).setName("test");
        src.setName("source");

        MapMessage dst = new MapMessage();
        dst.copyFrom(src);

        assertEquals(2, dst.getStringToIntCount());
        assertEquals(1, dst.getStringToInt("a"));
        assertEquals(2, dst.getStringToInt("b"));
        assertEquals(1, dst.getIntToStringCount());
        assertEquals("ten", dst.getIntToString(10));
        assertEquals(1, dst.getStringToMsgCount());
        assertEquals(42, dst.getStringToMsg("msg").getId());
        assertEquals("test", dst.getStringToMsg("msg").getName());
        assertEquals("source", dst.getName());
    }

    @Test
    public void testNonMapFieldsUnaffected() throws Exception {
        MapMessage lp = new MapMessage();
        lp.setName("test");
        lp.putStringToInt("a", 1);

        assertTrue(lp.hasName());
        assertEquals("test", lp.getName());
        assertEquals(1, lp.getStringToIntCount());

        verifyRoundtrip(lp);
    }

    // --- Cross-format wire compatibility tests ---

    @Test
    public void testLightProtoToProtobuf_StringToInt() throws Exception {
        MapMessage lp = new MapMessage();
        lp.putStringToInt("hello", 42);
        lp.putStringToInt("world", 99);

        byte[] lpBytes = serialize(lp);

        MapsProtos.MapMessage gpParsed = MapsProtos.MapMessage.parseFrom(lpBytes);
        assertEquals(2, gpParsed.getStringToIntCount());
        assertEquals(42, gpParsed.getStringToIntOrThrow("hello"));
        assertEquals(99, gpParsed.getStringToIntOrThrow("world"));
    }

    @Test
    public void testProtobufToLightProto_StringToInt() throws Exception {
        MapsProtos.MapMessage gp = MapsProtos.MapMessage.newBuilder()
                .putStringToInt("hello", 42)
                .putStringToInt("world", 99)
                .build();

        byte[] gpBytes = gp.toByteArray();

        MapMessage lpParsed = new MapMessage();
        lpParsed.parseFrom(gpBytes);
        assertEquals(2, lpParsed.getStringToIntCount());
        assertEquals(42, lpParsed.getStringToInt("hello"));
        assertEquals(99, lpParsed.getStringToInt("world"));
    }

    @Test
    public void testLightProtoToProtobuf_IntToString() throws Exception {
        MapMessage lp = new MapMessage();
        lp.putIntToString(1, "one");
        lp.putIntToString(2, "two");

        byte[] lpBytes = serialize(lp);

        MapsProtos.MapMessage gpParsed = MapsProtos.MapMessage.parseFrom(lpBytes);
        assertEquals(2, gpParsed.getIntToStringCount());
        assertEquals("one", gpParsed.getIntToStringOrThrow(1));
        assertEquals("two", gpParsed.getIntToStringOrThrow(2));
    }

    @Test
    public void testProtobufToLightProto_IntToString() throws Exception {
        MapsProtos.MapMessage gp = MapsProtos.MapMessage.newBuilder()
                .putIntToString(1, "one")
                .putIntToString(2, "two")
                .build();

        byte[] gpBytes = gp.toByteArray();

        MapMessage lpParsed = new MapMessage();
        lpParsed.parseFrom(gpBytes);
        assertEquals(2, lpParsed.getIntToStringCount());
        assertEquals("one", lpParsed.getIntToString(1));
        assertEquals("two", lpParsed.getIntToString(2));
    }

    @Test
    public void testLightProtoToProtobuf_StringToMsg() throws Exception {
        MapMessage lp = new MapMessage();
        lp.putStringToMsg("item").setId(42).setName("test");

        byte[] lpBytes = serialize(lp);

        MapsProtos.MapMessage gpParsed = MapsProtos.MapMessage.parseFrom(lpBytes);
        assertEquals(1, gpParsed.getStringToMsgCount());
        MapsProtos.MapNestedValue gpVal = gpParsed.getStringToMsgOrThrow("item");
        assertEquals(42, gpVal.getId());
        assertEquals("test", gpVal.getName());
    }

    @Test
    public void testProtobufToLightProto_StringToMsg() throws Exception {
        MapsProtos.MapMessage gp = MapsProtos.MapMessage.newBuilder()
                .putStringToMsg("item", MapsProtos.MapNestedValue.newBuilder()
                        .setId(42).setName("test").build())
                .build();

        byte[] gpBytes = gp.toByteArray();

        MapMessage lpParsed = new MapMessage();
        lpParsed.parseFrom(gpBytes);
        assertEquals(1, lpParsed.getStringToMsgCount());
        assertEquals(42, lpParsed.getStringToMsg("item").getId());
        assertEquals("test", lpParsed.getStringToMsg("item").getName());
    }

    @Test
    public void testLightProtoToProtobuf_StringToBytes() throws Exception {
        MapMessage lp = new MapMessage();
        byte[] data = {1, 2, 3, 4, 5};
        lp.putStringToBytes("data", data);

        byte[] lpBytes = serialize(lp);

        MapsProtos.MapMessage gpParsed = MapsProtos.MapMessage.parseFrom(lpBytes);
        assertEquals(1, gpParsed.getStringToBytesCount());
        assertArrayEquals(data, gpParsed.getStringToBytesOrThrow("data").toByteArray());
    }

    @Test
    public void testProtobufToLightProto_StringToBytes() throws Exception {
        byte[] data = {1, 2, 3, 4, 5};
        MapsProtos.MapMessage gp = MapsProtos.MapMessage.newBuilder()
                .putStringToBytes("data", ByteString.copyFrom(data))
                .build();

        byte[] gpBytes = gp.toByteArray();

        MapMessage lpParsed = new MapMessage();
        lpParsed.parseFrom(gpBytes);
        assertEquals(1, lpParsed.getStringToBytesCount());
        assertArrayEquals(data, lpParsed.getStringToBytes("data"));
    }

    @Test
    public void testLightProtoToProtobuf_BoolToString() throws Exception {
        MapMessage lp = new MapMessage();
        lp.putBoolToString(true, "yes");
        lp.putBoolToString(false, "no");

        byte[] lpBytes = serialize(lp);

        MapsProtos.MapMessage gpParsed = MapsProtos.MapMessage.parseFrom(lpBytes);
        assertEquals(2, gpParsed.getBoolToStringCount());
        assertEquals("yes", gpParsed.getBoolToStringOrThrow(true));
        assertEquals("no", gpParsed.getBoolToStringOrThrow(false));
    }

    @Test
    public void testProtobufToLightProto_BoolToString() throws Exception {
        MapsProtos.MapMessage gp = MapsProtos.MapMessage.newBuilder()
                .putBoolToString(true, "yes")
                .putBoolToString(false, "no")
                .build();

        byte[] gpBytes = gp.toByteArray();

        MapMessage lpParsed = new MapMessage();
        lpParsed.parseFrom(gpBytes);
        assertEquals(2, lpParsed.getBoolToStringCount());
        assertEquals("yes", lpParsed.getBoolToString(true));
        assertEquals("no", lpParsed.getBoolToString(false));
    }

    @Test
    public void testLightProtoToProtobuf_StringToDouble() throws Exception {
        MapMessage lp = new MapMessage();
        lp.putStringToDouble("pi", 3.14159);
        lp.putStringToDouble("e", 2.71828);

        byte[] lpBytes = serialize(lp);

        MapsProtos.MapMessage gpParsed = MapsProtos.MapMessage.parseFrom(lpBytes);
        assertEquals(2, gpParsed.getStringToDoubleCount());
        assertEquals(3.14159, gpParsed.getStringToDoubleOrThrow("pi"), 0.001);
        assertEquals(2.71828, gpParsed.getStringToDoubleOrThrow("e"), 0.001);
    }

    @Test
    public void testProtobufToLightProto_StringToDouble() throws Exception {
        MapsProtos.MapMessage gp = MapsProtos.MapMessage.newBuilder()
                .putStringToDouble("pi", 3.14159)
                .putStringToDouble("e", 2.71828)
                .build();

        byte[] gpBytes = gp.toByteArray();

        MapMessage lpParsed = new MapMessage();
        lpParsed.parseFrom(gpBytes);
        assertEquals(2, lpParsed.getStringToDoubleCount());
        assertEquals(3.14159, lpParsed.getStringToDouble("pi"), 0.001);
        assertEquals(2.71828, lpParsed.getStringToDouble("e"), 0.001);
    }

    @Test
    public void testFullCrossFormatCompatibility() throws Exception {
        // Build with LightProto
        MapMessage lp = new MapMessage();
        lp.putStringToInt("a", 1);
        lp.putStringToInt("b", 2);
        lp.putIntToString(10, "ten");
        lp.putIntToString(20, "twenty");
        lp.putStringToMsg("msg1").setId(1).setName("first");
        lp.putStringToBytes("data", new byte[]{1, 2, 3});
        lp.putBoolToString(true, "TRUE");
        lp.putStringToDouble("val", 1.5);
        lp.setName("compat-test");

        byte[] lpBytes = serialize(lp);

        // Parse with Google Protobuf
        MapsProtos.MapMessage gpParsed = MapsProtos.MapMessage.parseFrom(lpBytes);
        assertEquals(2, gpParsed.getStringToIntCount());
        assertEquals(1, gpParsed.getStringToIntOrThrow("a"));
        assertEquals(2, gpParsed.getStringToIntOrThrow("b"));
        assertEquals(2, gpParsed.getIntToStringCount());
        assertEquals("ten", gpParsed.getIntToStringOrThrow(10));
        assertEquals("twenty", gpParsed.getIntToStringOrThrow(20));
        assertEquals(1, gpParsed.getStringToMsgCount());
        assertEquals(1, gpParsed.getStringToMsgOrThrow("msg1").getId());
        assertEquals("first", gpParsed.getStringToMsgOrThrow("msg1").getName());
        assertEquals(1, gpParsed.getStringToBytesCount());
        assertArrayEquals(new byte[]{1, 2, 3}, gpParsed.getStringToBytesOrThrow("data").toByteArray());
        assertEquals(1, gpParsed.getBoolToStringCount());
        assertEquals("TRUE", gpParsed.getBoolToStringOrThrow(true));
        assertEquals(1, gpParsed.getStringToDoubleCount());
        assertEquals(1.5, gpParsed.getStringToDoubleOrThrow("val"), 0.001);
        assertEquals("compat-test", gpParsed.getName());

        // Build with Google Protobuf
        MapsProtos.MapMessage gp = MapsProtos.MapMessage.newBuilder()
                .putStringToInt("x", 10)
                .putStringToInt("y", 20)
                .putIntToString(100, "hundred")
                .putStringToMsg("nested", MapsProtos.MapNestedValue.newBuilder().setId(99).setName("deep").build())
                .putStringToBytes("bin", ByteString.copyFrom(new byte[]{9, 8, 7}))
                .putBoolToString(false, "FALSE")
                .putStringToDouble("num", 2.5)
                .setName("gp-test")
                .build();

        byte[] gpBytes = gp.toByteArray();

        // Parse with LightProto
        MapMessage lpParsed = new MapMessage();
        lpParsed.parseFrom(gpBytes);
        assertEquals(2, lpParsed.getStringToIntCount());
        assertEquals(10, lpParsed.getStringToInt("x"));
        assertEquals(20, lpParsed.getStringToInt("y"));
        assertEquals(1, lpParsed.getIntToStringCount());
        assertEquals("hundred", lpParsed.getIntToString(100));
        assertEquals(1, lpParsed.getStringToMsgCount());
        assertEquals(99, lpParsed.getStringToMsg("nested").getId());
        assertEquals("deep", lpParsed.getStringToMsg("nested").getName());
        assertEquals(1, lpParsed.getStringToBytesCount());
        assertArrayEquals(new byte[]{9, 8, 7}, lpParsed.getStringToBytes("bin"));
        assertEquals(1, lpParsed.getBoolToStringCount());
        assertEquals("FALSE", lpParsed.getBoolToString(false));
        assertEquals(1, lpParsed.getStringToDoubleCount());
        assertEquals(2.5, lpParsed.getStringToDouble("num"), 0.001);
        assertEquals("gp-test", lpParsed.getName());
    }

    // --- Entries with an omitted key or value ---
    // A map entry is encoded as `message Entry { K key = 1; V value = 2; }`, so a key or
    // value missing from the wire reads as its default. protobuf-java always writes both,
    // so these entries are built by hand. Each test checks how protobuf-java reads the
    // entry, then that LightProto behaves like protobuf-java on it.

    @Test
    public void testOmittedStringValue() throws Exception {
        // int_to_string entry {key: 7}
        byte[] wire = {0x12, 0x02, 0x08, 0x07};
        assertEquals(Map.of(7, ""), MapsProtos.MapMessage.parseFrom(wire).getIntToStringMap());
        verifySameAsProtobuf(wire);
    }

    @Test
    public void testOmittedKeyWithStringValue() throws Exception {
        // int_to_string entry {value: "v"}
        byte[] wire = {0x12, 0x03, 0x12, 0x01, 'v'};
        assertEquals(Map.of(0, "v"), MapsProtos.MapMessage.parseFrom(wire).getIntToStringMap());
        verifySameAsProtobuf(wire);
    }

    @Test
    public void testOmittedBytesValue() throws Exception {
        // string_to_bytes entry {key: "k"}
        byte[] wire = {0x22, 0x03, 0x0A, 0x01, 'k'};
        assertEquals(Map.of("k", ByteString.EMPTY), MapsProtos.MapMessage.parseFrom(wire).getStringToBytesMap());
        verifySameAsProtobuf(wire);
    }

    @Test
    public void testOmittedKeyWithBytesValue() throws Exception {
        // string_to_bytes entry {value: "v"}
        byte[] wire = {0x22, 0x03, 0x12, 0x01, 'v'};
        assertEquals(Map.of("", ByteString.copyFromUtf8("v")),
                MapsProtos.MapMessage.parseFrom(wire).getStringToBytesMap());
        verifySameAsProtobuf(wire);
    }

    @Test
    public void testOmittedEnumValue() throws Exception {
        // string_to_enum entry {key: "k"}
        byte[] wire = {0x3A, 0x03, 0x0A, 0x01, 'k'};
        assertEquals(Map.of("k", MapsProtos.MapEnumValue.MAP_ENUM_ZERO),
                MapsProtos.MapMessage.parseFrom(wire).getStringToEnumMap());
        verifySameAsProtobuf(wire);
    }

    @Test
    public void testOmittedKeyWithEnumValue() throws Exception {
        // string_to_enum entry {value: MAP_ENUM_ONE}
        byte[] wire = {0x3A, 0x02, 0x10, 0x01};
        assertEquals(Map.of("", MapsProtos.MapEnumValue.MAP_ENUM_ONE),
                MapsProtos.MapMessage.parseFrom(wire).getStringToEnumMap());
        verifySameAsProtobuf(wire);
    }

    @Test
    public void testOmittedMessageValue() throws Exception {
        // string_to_msg entry {key: "k"}
        byte[] wire = {0x1A, 0x03, 0x0A, 0x01, 'k'};
        assertEquals(Map.of("k", MapsProtos.MapNestedValue.getDefaultInstance()),
                MapsProtos.MapMessage.parseFrom(wire).getStringToMsgMap());
        verifySameAsProtobuf(wire);
    }

    @Test
    public void testOmittedKeyWithMessageValue() throws Exception {
        // string_to_msg entry {value: {id: 5}}
        byte[] wire = {0x1A, 0x04, 0x12, 0x02, 0x08, 0x05};
        assertEquals(Map.of("", MapsProtos.MapNestedValue.newBuilder().setId(5).build()),
                MapsProtos.MapMessage.parseFrom(wire).getStringToMsgMap());
        verifySameAsProtobuf(wire);
    }

    @Test
    public void testOmittedKeyWithNumericValue() throws Exception {
        // string_to_int entry {value: 7}
        byte[] wire = {0x0A, 0x02, 0x10, 0x07};
        assertEquals(Map.of("", 7), MapsProtos.MapMessage.parseFrom(wire).getStringToIntMap());
        verifySameAsProtobuf(wire);
    }

    @Test
    public void testOmittedNumericKeyAndValues() throws Exception {
        byte[] wire = {
                0x0A, 0x03, 0x0A, 0x01, 'k', // string_to_int entry {key: "k"}
                0x2A, 0x03, 0x12, 0x01, 'v', // bool_to_string entry {value: "v"}
                0x32, 0x03, 0x0A, 0x01, 'd', // string_to_double entry {key: "d"}
        };
        MapsProtos.MapMessage pb = MapsProtos.MapMessage.parseFrom(wire);
        assertEquals(Map.of("k", 0), pb.getStringToIntMap());
        assertEquals(Map.of(false, "v"), pb.getBoolToStringMap());
        assertEquals(Map.of("d", 0.0), pb.getStringToDoubleMap());
        verifySameAsProtobuf(wire);
    }

    @Test
    public void testOmittedKeyAndValue() throws Exception {
        // An empty entry in int_to_string, string_to_msg, string_to_bytes and string_to_enum
        byte[] wire = {0x12, 0x00, 0x1A, 0x00, 0x22, 0x00, 0x3A, 0x00};
        MapsProtos.MapMessage pb = MapsProtos.MapMessage.parseFrom(wire);
        assertEquals(Map.of(0, ""), pb.getIntToStringMap());
        assertEquals(Map.of("", MapsProtos.MapNestedValue.getDefaultInstance()), pb.getStringToMsgMap());
        assertEquals(Map.of("", ByteString.EMPTY), pb.getStringToBytesMap());
        assertEquals(Map.of("", MapsProtos.MapEnumValue.MAP_ENUM_ZERO), pb.getStringToEnumMap());
        verifySameAsProtobuf(wire);
    }

    // A message holding such an entry serializes larger than its parsed size, and so
    // does every message it is nested in: none of them may cache the parsed size.

    @Test
    public void testOmittedValueInNestedMessage() throws Exception {
        // inner { int_to_string entry {key: 7} }
        byte[] wire = {0x0A, 0x04, 0x12, 0x02, 0x08, 0x07};
        verifyHolderSameAsProtobuf(wire);
    }

    @Test
    public void testOmittedValueInMapMessageValue() throws Exception {
        // nested_maps entry {key: "a", value: {int_to_string entry {key: 7}}}
        byte[] wire = {0x12, 0x09, 0x0A, 0x01, 'a', 0x12, 0x04, 0x12, 0x02, 0x08, 0x07};
        verifyHolderSameAsProtobuf(wire);
    }

    // --- Entries with a repeated key or value ---
    // An entry that repeats its key or value reads as the last occurrence, in protobuf-java
    // too, and is serialized with each of them once: smaller than its parsed size.

    @Test
    public void testRepeatedStringKey() throws Exception {
        // string_to_int entry {key: "a", key: "k", value: 7}
        byte[] wire = {0x0A, 0x08, 0x0A, 0x01, 'a', 0x0A, 0x01, 'k', 0x10, 0x07};
        assertEquals(Map.of("k", 7), MapsProtos.MapMessage.parseFrom(wire).getStringToIntMap());
        verifySameAsProtobuf(wire);
    }

    @Test
    public void testRepeatedStringValue() throws Exception {
        // int_to_string entry {key: 1, value: "a", value: "b"}
        byte[] wire = {0x12, 0x08, 0x08, 0x01, 0x12, 0x01, 'a', 0x12, 0x01, 'b'};
        assertEquals(Map.of(1, "b"), MapsProtos.MapMessage.parseFrom(wire).getIntToStringMap());
        verifySameAsProtobuf(wire);
    }

    @Test
    public void testRepeatedMessageValue() throws Exception {
        // string_to_msg entry {key: "k", value: {id: 1}, value: {id: 2}}. Both values set the
        // same field, so protobuf-java, which merges them, also reads {id: 2}.
        byte[] wire = {0x1A, 0x0B, 0x0A, 0x01, 'k', 0x12, 0x02, 0x08, 0x01, 0x12, 0x02, 0x08, 0x02};
        assertEquals(Map.of("k", MapsProtos.MapNestedValue.newBuilder().setId(2).build()),
                MapsProtos.MapMessage.parseFrom(wire).getStringToMsgMap());
        verifySameAsProtobuf(wire);
    }

    @Test
    public void testRepeatedKeysAndValues() throws Exception {
        byte[] wire = {
                // string_to_int entry {key: "k", value: 1, value: 2}
                0x0A, 0x07, 0x0A, 0x01, 'k', 0x10, 0x01, 0x10, 0x02,
                // string_to_bytes entry {key: "k", value: "a", value: "bc"}
                0x22, 0x0A, 0x0A, 0x01, 'k', 0x12, 0x01, 'a', 0x12, 0x02, 'b', 'c',
                // bool_to_string entry {key: true, key: false, value: "v"}
                0x2A, 0x07, 0x08, 0x01, 0x08, 0x00, 0x12, 0x01, 'v',
                // string_to_enum entry {key: "e", value: MAP_ENUM_ONE, value: MAP_ENUM_ZERO}
                0x3A, 0x07, 0x0A, 0x01, 'e', 0x10, 0x01, 0x10, 0x00,
        };
        MapsProtos.MapMessage pb = MapsProtos.MapMessage.parseFrom(wire);
        assertEquals(Map.of("k", 2), pb.getStringToIntMap());
        assertEquals(Map.of("k", ByteString.copyFromUtf8("bc")), pb.getStringToBytesMap());
        assertEquals(Map.of(false, "v"), pb.getBoolToStringMap());
        assertEquals(Map.of("e", MapsProtos.MapEnumValue.MAP_ENUM_ZERO), pb.getStringToEnumMap());
        verifySameAsProtobuf(wire);
    }

    @Test
    public void testRepeatedKeyInNestedMessage() throws Exception {
        // inner { string_to_int entry {key: "a", key: "k", value: 7} }
        byte[] wire = {0x0A, 0x0A, 0x0A, 0x08, 0x0A, 0x01, 'a', 0x0A, 0x01, 'k', 0x10, 0x07};
        verifyHolderSameAsProtobuf(wire);
    }

    // --- Helpers ---

    private byte[] serialize(MapMessage msg) {
        int size = msg.getSerializedSize();
        bb1.writerIndex(0);
        msg.writeTo(bb1);
        byte[] result = new byte[size];
        System.arraycopy(b1, 0, result, 0, size);
        return result;
    }

    private void verifyRoundtrip(MapMessage original) throws Exception {
        byte[] serialized = serialize(original);

        MapMessage parsed = new MapMessage();
        parsed.parseFrom(serialized);

        assertEquals(original.getStringToIntCount(), parsed.getStringToIntCount());
        assertEquals(original.getIntToStringCount(), parsed.getIntToStringCount());
        assertEquals(original.getStringToMsgCount(), parsed.getStringToMsgCount());
        assertEquals(original.getStringToBytesCount(), parsed.getStringToBytesCount());
        assertEquals(original.getBoolToStringCount(), parsed.getBoolToStringCount());
        assertEquals(original.getStringToDoubleCount(), parsed.getStringToDoubleCount());

        // Verify map contents via forEach
        Map<String, Integer> origStringToInt = new HashMap<>();
        original.forEachStringToInt(origStringToInt::put);
        Map<String, Integer> parsedStringToInt = new HashMap<>();
        parsed.forEachStringToInt(parsedStringToInt::put);
        assertEquals(origStringToInt, parsedStringToInt);

        Map<Integer, String> origIntToString = new HashMap<>();
        original.forEachIntToString(origIntToString::put);
        Map<Integer, String> parsedIntToString = new HashMap<>();
        parsed.forEachIntToString(parsedIntToString::put);
        assertEquals(origIntToString, parsedIntToString);

        assertEquals(original.hasName(), parsed.hasName());
        if (original.hasName()) {
            assertEquals(original.getName(), parsed.getName());
        }
    }

    private static MapMessage parse(byte[] wire) {
        MapMessage lp = new MapMessage();
        lp.parseFrom(wire);
        return lp;
    }

    /** Parses {@code wire} into a MapMessageHolder and checks it serializes like protobuf-java. */
    private static void verifyHolderSameAsProtobuf(byte[] wire) throws Exception {
        byte[] expected = MapsProtos.MapMessageHolder.parseFrom(wire).toByteArray();
        MapMessageHolder lp = new MapMessageHolder();
        lp.parseFrom(wire);
        assertEquals(expected.length, lp.getSerializedSize());
        assertArrayEquals(expected, lp.toByteArray());
    }

    /**
     * Parses {@code wire} with LightProto and protobuf-java, and checks that LightProto behaves
     * like protobuf-java: same entries through get(), the counts and forEach(), same serialized
     * bytes, JSON and text format, same equals() results, also after copyFrom(), materialize()
     * and when parsing into a reused instance.
     */
    private void verifySameAsProtobuf(byte[] wire) throws Exception {
        MapsProtos.MapMessage pb = MapsProtos.MapMessage.parseFrom(wire);

        // Each check parses the wire again, so that it starts from the freshly parsed state
        assertSameEntries(pb, parse(wire));
        assertEquals(pb, toProtobuf(parse(wire)));
        assertEquals(pb.getSerializedSize(), parse(wire).getSerializedSize());
        assertArrayEquals(pb.toByteArray(), serialize(parse(wire)));
        assertEquals(JsonFormat.printer().omittingInsignificantWhitespace().print(pb), parse(wire).toJson());
        assertEquals(TextFormat.printer().printToString(pb), parse(wire).toTextFormat());

        // Compared with the same entries set explicitly (protobuf-java holds a parsed entry
        // like a put() one, so pb stands for both), with different values, and with none
        MapsProtos.MapMessage changed = withChangedValues(pb);
        assertSameEquality(
                new MapsProtos.MapMessage[]{pb, pb, pb, changed, MapsProtos.MapMessage.getDefaultInstance()},
                new MapMessage[]{parse(wire), parse(wire), fromProtobuf(pb), fromProtobuf(changed), new MapMessage()});

        assertSameContent(pb, new MapMessage().copyFrom(parse(wire)));

        MapMessage materialized = parse(wire);
        materialized.materialize();
        assertSameContent(pb, materialized);

        // The pooled holders of a reused instance must not leak previous entries
        MapMessage reused = parse(NON_DEFAULT_ENTRIES);
        reused.parseFrom(wire);
        assertSameContent(pb, reused);
    }

    /** Checks the entries and the serialized form against protobuf-java. */
    private void assertSameContent(MapsProtos.MapMessage pb, MapMessage lp) {
        assertSameEntries(pb, lp);
        assertEquals(pb, toProtobuf(lp));
        assertEquals(pb.getSerializedSize(), lp.getSerializedSize());
        assertArrayEquals(pb.toByteArray(), serialize(lp));
    }

    /** Checks that LightProto has protobuf-java's entries, through the counts and get(). */
    private static void assertSameEntries(MapsProtos.MapMessage pb, MapMessage lp) {
        assertEquals(pb.getStringToIntCount(), lp.getStringToIntCount());
        pb.getStringToIntMap().forEach((k, v) -> assertEquals(v, lp.getStringToInt(k)));
        assertEquals(pb.getIntToStringCount(), lp.getIntToStringCount());
        pb.getIntToStringMap().forEach((k, v) -> assertEquals(v, lp.getIntToString(k)));
        assertEquals(pb.getStringToMsgCount(), lp.getStringToMsgCount());
        pb.getStringToMsgMap().forEach((k, v) -> assertEquals(v, toProtobuf(lp.getStringToMsg(k))));
        assertEquals(pb.getStringToBytesCount(), lp.getStringToBytesCount());
        pb.getStringToBytesMap().forEach((k, v) -> assertEquals(v, ByteString.copyFrom(lp.getStringToBytes(k))));
        assertEquals(pb.getBoolToStringCount(), lp.getBoolToStringCount());
        pb.getBoolToStringMap().forEach((k, v) -> assertEquals(v, lp.getBoolToString(k)));
        assertEquals(pb.getStringToDoubleCount(), lp.getStringToDoubleCount());
        pb.getStringToDoubleMap().forEach((k, v) -> assertEquals(v, lp.getStringToDouble(k)));
        assertEquals(pb.getStringToEnumCount(), lp.getStringToEnumCount());
        pb.getStringToEnumMap().forEach((k, v) ->
                assertEquals(MapEnumValue.valueOf(v.getNumber()), lp.getStringToEnum(k)));
    }

    /**
     * Checks that LightProto's equals() gives protobuf-java's result for every pair of
     * corresponding messages, and that equal LightProto messages have the same hashCode().
     */
    private static void assertSameEquality(MapsProtos.MapMessage[] pbs, MapMessage[] lps) {
        for (int i = 0; i < pbs.length; i++) {
            for (int j = 0; j < pbs.length; j++) {
                assertEquals(pbs[i].equals(pbs[j]), lps[i].equals(lps[j]), "equals " + i + ", " + j);
                if (lps[i].equals(lps[j])) {
                    assertEquals(lps[i].hashCode(), lps[j].hashCode(), "hashCode " + i + ", " + j);
                }
            }
        }
    }

    /** Rebuilds the message with protobuf-java, reading its maps through forEach. */
    private static MapsProtos.MapMessage toProtobuf(MapMessage lp) {
        MapsProtos.MapMessage.Builder pb = MapsProtos.MapMessage.newBuilder();
        lp.forEachStringToInt(pb::putStringToInt);
        lp.forEachIntToString(pb::putIntToString);
        lp.forEachStringToMsg((k, v) -> pb.putStringToMsg(k, toProtobuf(v)));
        lp.forEachStringToBytes((k, v) -> pb.putStringToBytes(k, ByteString.copyFrom(v)));
        lp.forEachBoolToString(pb::putBoolToString);
        lp.forEachStringToDouble(pb::putStringToDouble);
        lp.forEachStringToEnum((k, v) -> pb.putStringToEnum(k, MapsProtos.MapEnumValue.forNumber(v.getValue())));
        if (lp.hasName()) {
            pb.setName(lp.getName());
        }
        return pb.build();
    }

    private static MapsProtos.MapNestedValue toProtobuf(MapNestedValue lp) {
        MapsProtos.MapNestedValue.Builder pb = MapsProtos.MapNestedValue.newBuilder();
        if (lp.hasId()) {
            pb.setId(lp.getId());
        }
        if (lp.hasName()) {
            pb.setName(lp.getName());
        }
        return pb.build();
    }

    /** Builds the LightProto message with protobuf-java's entries, set explicitly with put(). */
    private static MapMessage fromProtobuf(MapsProtos.MapMessage pb) {
        MapMessage lp = new MapMessage();
        pb.getStringToIntMap().forEach(lp::putStringToInt);
        pb.getIntToStringMap().forEach(lp::putIntToString);
        pb.getStringToMsgMap().forEach((k, v) -> {
            MapNestedValue lpValue = lp.putStringToMsg(k);
            if (v.hasId()) {
                lpValue.setId(v.getId());
            }
            if (v.hasName()) {
                lpValue.setName(v.getName());
            }
        });
        pb.getStringToBytesMap().forEach((k, v) -> lp.putStringToBytes(k, v.toByteArray()));
        pb.getBoolToStringMap().forEach(lp::putBoolToString);
        pb.getStringToDoubleMap().forEach(lp::putStringToDouble);
        pb.getStringToEnumMap().forEach((k, v) -> lp.putStringToEnum(k, MapEnumValue.valueOf(v.getNumber())));
        if (pb.hasName()) {
            lp.setName(pb.getName());
        }
        return lp;
    }

    /** Returns the message with the same keys and every value changed. */
    private static MapsProtos.MapMessage withChangedValues(MapsProtos.MapMessage pb) {
        MapsProtos.MapMessage.Builder changed = MapsProtos.MapMessage.newBuilder();
        pb.getStringToIntMap().forEach((k, v) -> changed.putStringToInt(k, v + 1));
        pb.getIntToStringMap().forEach((k, v) -> changed.putIntToString(k, v + "x"));
        pb.getStringToMsgMap().forEach((k, v) -> changed.putStringToMsg(k, v.toBuilder().setId(v.getId() + 1).build()));
        pb.getStringToBytesMap().forEach((k, v) -> changed.putStringToBytes(k, v.concat(ByteString.copyFromUtf8("x"))));
        pb.getBoolToStringMap().forEach((k, v) -> changed.putBoolToString(k, v + "x"));
        pb.getStringToDoubleMap().forEach((k, v) -> changed.putStringToDouble(k, v + 1));
        pb.getStringToEnumMap().forEach((k, v) -> changed.putStringToEnum(k,
                v == MapsProtos.MapEnumValue.MAP_ENUM_ZERO
                        ? MapsProtos.MapEnumValue.MAP_ENUM_ONE : MapsProtos.MapEnumValue.MAP_ENUM_ZERO));
        return changed.build();
    }
}
