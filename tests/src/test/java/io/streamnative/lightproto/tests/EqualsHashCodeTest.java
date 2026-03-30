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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class EqualsHashCodeTest {

    @Test
    public void testEmptyMessagesAreEqual() {
        Numbers n1 = new Numbers();
        Numbers n2 = new Numbers();
        assertEquals(n1, n2);
        assertEquals(n1.hashCode(), n2.hashCode());
    }

    @Test
    public void testSameValuesAreEqual() {
        Numbers n1 = new Numbers();
        n1.setXInt32(42);
        n1.setXInt64(100L);
        n1.setXFloat(3.14f);
        n1.setXDouble(2.718);
        n1.setXBool(true);
        n1.setEnum1(Enum1.X1_2);

        Numbers n2 = new Numbers();
        n2.setXInt32(42);
        n2.setXInt64(100L);
        n2.setXFloat(3.14f);
        n2.setXDouble(2.718);
        n2.setXBool(true);
        n2.setEnum1(Enum1.X1_2);

        assertEquals(n1, n2);
        assertEquals(n1.hashCode(), n2.hashCode());
    }

    @Test
    public void testDifferentValuesNotEqual() {
        Numbers n1 = new Numbers();
        n1.setXInt32(42);

        Numbers n2 = new Numbers();
        n2.setXInt32(43);

        assertNotEquals(n1, n2);
    }

    @Test
    public void testPresenceDifference() {
        Numbers n1 = new Numbers();
        n1.setXInt32(0);

        Numbers n2 = new Numbers();
        // n2 has no xInt32 set

        assertNotEquals(n1, n2);
    }

    @Test
    public void testStringFields() {
        S s1 = new S();
        s1.setId("hello");
        s1.addName("a");
        s1.addName("b");

        S s2 = new S();
        s2.setId("hello");
        s2.addName("a");
        s2.addName("b");

        assertEquals(s1, s2);
        assertEquals(s1.hashCode(), s2.hashCode());

        S s3 = new S();
        s3.setId("hello");
        s3.addName("a");
        s3.addName("c");

        assertNotEquals(s1, s3);
    }

    @Test
    public void testRepeatedStringCount() {
        S s1 = new S();
        s1.addName("a");

        S s2 = new S();
        s2.addName("a");
        s2.addName("b");

        assertNotEquals(s1, s2);
    }

    @Test
    public void testNestedMessages() {
        Frame f1 = new Frame();
        f1.setName("test");
        f1.setPoint().setX(1).setY(2);

        Frame f2 = new Frame();
        f2.setName("test");
        f2.setPoint().setX(1).setY(2);

        assertEquals(f1, f2);
        assertEquals(f1.hashCode(), f2.hashCode());

        Frame f3 = new Frame();
        f3.setName("test");
        f3.setPoint().setX(1).setY(3);

        assertNotEquals(f1, f3);
    }

    @Test
    public void testOneofFields() {
        OneofMsg m1 = new OneofMsg();
        m1.setOneofInt(42);

        OneofMsg m2 = new OneofMsg();
        m2.setOneofInt(42);

        assertEquals(m1, m2);
        assertEquals(m1.hashCode(), m2.hashCode());

        // Different oneof alternative
        OneofMsg m3 = new OneofMsg();
        m3.setOneofString("42");

        assertNotEquals(m1, m3);

        // Same alternative, different value
        OneofMsg m4 = new OneofMsg();
        m4.setOneofInt(99);

        assertNotEquals(m1, m4);
    }

    @Test
    public void testMapFields() {
        MapMessage m1 = new MapMessage();
        m1.putStringToInt("a", 1);
        m1.putStringToInt("b", 2);

        MapMessage m2 = new MapMessage();
        m2.putStringToInt("b", 2);
        m2.putStringToInt("a", 1);

        assertEquals(m1, m2);
        assertEquals(m1.hashCode(), m2.hashCode());

        MapMessage m3 = new MapMessage();
        m3.putStringToInt("a", 1);
        m3.putStringToInt("b", 3);

        assertNotEquals(m1, m3);
    }

    @Test
    public void testEqualityAfterSerializeDeserialize() {
        Numbers n1 = new Numbers();
        n1.setXInt32(42);
        n1.setXInt64(100L);
        n1.setXFloat(3.14f);
        n1.setXDouble(2.718);
        n1.setXBool(true);

        byte[] serialized = n1.toByteArray();
        Numbers n2 = new Numbers();
        n2.parseFrom(serialized);

        assertEquals(n1, n2);
        assertEquals(n1.hashCode(), n2.hashCode());
    }

    @Test
    public void testStringEqualityAfterSerializeDeserialize() {
        S s1 = new S();
        s1.setId("hello");
        s1.addName("world");

        byte[] serialized = s1.toByteArray();
        S s2 = new S();
        s2.parseFrom(serialized);

        assertEquals(s1, s2);
        assertEquals(s1.hashCode(), s2.hashCode());
    }

    @Test
    public void testCopyFromProducesEqual() {
        Numbers n1 = new Numbers();
        n1.setXInt32(42);
        n1.setXFloat(3.14f);
        n1.setXBool(true);
        n1.setEnum1(Enum1.X1_1);

        Numbers n2 = new Numbers();
        n2.copyFrom(n1);

        assertEquals(n1, n2);
        assertEquals(n1.hashCode(), n2.hashCode());
    }

    @Test
    public void testEqualsWithNull() {
        Numbers n = new Numbers();
        assertNotEquals(null, n);
    }

    @Test
    public void testEqualsReflexive() {
        Numbers n = new Numbers();
        n.setXInt32(42);
        assertEquals(n, n);
    }

    @Test
    public void testClearReturnsToEqual() {
        Numbers n1 = new Numbers();
        n1.setXInt32(42);

        Numbers n2 = new Numbers();
        n2.setXInt32(42);
        n2.clear();

        Numbers n3 = new Numbers();
        assertEquals(n2, n3);
    }

    @Test
    public void testBytesFields() {
        B b1 = new B();
        b1.setPayload(new byte[]{1, 2, 3});

        B b2 = new B();
        b2.setPayload(new byte[]{1, 2, 3});

        assertEquals(b1, b2);
        assertEquals(b1.hashCode(), b2.hashCode());

        B b3 = new B();
        b3.setPayload(new byte[]{1, 2, 4});

        assertNotEquals(b1, b3);
    }

    @Test
    public void testProto3ImplicitPresence() {
        Proto3Message m1 = new Proto3Message();
        Proto3Message m2 = new Proto3Message();

        // Both default — should be equal
        assertEquals(m1, m2);
        assertEquals(m1.hashCode(), m2.hashCode());

        m1.setStringField("test");
        assertNotEquals(m1, m2);

        m2.setStringField("test");
        assertEquals(m1, m2);
    }

    @Test
    public void testMapWithNestedMessageValues() {
        MapMessage m1 = new MapMessage();
        m1.putStringToMsg("key").setId(1).setName("val");

        MapMessage m2 = new MapMessage();
        m2.putStringToMsg("key").setId(1).setName("val");

        assertEquals(m1, m2);
        assertEquals(m1.hashCode(), m2.hashCode());

        MapMessage m3 = new MapMessage();
        m3.putStringToMsg("key").setId(2).setName("val");

        assertNotEquals(m1, m3);
    }
}
