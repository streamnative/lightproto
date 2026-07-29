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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

/**
 * Verifies that writeTo() produces identical output for every supported target
 * buffer type: heap (in-place through the backing array), direct (composed in
 * scratch, bulk-transferred), and composite (previously crashed with
 * UnsupportedOperationException on array()).
 */
public class WriteTargetTypesTest {

    private static AddressBook sample() {
        AddressBook ab = new AddressBook();
        Person p = ab.addPerson();
        p.setName("write-target");
        p.setId(7);
        p.setEmail("wt@example.com");
        Person.PhoneNumber pn = p.addPhone();
        pn.setNumber("555-0199");
        pn.setType(Person.PhoneType.MOBILE);
        return ab;
    }

    private static byte[] drain(ByteBuf b) {
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        return out;
    }

    @Test
    public void testHeapTarget() {
        AddressBook ab = sample();
        byte[] expected = ab.toByteArray();
        ByteBuf b = Unpooled.buffer(4);  // undersized: forces ensureWritable growth
        assertEquals(expected.length, ab.writeTo(b));
        assertArrayEquals(expected, drain(b));
    }

    @Test
    public void testPooledHeapTargetWithArrayOffset() {
        AddressBook ab = sample();
        byte[] expected = ab.toByteArray();
        ByteBuf b = PooledByteBufAllocator.DEFAULT.heapBuffer(expected.length + 16);
        try {
            b.writeBytes(new byte[3]); // non-zero writerIndex
            ab.writeTo(b);
            b.skipBytes(3);
            assertArrayEquals(expected, drain(b));
        } finally {
            b.release();
        }
    }

    @Test
    public void testDirectTarget() {
        AddressBook ab = sample();
        byte[] expected = ab.toByteArray();
        ByteBuf b = PooledByteBufAllocator.DEFAULT.directBuffer(expected.length);
        try {
            ab.writeTo(b);
            assertArrayEquals(expected, drain(b));
        } finally {
            b.release();
        }
    }

    @Test
    public void testCompositeTarget() {
        AddressBook ab = sample();
        byte[] expected = ab.toByteArray();
        CompositeByteBuf b = Unpooled.compositeBuffer();
        try {
            ab.writeTo(b);
            assertArrayEquals(expected, drain(b));
        } finally {
            b.release();
        }
    }

    @Test
    public void testRoundTripAfterParse() {
        // Serialize a parsed message (exercises the lazy-string passthrough writes)
        byte[] expected = sample().toByteArray();
        AddressBook parsed = new AddressBook();
        parsed.parseFrom(expected);
        ByteBuf direct = PooledByteBufAllocator.DEFAULT.directBuffer(expected.length);
        try {
            parsed.writeTo(direct);
            assertArrayEquals(expected, drain(direct));
        } finally {
            direct.release();
        }
    }
}
