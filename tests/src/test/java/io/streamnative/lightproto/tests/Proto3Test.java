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

import static org.junit.jupiter.api.Assertions.*;

public class Proto3Test {

    private byte[] b1 = new byte[4096];
    private ByteBuf bb1 = Unpooled.wrappedBuffer(b1);

    @BeforeEach
    public void setup() {
        bb1.clear();
    }

    // --- Implicit presence: default values ---

    @Test
    public void testDefaultValues() {
        Proto3Message msg = new Proto3Message();
        assertEquals(0, msg.getIntField());
        assertEquals(0L, msg.getLongField());
        assertEquals(0.0f, msg.getFloatField());
        assertEquals(0.0, msg.getDoubleField());
        assertFalse(msg.isBoolField());
        assertEquals("", msg.getStringField());
        assertArrayEquals(new byte[0], msg.getBytesField());
        assertEquals(Proto3Enum.DEFAULT, msg.getEnumField());
    }

    @Test
    public void testDefaultSerializesToEmpty() {
        Proto3Message msg = new Proto3Message();
        assertEquals(0, msg.getSerializedSize());
    }

    // --- Implicit presence: non-default values serialize ---

    @Test
    public void testNonDefaultValuesRoundtrip() {
        Proto3Message msg = new Proto3Message();
        msg.setIntField(42);
        msg.setLongField(123456789L);
        msg.setFloatField(3.14f);
        msg.setDoubleField(2.718);
        msg.setBoolField(true);
        msg.setStringField("hello");
        msg.setBytesField(new byte[]{1, 2, 3});
        msg.setEnumField(Proto3Enum.VALUE_A);

        assertTrue(msg.getSerializedSize() > 0);

        byte[] serialized = serialize(msg);
        Proto3Message parsed = new Proto3Message();
        parsed.parseFrom(serialized);

        assertEquals(42, parsed.getIntField());
        assertEquals(123456789L, parsed.getLongField());
        assertEquals(3.14f, parsed.getFloatField());
        assertEquals(2.718, parsed.getDoubleField(), 0.001);
        assertTrue(parsed.isBoolField());
        assertEquals("hello", parsed.getStringField());
        assertArrayEquals(new byte[]{1, 2, 3}, parsed.getBytesField());
        assertEquals(Proto3Enum.VALUE_A, parsed.getEnumField());
    }

    @Test
    public void testClearResetsToDefaults() {
        Proto3Message msg = new Proto3Message();
        msg.setIntField(42);
        msg.setStringField("hello");
        msg.setBoolField(true);
        msg.setEnumField(Proto3Enum.VALUE_B);

        msg.clear();

        assertEquals(0, msg.getIntField());
        assertEquals("", msg.getStringField());
        assertFalse(msg.isBoolField());
        assertEquals(Proto3Enum.DEFAULT, msg.getEnumField());
        assertEquals(0, msg.getSerializedSize());
    }

    // --- Explicit presence (optional keyword) ---

    @Test
    public void testOptionalFieldPresence() {
        Proto3Message msg = new Proto3Message();
        assertFalse(msg.hasOptInt());
        assertFalse(msg.hasOptString());

        msg.setOptInt(0);
        assertTrue(msg.hasOptInt());
        assertEquals(0, msg.getOptInt());

        msg.setOptString("");
        assertTrue(msg.hasOptString());
        assertEquals("", msg.getOptString());
    }

    @Test
    public void testOptionalFieldSerializesDefaultValue() {
        Proto3Message msg = new Proto3Message();
        msg.setOptInt(0);

        // Should serialize even though value is 0, because has() is true
        assertTrue(msg.getSerializedSize() > 0);

        byte[] serialized = serialize(msg);
        Proto3Message parsed = new Proto3Message();
        parsed.parseFrom(serialized);
        assertTrue(parsed.hasOptInt());
        assertEquals(0, parsed.getOptInt());
    }

    @Test
    public void testClearOptionalField() {
        Proto3Message msg = new Proto3Message();
        msg.setOptInt(42);
        assertTrue(msg.hasOptInt());

        msg.clearOptInt();
        assertFalse(msg.hasOptInt());
    }

    // --- Message field (always has presence in proto3) ---

    @Test
    public void testMessageFieldPresence() {
        Proto3Message msg = new Proto3Message();
        assertFalse(msg.hasNested());

        msg.setNested().setLabel("test").setValue(42);
        assertTrue(msg.hasNested());
        assertEquals("test", msg.getNested().getLabel());
        assertEquals(42, msg.getNested().getValue());

        byte[] serialized = serialize(msg);
        Proto3Message parsed = new Proto3Message();
        parsed.parseFrom(serialized);
        assertTrue(parsed.hasNested());
        assertEquals("test", parsed.getNested().getLabel());
        assertEquals(42, parsed.getNested().getValue());
    }

    // --- Oneof ---

    @Test
    public void testOneofInProto3() {
        Proto3Message msg = new Proto3Message();
        assertEquals(Proto3Message.TestOneofCase.NOT_SET, msg.getTestOneofCase());

        msg.setOneofString("hello");
        assertTrue(msg.hasOneofString());
        assertFalse(msg.hasOneofInt());
        assertEquals(Proto3Message.TestOneofCase.ONEOF_STRING, msg.getTestOneofCase());
        assertEquals("hello", msg.getOneofString());

        // Switching to another oneof member clears the first
        msg.setOneofInt(42);
        assertFalse(msg.hasOneofString());
        assertTrue(msg.hasOneofInt());
        assertEquals(Proto3Message.TestOneofCase.ONEOF_INT, msg.getTestOneofCase());
        assertEquals(42, msg.getOneofInt());

        byte[] serialized = serialize(msg);
        Proto3Message parsed = new Proto3Message();
        parsed.parseFrom(serialized);
        assertTrue(parsed.hasOneofInt());
        assertEquals(42, parsed.getOneofInt());
        assertEquals(Proto3Message.TestOneofCase.ONEOF_INT, parsed.getTestOneofCase());
    }

    // --- Repeated packed ---

    @Test
    public void testPackedRepeatedInts() {
        Proto3Message msg = new Proto3Message();
        msg.addPackedInt(1);
        msg.addPackedInt(2);
        msg.addPackedInt(3);

        assertEquals(3, msg.getPackedIntsCount());
        assertEquals(1, msg.getPackedIntAt(0));
        assertEquals(2, msg.getPackedIntAt(1));
        assertEquals(3, msg.getPackedIntAt(2));

        byte[] serialized = serialize(msg);
        Proto3Message parsed = new Proto3Message();
        parsed.parseFrom(serialized);
        assertEquals(3, parsed.getPackedIntsCount());
        assertEquals(1, parsed.getPackedIntAt(0));
        assertEquals(2, parsed.getPackedIntAt(1));
        assertEquals(3, parsed.getPackedIntAt(2));
    }

    @Test
    public void testPackedRepeatedEnums() {
        Proto3Message msg = new Proto3Message();
        msg.addPackedEnum(Proto3Enum.VALUE_A);
        msg.addPackedEnum(Proto3Enum.VALUE_B);

        assertEquals(2, msg.getPackedEnumsCount());
        assertEquals(Proto3Enum.VALUE_A, msg.getPackedEnumAt(0));
        assertEquals(Proto3Enum.VALUE_B, msg.getPackedEnumAt(1));

        byte[] serialized = serialize(msg);
        Proto3Message parsed = new Proto3Message();
        parsed.parseFrom(serialized);
        assertEquals(2, parsed.getPackedEnumsCount());
        assertEquals(Proto3Enum.VALUE_A, parsed.getPackedEnumAt(0));
        assertEquals(Proto3Enum.VALUE_B, parsed.getPackedEnumAt(1));
    }

    // --- Map ---

    @Test
    public void testMapInProto3() {
        Proto3Message msg = new Proto3Message();
        msg.putStringToInt("a", 1);
        msg.putStringToInt("b", 2);

        assertEquals(2, msg.getStringToIntCount());
        assertEquals(1, msg.getStringToInt("a"));
        assertEquals(2, msg.getStringToInt("b"));

        byte[] serialized = serialize(msg);
        Proto3Message parsed = new Proto3Message();
        parsed.parseFrom(serialized);
        assertEquals(2, parsed.getStringToIntCount());
        assertEquals(1, parsed.getStringToInt("a"));
        assertEquals(2, parsed.getStringToInt("b"));
    }

    // --- CopyFrom ---

    @Test
    public void testCopyFrom() {
        Proto3Message src = new Proto3Message();
        src.setIntField(42);
        src.setStringField("hello");
        src.setBoolField(true);
        src.setOptInt(99);
        src.setOneofString("oneof");
        src.addPackedInt(1);
        src.addPackedInt(2);
        src.putStringToInt("k", 100);

        Proto3Message dst = new Proto3Message();
        dst.copyFrom(src);

        assertEquals(42, dst.getIntField());
        assertEquals("hello", dst.getStringField());
        assertTrue(dst.isBoolField());
        assertTrue(dst.hasOptInt());
        assertEquals(99, dst.getOptInt());
        assertTrue(dst.hasOneofString());
        assertEquals("oneof", dst.getOneofString());
        assertEquals(2, dst.getPackedIntsCount());
        assertEquals(1, dst.getPackedIntAt(0));
        assertEquals(2, dst.getPackedIntAt(1));
        assertEquals(1, dst.getStringToIntCount());
        assertEquals(100, dst.getStringToInt("k"));
    }

    // --- Cross-format wire compatibility: LightProto -> protobuf-java ---

    @Test
    public void testLightProtoToProtobuf() throws Exception {
        Proto3Message lp = new Proto3Message();
        lp.setIntField(42);
        lp.setLongField(123456789L);
        lp.setFloatField(3.14f);
        lp.setDoubleField(2.718);
        lp.setBoolField(true);
        lp.setStringField("hello");
        lp.setBytesField(new byte[]{1, 2, 3});
        lp.setEnumField(Proto3Enum.VALUE_A);
        lp.setOptInt(99);
        lp.setOptString("opt");
        lp.setNested().setLabel("nested").setValue(10);
        lp.setOneofString("oneof-val");
        lp.addPackedInt(100);
        lp.addPackedInt(200);
        lp.addPackedEnum(Proto3Enum.VALUE_B);
        lp.putStringToInt("key", 42);

        byte[] lpBytes = serialize(lp);

        Proto3Protos.Proto3Message gp = Proto3Protos.Proto3Message.parseFrom(lpBytes);
        assertEquals(42, gp.getIntField());
        assertEquals(123456789L, gp.getLongField());
        assertEquals(3.14f, gp.getFloatField());
        assertEquals(2.718, gp.getDoubleField(), 0.001);
        assertTrue(gp.getBoolField());
        assertEquals("hello", gp.getStringField());
        assertArrayEquals(new byte[]{1, 2, 3}, gp.getBytesField().toByteArray());
        assertEquals(Proto3Protos.Proto3Enum.VALUE_A, gp.getEnumField());
        assertTrue(gp.hasOptInt());
        assertEquals(99, gp.getOptInt());
        assertTrue(gp.hasOptString());
        assertEquals("opt", gp.getOptString());
        assertTrue(gp.hasNested());
        assertEquals("nested", gp.getNested().getLabel());
        assertEquals(10, gp.getNested().getValue());
        assertEquals("oneof-val", gp.getOneofString());
        assertEquals(Proto3Protos.Proto3Message.TestOneofCase.ONEOF_STRING, gp.getTestOneofCase());
        assertEquals(2, gp.getPackedIntsCount());
        assertEquals(100, gp.getPackedInts(0));
        assertEquals(200, gp.getPackedInts(1));
        assertEquals(1, gp.getPackedEnumsCount());
        assertEquals(Proto3Protos.Proto3Enum.VALUE_B, gp.getPackedEnums(0));
        assertEquals(1, gp.getStringToIntCount());
        assertEquals(42, gp.getStringToIntOrThrow("key"));
    }

    // --- Cross-format wire compatibility: protobuf-java -> LightProto ---

    @Test
    public void testProtobufToLightProto() throws Exception {
        Proto3Protos.Proto3Message gp = Proto3Protos.Proto3Message.newBuilder()
                .setIntField(42)
                .setLongField(123456789L)
                .setFloatField(3.14f)
                .setDoubleField(2.718)
                .setBoolField(true)
                .setStringField("hello")
                .setBytesField(ByteString.copyFrom(new byte[]{1, 2, 3}))
                .setEnumField(Proto3Protos.Proto3Enum.VALUE_A)
                .setOptInt(99)
                .setOptString("opt")
                .setNested(Proto3Protos.Proto3Nested.newBuilder().setLabel("nested").setValue(10))
                .setOneofString("oneof-val")
                .addPackedInts(100)
                .addPackedInts(200)
                .addPackedEnums(Proto3Protos.Proto3Enum.VALUE_B)
                .putStringToInt("key", 42)
                .build();

        byte[] gpBytes = gp.toByteArray();

        Proto3Message lp = new Proto3Message();
        lp.parseFrom(gpBytes);
        assertEquals(42, lp.getIntField());
        assertEquals(123456789L, lp.getLongField());
        assertEquals(3.14f, lp.getFloatField());
        assertEquals(2.718, lp.getDoubleField(), 0.001);
        assertTrue(lp.isBoolField());
        assertEquals("hello", lp.getStringField());
        assertArrayEquals(new byte[]{1, 2, 3}, lp.getBytesField());
        assertEquals(Proto3Enum.VALUE_A, lp.getEnumField());
        assertTrue(lp.hasOptInt());
        assertEquals(99, lp.getOptInt());
        assertTrue(lp.hasOptString());
        assertEquals("opt", lp.getOptString());
        assertTrue(lp.hasNested());
        assertEquals("nested", lp.getNested().getLabel());
        assertEquals(10, lp.getNested().getValue());
        assertTrue(lp.hasOneofString());
        assertEquals("oneof-val", lp.getOneofString());
        assertEquals(Proto3Message.TestOneofCase.ONEOF_STRING, lp.getTestOneofCase());
        assertEquals(2, lp.getPackedIntsCount());
        assertEquals(100, lp.getPackedIntAt(0));
        assertEquals(200, lp.getPackedIntAt(1));
        assertEquals(1, lp.getPackedEnumsCount());
        assertEquals(Proto3Enum.VALUE_B, lp.getPackedEnumAt(0));
        assertEquals(1, lp.getStringToIntCount());
        assertEquals(42, lp.getStringToInt("key"));
    }

    // --- Cross-format: default values roundtrip ---

    @Test
    public void testDefaultValuesCompatibility() throws Exception {
        // Default Proto3Message serialized by protobuf-java should be empty
        Proto3Protos.Proto3Message gpDefault = Proto3Protos.Proto3Message.getDefaultInstance();
        assertEquals(0, gpDefault.getSerializedSize());

        // Default Proto3Message serialized by LightProto should also be empty
        Proto3Message lpDefault = new Proto3Message();
        assertEquals(0, lpDefault.getSerializedSize());

        // Empty bytes from protobuf-java parsed by LightProto
        byte[] gpEmpty = gpDefault.toByteArray();
        Proto3Message lpFromGp = new Proto3Message();
        lpFromGp.parseFrom(gpEmpty);
        assertEquals(0, lpFromGp.getIntField());
        assertEquals("", lpFromGp.getStringField());
        assertFalse(lpFromGp.isBoolField());
        assertEquals(Proto3Enum.DEFAULT, lpFromGp.getEnumField());

        // Empty bytes from LightProto parsed by protobuf-java
        byte[] lpEmpty = serialize(lpDefault);
        Proto3Protos.Proto3Message gpFromLp = Proto3Protos.Proto3Message.parseFrom(lpEmpty);
        assertEquals(0, gpFromLp.getIntField());
        assertEquals("", gpFromLp.getStringField());
        assertFalse(gpFromLp.getBoolField());
        assertEquals(Proto3Protos.Proto3Enum.DEFAULT, gpFromLp.getEnumField());
    }

    // --- Cross-format: byte-exact wire format ---

    @Test
    public void testByteExactWireFormat() throws Exception {
        Proto3Message lp = new Proto3Message();
        lp.setIntField(42);
        lp.setStringField("hello");
        lp.setOptInt(0);
        lp.addPackedInt(1);
        lp.addPackedInt(2);
        lp.addPackedInt(3);

        Proto3Protos.Proto3Message gp = Proto3Protos.Proto3Message.newBuilder()
                .setIntField(42)
                .setStringField("hello")
                .setOptInt(0)
                .addPackedInts(1)
                .addPackedInts(2)
                .addPackedInts(3)
                .build();

        byte[] lpBytes = serialize(lp);
        byte[] gpBytes = gp.toByteArray();

        assertEquals(gpBytes.length, lpBytes.length);
        assertArrayEquals(gpBytes, lpBytes);
    }

    // --- Helpers ---

    private byte[] serialize(Proto3Message msg) {
        int size = msg.getSerializedSize();
        bb1.writerIndex(0);
        msg.writeTo(bb1);
        byte[] result = new byte[size];
        System.arraycopy(b1, 0, result, 0, size);
        return result;
    }
}
