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
}
