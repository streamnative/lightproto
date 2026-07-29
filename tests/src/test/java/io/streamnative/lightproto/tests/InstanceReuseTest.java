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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies that reusing a message instance across parse/clear cycles never exposes stale
 * data. Since clear() only resets presence state (not field values), every read path must
 * be guarded by presence: getters, equals/hashCode, copyFrom, serialization and materialize.
 */
public class InstanceReuseTest {

    private static byte[] bytes(AddressBook ab) {
        return ab.toByteArray();
    }

    private static AddressBook wideBook() {
        AddressBook ab = new AddressBook();
        Person p = ab.addPerson();
        p.setName("wide-name");
        p.setId(1);
        p.setEmail("wide@example.com");
        Person.PhoneNumber pn1 = p.addPhone();
        pn1.setNumber("111-111");
        pn1.setType(Person.PhoneType.WORK);
        Person.PhoneNumber pn2 = p.addPhone();
        pn2.setNumber("222-222");
        return ab;
    }

    private static AddressBook narrowBook() {
        AddressBook ab = new AddressBook();
        Person p = ab.addPerson();
        p.setName("narrow-name");
        p.setId(2);
        return ab;
    }

    @Test
    public void testWideThenNarrowParseReuse() {
        byte[] wide = bytes(wideBook());
        byte[] narrow = bytes(narrowBook());

        AddressBook reused = new AddressBook();
        reused.parseFrom(wide);
        // Force lazy string decode so a stale String is cached in the instance
        assertEquals("wide@example.com", reused.getPersonAt(0).getEmail());

        reused.parseFrom(narrow);
        Person p = reused.getPersonAt(0);
        assertEquals("narrow-name", p.getName());
        assertEquals(2, p.getId());
        assertFalse(p.hasEmail());
        assertEquals("", p.getEmail());
        assertEquals(0, p.getPhonesCount());

        // Re-serialization must be byte-identical to the narrow message
        assertArrayEquals(narrow, bytes(reused));

        // And the reused instance must be indistinguishable from a fresh parse
        AddressBook fresh = new AddressBook();
        fresh.parseFrom(narrow);
        assertEquals(fresh, reused);
        assertEquals(fresh.hashCode(), reused.hashCode());
    }

    @Test
    public void testEnumDefaultAfterReuse() {
        byte[] wide = bytes(wideBook());

        AddressBook reused = new AddressBook();
        reused.parseFrom(wide);
        assertEquals(Person.PhoneType.WORK, reused.getPersonAt(0).getPhoneAt(0).getType());

        // Second phone had no type: default (HOME) applies
        assertFalse(reused.getPersonAt(0).getPhoneAt(1).hasType());
        assertEquals(Person.PhoneType.HOME, reused.getPersonAt(0).getPhoneAt(1).getType());

        // Re-parse: first phone slot is pooled from the WORK phone; a phone without
        // a type must not inherit the stale WORK value
        AddressBook oneDefaultPhone = new AddressBook();
        Person p = oneDefaultPhone.addPerson();
        p.setName("n");
        p.setId(3);
        p.addPhone().setNumber("333-333");

        reused.parseFrom(bytes(oneDefaultPhone));
        Person.PhoneNumber pn = reused.getPersonAt(0).getPhoneAt(0);
        assertFalse(pn.hasType());
        assertEquals(Person.PhoneType.HOME, pn.getType());
    }

    @Test
    public void testProto3ImplicitPresenceReuse() {
        Proto3Message wide = new Proto3Message();
        wide.setIntField(42);
        wide.setLongField(43L);
        wide.setFloatField(1.5f);
        wide.setDoubleField(2.5);
        wide.setBoolField(true);
        wide.setStringField("hello");
        wide.setBytesField(new byte[] {1, 2, 3});
        wide.setEnumField(Proto3Enum.VALUE_B);
        byte[] wideBytes = wide.toByteArray();

        Proto3Message reused = new Proto3Message();
        reused.parseFrom(wideBytes);
        assertEquals(42, reused.getIntField());
        assertEquals("hello", reused.getStringField());

        // Parse an empty message into the same instance: every implicit field
        // must read back as its default and nothing must be serialized
        reused.parseFrom(new byte[0]);
        assertEquals(0, reused.getIntField());
        assertEquals(0L, reused.getLongField());
        assertEquals(0.0f, reused.getFloatField());
        assertEquals(0.0, reused.getDoubleField());
        assertFalse(reused.isBoolField());
        assertEquals("", reused.getStringField());
        assertEquals(0, reused.getBytesFieldSize());
        assertArrayEquals(new byte[0], reused.getBytesField());
        assertEquals(Proto3Enum.DEFAULT, reused.getEnumField());
        assertEquals(0, reused.toByteArray().length);

        Proto3Message fresh = new Proto3Message();
        assertEquals(fresh, reused);
        assertEquals(fresh.hashCode(), reused.hashCode());
    }

    @Test
    public void testProto3SetToDefaultEqualsUnset() {
        // proto3: explicitly setting an implicit-presence field to its default must be
        // indistinguishable from never setting it (not serialized, equals/hashCode agree)
        Proto3Message a = new Proto3Message();
        a.setIntField(0);
        a.setStringField("");
        Proto3Message b = new Proto3Message();

        assertEquals(0, a.toByteArray().length);
        assertEquals(b, a);
        assertEquals(a, b);
        assertEquals(b.hashCode(), a.hashCode());
    }

    @Test
    public void testStaleDecodedStringDropped() {
        S first = new S();
        first.setId("hello");
        S second = new S();
        second.setId("world");

        S reused = new S();
        reused.parseFrom(first.toByteArray());
        assertEquals("hello", reused.getId()); // caches decoded String

        reused.parseFrom(second.toByteArray());
        assertEquals("world", reused.getId());

        reused.parseFrom(new byte[0]);
        assertFalse(reused.hasId());
        assertEquals("", reused.getId());
    }

    @Test
    public void testClearFieldClearsChildMessage() {
        Frame f = new Frame();
        f.setName("f");
        f.setPoint().setX(5).setY(6).setZ(7);
        assertEquals(5, f.getPoint().getX());

        f.clearPoint();
        assertFalse(f.hasPoint());
        // The child returned after clearing the field must be fully cleared
        assertEquals(0, f.getPoint().getX());
        assertEquals(0, f.getPoint().getY());
        assertFalse(f.getPoint().hasZ());
        assertEquals(0, f.getPoint().getZ());
    }

    @Test
    public void testPooledRepeatedMessageClearedOnAdd() {
        AddressBook ab = wideBook();
        byte[] wide = bytes(ab);
        ab.parseFrom(wide); // pool holds fully-populated Person instances

        ab.clear();
        // addPerson() must hand out a cleared instance even when pooled
        Person p = ab.addPerson();
        assertFalse(p.hasName());
        assertEquals("", p.getName());
        assertFalse(p.hasEmail());
        assertEquals(0, p.getPhonesCount());
    }

    @Test
    public void testOneofReuse() {
        OneofMsg withString = new OneofMsg().setOneofString("str-value");
        OneofMsg withInt = new OneofMsg().setOneofInt(99);

        OneofMsg reused = new OneofMsg();
        reused.parseFrom(withString.toByteArray());
        assertEquals(OneofMsg.TestOneofCase.ONEOF_STRING, reused.getTestOneofCase());
        assertEquals("str-value", reused.getOneofString());

        reused.parseFrom(withInt.toByteArray());
        assertEquals(OneofMsg.TestOneofCase.ONEOF_INT, reused.getTestOneofCase());
        assertEquals(99, reused.getOneofInt());
        assertFalse(reused.hasOneofString());
        assertEquals("", reused.getOneofString());

        reused.parseFrom(new byte[0]);
        assertEquals(OneofMsg.TestOneofCase.NOT_SET, reused.getTestOneofCase());
        assertEquals(0, reused.getOneofInt());
        assertEquals("", reused.getOneofString());
    }

    @Test
    public void testMaterializeSkipsAbsentFields() {
        S withId = new S();
        withId.setId("some-id-value");

        S reused = new S();
        reused.parseFrom(withId.toByteArray());
        // Parse an empty message: the stale buffer index of `id` points into the old
        // (conceptually released) buffer and must not be dereferenced by materialize()
        reused.parseFrom(new byte[0]);
        reused.materialize();
        assertFalse(reused.hasId());
        assertEquals("", reused.getId());
    }

    @Test
    public void testMapReuse() {
        MapMessage m1 = new MapMessage();
        m1.putStringToInt("a", 1);
        m1.putStringToInt("b", 2);
        m1.putStringToInt("c", 3);
        MapNestedValue v = m1.putStringToMsg("k");
        v.setId(10);
        v.setName("nested-name");

        MapMessage reused = new MapMessage();
        reused.parseFrom(m1.toByteArray());
        assertEquals(3, reused.getStringToIntCount());
        assertEquals(2, reused.getStringToInt("b"));
        assertEquals("nested-name", reused.getStringToMsg("k").getName());

        // Smaller map into the same instance; the pooled nested value message must not
        // retain the stale `name` sub-field
        MapMessage m2 = new MapMessage();
        m2.putStringToInt("z", 26);
        m2.putStringToMsg("k").setId(20);

        reused.parseFrom(m2.toByteArray());
        assertEquals(1, reused.getStringToIntCount());
        assertEquals(26, reused.getStringToInt("z"));
        assertThrows(IllegalArgumentException.class, () -> reused.getStringToInt("a"));
        assertEquals(20, reused.getStringToMsg("k").getId());
        assertFalse(reused.getStringToMsg("k").hasName());
        assertEquals("", reused.getStringToMsg("k").getName());
    }

    @Test
    public void testCopyFromTreatsSetToDefaultAsUnset() {
        // proto3 merge semantics: a source field explicitly set to its default must not
        // overwrite a non-default target value (same as protobuf-java mergeFrom, and
        // consistent with the field not being serialized on the wire)
        Proto3Message src = new Proto3Message();
        src.setIntField(0);
        src.setStringField("");
        src.setBoolField(false);

        Proto3Message target = new Proto3Message();
        target.setIntField(7);
        target.setStringField("keep");
        target.setBoolField(true);
        target.copyFrom(src);
        assertEquals(7, target.getIntField());
        assertEquals("keep", target.getStringField());
        assertTrue(target.isBoolField());

        // Non-default source values do overwrite
        src.setIntField(5);
        target.copyFrom(src);
        assertEquals(5, target.getIntField());
        assertEquals("keep", target.getStringField());
    }

    @Test
    public void testCopyFromReusedInstance() {
        Proto3Message src = new Proto3Message();
        src.parseFrom(new Proto3Message().setIntField(5).setStringField("x").toByteArray());
        src.parseFrom(new byte[0]); // src now holds stale-but-guarded values

        Proto3Message target = new Proto3Message();
        target.setLongField(7); // must survive: copyFrom merges present fields
        target.copyFrom(src);
        assertEquals(0, target.getIntField());
        assertEquals("", target.getStringField());
        assertEquals(7L, target.getLongField());
    }
}
