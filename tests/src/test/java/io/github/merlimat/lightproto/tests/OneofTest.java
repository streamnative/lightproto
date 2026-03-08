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
package io.github.merlimat.lightproto.tests;

import com.google.protobuf.CodedOutputStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class OneofTest {

    private byte[] b1 = new byte[4096];
    private ByteBuf bb1 = Unpooled.wrappedBuffer(b1);

    private byte[] b2 = new byte[4096];
    private ByteBuf bb2 = Unpooled.wrappedBuffer(b2);

    @BeforeEach
    public void setup() {
        bb1.clear();
        bb2.clear();
    }

    @Test
    public void testEmptyOneof() {
        OneofMsg lp = new OneofMsg();
        assertFalse(lp.hasOneofInt());
        assertFalse(lp.hasOneofString());
        assertFalse(lp.hasOneofBool());
        assertFalse(lp.hasOneofMsg());
        assertFalse(lp.hasOneofDouble());
        assertFalse(lp.hasOneofBytes());
        assertEquals(OneofMsg.TestOneofCase.NOT_SET, lp.getTestOneofCase());
        assertEquals(OneofMsg.SecondOneofCase.NOT_SET, lp.getSecondOneofCase());
    }

    @Test
    public void testSetOneofInt() throws Exception {
        OneofMsg lp = new OneofMsg();
        lp.setOneofInt(42);

        assertTrue(lp.hasOneofInt());
        assertFalse(lp.hasOneofString());
        assertFalse(lp.hasOneofBool());
        assertFalse(lp.hasOneofMsg());
        assertEquals(42, lp.getOneofInt());
        assertEquals(OneofMsg.TestOneofCase.ONEOF_INT, lp.getTestOneofCase());

        // Roundtrip
        verifyRoundtrip(lp);
    }

    @Test
    public void testMutualExclusion() {
        OneofMsg lp = new OneofMsg();
        lp.setOneofInt(42);
        assertTrue(lp.hasOneofInt());
        assertEquals(OneofMsg.TestOneofCase.ONEOF_INT, lp.getTestOneofCase());

        // Setting another field in the same oneof clears the first
        lp.setOneofString("hello");
        assertFalse(lp.hasOneofInt());
        assertTrue(lp.hasOneofString());
        assertEquals("hello", lp.getOneofString());
        assertEquals(OneofMsg.TestOneofCase.ONEOF_STRING, lp.getTestOneofCase());

        // Setting bool clears string
        lp.setOneofBool(true);
        assertFalse(lp.hasOneofString());
        assertTrue(lp.hasOneofBool());
        assertTrue(lp.isOneofBool());
        assertEquals(OneofMsg.TestOneofCase.ONEOF_BOOL, lp.getTestOneofCase());
    }

    @Test
    public void testMultipleOneofsIndependent() throws Exception {
        OneofMsg lp = new OneofMsg();
        lp.setOneofInt(42);
        lp.setOneofDouble(3.14);

        assertTrue(lp.hasOneofInt());
        assertTrue(lp.hasOneofDouble());
        assertEquals(OneofMsg.TestOneofCase.ONEOF_INT, lp.getTestOneofCase());
        assertEquals(OneofMsg.SecondOneofCase.ONEOF_DOUBLE, lp.getSecondOneofCase());

        verifyRoundtrip(lp);
    }

    @Test
    public void testNonOneofFieldsUnaffected() throws Exception {
        OneofMsg lp = new OneofMsg();
        lp.setName("test");
        lp.setAfterField(99);
        lp.setOneofInt(42);

        assertTrue(lp.hasName());
        assertTrue(lp.hasAfterField());
        assertTrue(lp.hasOneofInt());
        assertEquals("test", lp.getName());
        assertEquals(99, lp.getAfterField());
        assertEquals(42, lp.getOneofInt());

        // Setting oneof doesn't affect non-oneof fields
        lp.setOneofString("hello");
        assertTrue(lp.hasName());
        assertTrue(lp.hasAfterField());
        assertEquals("test", lp.getName());
        assertEquals(99, lp.getAfterField());

        verifyRoundtrip(lp);
    }

    @Test
    public void testClearOneof() {
        OneofMsg lp = new OneofMsg();
        lp.setOneofInt(42);
        assertTrue(lp.hasOneofInt());

        lp.clearTestOneof();
        assertFalse(lp.hasOneofInt());
        assertEquals(OneofMsg.TestOneofCase.NOT_SET, lp.getTestOneofCase());
    }

    @Test
    public void testClearIndividualOneofField() {
        OneofMsg lp = new OneofMsg();
        lp.setOneofInt(42);
        assertTrue(lp.hasOneofInt());

        lp.clearOneofInt();
        assertFalse(lp.hasOneofInt());
        assertEquals(OneofMsg.TestOneofCase.NOT_SET, lp.getTestOneofCase());
    }

    @Test
    public void testOneofWithNestedMessage() throws Exception {
        OneofMsg lp = new OneofMsg();
        lp.setOneofMsg().setValue(100).setLabel("nested");

        assertTrue(lp.hasOneofMsg());
        assertFalse(lp.hasOneofInt());
        assertEquals(OneofMsg.TestOneofCase.ONEOF_MSG, lp.getTestOneofCase());
        assertEquals(100, lp.getOneofMsg().getValue());
        assertEquals("nested", lp.getOneofMsg().getLabel());

        verifyRoundtrip(lp);
    }

    @Test
    public void testNestedMessageClearedBySwitchingOneofField() {
        OneofMsg lp = new OneofMsg();
        lp.setOneofMsg().setValue(100).setLabel("nested");
        assertTrue(lp.hasOneofMsg());

        // Switching to int should clear the message
        lp.setOneofInt(42);
        assertFalse(lp.hasOneofMsg());
        assertTrue(lp.hasOneofInt());
        assertEquals(42, lp.getOneofInt());
    }

    @Test
    public void testOneofBytesField() throws Exception {
        OneofMsg lp = new OneofMsg();
        byte[] data = {1, 2, 3, 4, 5};
        lp.setOneofBytes(data);

        assertTrue(lp.hasOneofBytes());
        assertFalse(lp.hasOneofDouble());
        assertEquals(OneofMsg.SecondOneofCase.ONEOF_BYTES, lp.getSecondOneofCase());
        assertArrayEquals(data, lp.getOneofBytes());

        verifyRoundtrip(lp);
    }

    @Test
    public void testProtobufCompatibility() throws Exception {
        // Build with LightProto
        OneofMsg lp = new OneofMsg();
        lp.setName("compat-test");
        lp.setOneofString("oneof-value");
        lp.setOneofDouble(2.718);
        lp.setAfterField(77);

        int lpSize = lp.getSerializedSize();
        bb1.writerIndex(0);
        lp.writeTo(bb1);
        byte[] lpBytes = new byte[lpSize];
        System.arraycopy(b1, 0, lpBytes, 0, lpSize);

        // Build equivalent with Google Protobuf
        OneofProtos.OneofMsg.Builder gpb = OneofProtos.OneofMsg.newBuilder();
        gpb.setName("compat-test");
        gpb.setOneofString("oneof-value");
        gpb.setOneofDouble(2.718);
        gpb.setAfterField(77);

        byte[] gpBytes = gpb.build().toByteArray();

        assertEquals(gpBytes.length, lpSize);
        assertArrayEquals(gpBytes, lpBytes);

        // Parse LightProto bytes with Google Protobuf
        OneofProtos.OneofMsg gpParsed = OneofProtos.OneofMsg.parseFrom(lpBytes);
        assertEquals("compat-test", gpParsed.getName());
        assertEquals("oneof-value", gpParsed.getOneofString());
        assertEquals(2.718, gpParsed.getOneofDouble(), 0.001);
        assertEquals(77, gpParsed.getAfterField());

        // Parse Google Protobuf bytes with LightProto
        OneofMsg lpParsed = new OneofMsg();
        lpParsed.parseFrom(gpBytes);
        assertEquals("compat-test", lpParsed.getName());
        assertEquals("oneof-value", lpParsed.getOneofString());
        assertEquals(2.718, lpParsed.getOneofDouble(), 0.001);
        assertEquals(77, lpParsed.getAfterField());
    }

    @Test
    public void testProtobufCompatibilityWithNestedMessage() throws Exception {
        // Build with LightProto
        OneofMsg lp = new OneofMsg();
        lp.setOneofMsg().setValue(42).setLabel("test");

        int lpSize = lp.getSerializedSize();
        bb1.writerIndex(0);
        lp.writeTo(bb1);
        byte[] lpBytes = new byte[lpSize];
        System.arraycopy(b1, 0, lpBytes, 0, lpSize);

        // Build equivalent with Google Protobuf
        OneofProtos.OneofMsg.Builder gpb = OneofProtos.OneofMsg.newBuilder();
        gpb.setOneofMsg(OneofProtos.SubMessage.newBuilder().setValue(42).setLabel("test"));

        byte[] gpBytes = gpb.build().toByteArray();

        assertEquals(gpBytes.length, lpSize);
        assertArrayEquals(gpBytes, lpBytes);

        // Parse Google Protobuf bytes with LightProto
        OneofMsg lpParsed = new OneofMsg();
        lpParsed.parseFrom(gpBytes);
        assertTrue(lpParsed.hasOneofMsg());
        assertEquals(42, lpParsed.getOneofMsg().getValue());
        assertEquals("test", lpParsed.getOneofMsg().getLabel());
    }

    @Test
    public void testCopyFrom() {
        OneofMsg src = new OneofMsg();
        src.setName("source");
        src.setOneofString("copied");
        src.setOneofDouble(1.23);

        OneofMsg dst = new OneofMsg();
        dst.copyFrom(src);

        assertEquals("source", dst.getName());
        assertTrue(dst.hasOneofString());
        assertEquals("copied", dst.getOneofString());
        assertTrue(dst.hasOneofDouble());
        assertEquals(1.23, dst.getOneofDouble(), 0.001);
    }

    private void verifyRoundtrip(OneofMsg original) throws Exception {
        int size = original.getSerializedSize();
        bb1.writerIndex(0);
        original.writeTo(bb1);
        byte[] serialized = new byte[size];
        System.arraycopy(b1, 0, serialized, 0, size);

        OneofMsg parsed = new OneofMsg();
        parsed.parseFrom(serialized);

        // Verify all fields match
        assertEquals(original.hasName(), parsed.hasName());
        if (original.hasName()) {
            assertEquals(original.getName(), parsed.getName());
        }
        assertEquals(original.hasAfterField(), parsed.hasAfterField());
        if (original.hasAfterField()) {
            assertEquals(original.getAfterField(), parsed.getAfterField());
        }

        assertEquals(original.getTestOneofCase(), parsed.getTestOneofCase());
        assertEquals(original.getSecondOneofCase(), parsed.getSecondOneofCase());

        if (original.hasOneofInt()) assertEquals(original.getOneofInt(), parsed.getOneofInt());
        if (original.hasOneofString()) assertEquals(original.getOneofString(), parsed.getOneofString());
        if (original.hasOneofBool()) assertEquals(original.isOneofBool(), parsed.isOneofBool());
        if (original.hasOneofMsg()) {
            assertEquals(original.getOneofMsg().getValue(), parsed.getOneofMsg().getValue());
            if (original.getOneofMsg().hasLabel()) {
                assertEquals(original.getOneofMsg().getLabel(), parsed.getOneofMsg().getLabel());
            }
        }
        if (original.hasOneofDouble()) assertEquals(original.getOneofDouble(), parsed.getOneofDouble(), 0.001);
        if (original.hasOneofBytes()) assertArrayEquals(original.getOneofBytes(), parsed.getOneofBytes());
    }
}
