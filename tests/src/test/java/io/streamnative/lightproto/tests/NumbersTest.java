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

import com.google.protobuf.CodedOutputStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class NumbersTest {

    private byte[] b1 = new byte[4096];
    private ByteBuf bb1 = Unpooled.wrappedBuffer(b1);

    private byte[] b2 = new byte[4096];
    private ByteBuf bb2 = Unpooled.wrappedBuffer(b2);

    @BeforeEach
    public void setup() {
        bb1.clear();
        Arrays.fill(b1, (byte) 0);

        bb2.clear();
        Arrays.fill(b2, (byte) 0);
    }

    @Test
    public void testEnumValue() throws Exception {
        assertEquals(Enum1.X1_0_VALUE, Enum1.X1_0.getValue());
        assertEquals(Enum1.X1_1_VALUE, Enum1.X1_1.getValue());
        assertEquals(Enum1.X1_2_VALUE, Enum1.X1_2.getValue());
    }

    @Test
    public void testEmpty() throws Exception {
        Numbers lpn = new Numbers();
        NumbersOuterClass.Numbers pbn = NumbersOuterClass.Numbers.newBuilder().build();
        verify(lpn, pbn);
    }

    private void verify(Numbers lpn, NumbersOuterClass.Numbers pbn) throws Exception {
        assertEquals(pbn.getSerializedSize(), lpn.getSerializedSize());

        lpn.writeTo(bb1);
        assertEquals(lpn.getSerializedSize(), bb1.readableBytes());

        pbn.writeTo(CodedOutputStream.newInstance(b2));

        assertArrayEquals(b1, b2);

        Numbers parsed = new Numbers();
        parsed.parseFrom(bb1, bb1.readableBytes());

        assertEquals(pbn.hasEnum1(), parsed.hasEnum1());
        assertEquals(pbn.hasEnum2(), parsed.hasEnum2());
        assertEquals(pbn.hasXBool(), parsed.hasXBool());
        assertEquals(pbn.hasXDouble(), parsed.hasXDouble());
        assertEquals(pbn.hasXFixed32(), parsed.hasXFixed32());
        assertEquals(pbn.hasXFixed64(), parsed.hasXFixed64());
        assertEquals(pbn.hasXFixed32(), parsed.hasXSfixed32());
        assertEquals(pbn.hasXSfixed64(), parsed.hasXSfixed64());
        assertEquals(pbn.hasXFloat(), parsed.hasXFloat());
        assertEquals(pbn.hasXInt32(), parsed.hasXInt32());
        assertEquals(pbn.hasXInt64(), parsed.hasXInt64());
        assertEquals(pbn.hasXSint32(), parsed.hasXSint32());
        assertEquals(pbn.hasXSint64(), parsed.hasXSint64());

        assertEquals(pbn.getEnum1().getNumber(), parsed.getEnum1().getValue());
        assertEquals(pbn.getEnum2().getNumber(), parsed.getEnum2().getValue());
        assertEquals(pbn.getXBool(), parsed.isXBool());
        assertEquals(pbn.getXDouble(), parsed.getXDouble());
        assertEquals(pbn.getXFixed32(), parsed.getXFixed32());
        assertEquals(pbn.getXFixed64(), parsed.getXFixed64());
        assertEquals(pbn.getXSfixed32(), parsed.getXSfixed32());
        assertEquals(pbn.getXSfixed64(), parsed.getXSfixed64());
        assertEquals(pbn.getXFloat(), parsed.getXFloat());
        assertEquals(pbn.getXInt32(), parsed.getXInt32());
        assertEquals(pbn.getXInt64(), parsed.getXInt64());
        assertEquals(pbn.getXSint32(), parsed.getXSint32());
        assertEquals(pbn.getXSint64(), parsed.getXSint64());
    }

    @Test
    public void testNumberFields() throws Exception {
        Numbers lpn = new Numbers();
        NumbersOuterClass.Numbers.Builder pbn = NumbersOuterClass.Numbers.newBuilder();

        assertFalse(lpn.hasEnum1());
        assertFalse(lpn.hasEnum2());
        assertFalse(lpn.hasXBool());
        assertFalse(lpn.hasXDouble());
        assertFalse(lpn.hasXFixed32());
        assertFalse(lpn.hasXFixed64());
        assertFalse(lpn.hasXSfixed32());
        assertFalse(lpn.hasXSfixed64());
        assertFalse(lpn.hasXFloat());
        assertFalse(lpn.hasXInt32());
        assertFalse(lpn.hasXInt64());
        assertFalse(lpn.hasXInt32());
        assertFalse(lpn.hasXUint64());
        assertFalse(lpn.hasXUint32());
        assertFalse(lpn.hasXSint32());
        assertFalse(lpn.hasXSint64());

        // Optional fields should return default values when not set (matching Protobuf behavior)
        assertEquals(Enum1.valueOf(0), lpn.getEnum1());
        assertEquals(Numbers.Enum2.valueOf(0), lpn.getEnum2());
        assertEquals(false, lpn.isXBool());
        assertEquals(0.0, lpn.getXDouble());
        assertEquals(0, lpn.getXFixed32());
        assertEquals(0L, lpn.getXFixed64());
        assertEquals(0, lpn.getXSfixed32());
        assertEquals(0L, lpn.getXSfixed64());
        assertEquals(0.0f, lpn.getXFloat());
        assertEquals(0, lpn.getXInt32());
        assertEquals(0L, lpn.getXInt64());
        assertEquals(0, lpn.getXInt32());
        assertEquals(0L, lpn.getXUint64());
        assertEquals(0, lpn.getXUint32());
        assertEquals(0, lpn.getXSint32());
        assertEquals(0L, lpn.getXSint64());


        lpn.setEnum1(Enum1.X1_1);
        lpn.setEnum2(Numbers.Enum2.X2_1);
        lpn.setXBool(true);
        lpn.setXDouble(1.0);
        lpn.setXFixed32(2);
        lpn.setXFixed64(12345L);
        lpn.setXSfixed32(-2);
        lpn.setXSfixed64(-12345L);
        lpn.setXFloat(1.2f);
        lpn.setXInt32(4);
        lpn.setXInt64(126L);
        lpn.setXUint32(40);
        lpn.setXUint64(1260L);
        lpn.setXSint32(-11);
        lpn.setXSint64(-12L);

        pbn.setEnum1(NumbersOuterClass.Enum1.X1_1);
        pbn.setEnum2(NumbersOuterClass.Numbers.Enum2.X2_1);
        pbn.setXBool(true);
        pbn.setXDouble(1.0);
        pbn.setXFixed32(2);
        pbn.setXFixed64(12345L);
        pbn.setXSfixed32(-2);
        pbn.setXSfixed64(-12345L);
        pbn.setXFloat(1.2f);
        pbn.setXInt32(4);
        pbn.setXInt64(126L);
        pbn.setXUint32(40);
        pbn.setXUint64(1260L);
        pbn.setXSint32(-11);
        pbn.setXSint64(-12L);

        assertTrue(lpn.hasEnum1());
        assertTrue(lpn.hasEnum2());
        assertTrue(lpn.hasXBool());
        assertTrue(lpn.hasXDouble());
        assertTrue(lpn.hasXFixed32());
        assertTrue(lpn.hasXFixed64());
        assertTrue(lpn.hasXSfixed32());
        assertTrue(lpn.hasXSfixed64());
        assertTrue(lpn.hasXFloat());
        assertTrue(lpn.hasXInt32());
        assertTrue(lpn.hasXInt64());
        assertTrue(lpn.hasXSint32());
        assertTrue(lpn.hasXSint64());

        assertEquals(Enum1.X1_1, lpn.getEnum1());
        assertEquals(Numbers.Enum2.X2_1, lpn.getEnum2());
        assertEquals(true, lpn.isXBool());
        assertEquals(1.0, lpn.getXDouble());
        assertEquals(2, lpn.getXFixed32());
        assertEquals(12345L, lpn.getXFixed64());
        assertEquals(-2, lpn.getXSfixed32());
        assertEquals(-12345L, lpn.getXSfixed64());
        assertEquals(1.2f, lpn.getXFloat());
        assertEquals(4, lpn.getXInt32());
        assertEquals(126L, lpn.getXInt64());
        assertEquals(40, lpn.getXUint32());
        assertEquals(1260L, lpn.getXUint64());
        assertEquals(-11, lpn.getXSint32());
        assertEquals(-12L, lpn.getXSint64());

        verify(lpn, pbn.build());
    }

    @Test
    public void testClearResetsAllFieldsToDefaults() throws Exception {
        Numbers lpn = new Numbers();

        // Set all fields to non-default values
        lpn.setEnum1(Enum1.X1_2);
        lpn.setEnum2(Numbers.Enum2.X2_2);
        lpn.setXBool(true);
        lpn.setXDouble(3.14);
        lpn.setXFixed32(100);
        lpn.setXFixed64(200L);
        lpn.setXSfixed32(-100);
        lpn.setXSfixed64(-200L);
        lpn.setXFloat(2.71f);
        lpn.setXInt32(42);
        lpn.setXInt64(84L);
        lpn.setXUint32(55);
        lpn.setXUint64(110L);
        lpn.setXSint32(-33);
        lpn.setXSint64(-66L);

        // Verify all fields are set
        assertTrue(lpn.hasEnum1());
        assertTrue(lpn.hasXBool());
        assertTrue(lpn.hasXInt32());

        lpn.clear();

        // After clear, all has*() should return false
        assertFalse(lpn.hasEnum1());
        assertFalse(lpn.hasEnum2());
        assertFalse(lpn.hasXBool());
        assertFalse(lpn.hasXDouble());
        assertFalse(lpn.hasXFixed32());
        assertFalse(lpn.hasXFixed64());
        assertFalse(lpn.hasXSfixed32());
        assertFalse(lpn.hasXSfixed64());
        assertFalse(lpn.hasXFloat());
        assertFalse(lpn.hasXInt32());
        assertFalse(lpn.hasXInt64());
        assertFalse(lpn.hasXUint32());
        assertFalse(lpn.hasXUint64());
        assertFalse(lpn.hasXSint32());
        assertFalse(lpn.hasXSint64());

        // After clear, all getters should return default values (not old values)
        assertEquals(Enum1.valueOf(0), lpn.getEnum1());
        assertEquals(Numbers.Enum2.valueOf(0), lpn.getEnum2());
        assertEquals(false, lpn.isXBool());
        assertEquals(0.0, lpn.getXDouble());
        assertEquals(0, lpn.getXFixed32());
        assertEquals(0L, lpn.getXFixed64());
        assertEquals(0, lpn.getXSfixed32());
        assertEquals(0L, lpn.getXSfixed64());
        assertEquals(0.0f, lpn.getXFloat());
        assertEquals(0, lpn.getXInt32());
        assertEquals(0L, lpn.getXInt64());
        assertEquals(0, lpn.getXUint32());
        assertEquals(0L, lpn.getXUint64());
        assertEquals(0, lpn.getXSint32());
        assertEquals(0L, lpn.getXSint64());

        // Serialized size should be 0 after clear
        assertEquals(0, lpn.getSerializedSize());

        // Should serialize identically to a fresh empty protobuf message
        NumbersOuterClass.Numbers pbn = NumbersOuterClass.Numbers.newBuilder().build();
        verify(lpn, pbn);
    }
}
