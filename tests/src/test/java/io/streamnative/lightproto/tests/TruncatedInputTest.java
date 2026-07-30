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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

/**
 * Truncated input must throw IndexOutOfBoundsException: heap buffers fail
 * safely on array bounds, and for direct buffers the unchecked readers overrun
 * by at most 9 bytes before the post-parse limit check detects it.
 */
public class TruncatedInputTest {

    // int_field tag followed by a dangling continuation byte
    private static final byte[] TRUNCATED_VARINT = {0x08, (byte) 0x80};
    // long_field (tag 0x10) truncated mid-varint64
    private static final byte[] TRUNCATED_VARINT64 = {0x10, (byte) 0xFF, (byte) 0xFF};
    // double_field (tag 0x21) with only 3 of 8 bytes
    private static final byte[] TRUNCATED_FIXED64 = {0x21, 0x00, 0x00, 0x00};

    private static void assertThrowsOnAllBufferTypes(byte[] data) {
        // Heap, exact size
        assertThrows(IndexOutOfBoundsException.class,
                () -> new Proto3Message().parseFrom(data));

        // Pooled direct (capacity slack: overrun reads stay in the chunk, the
        // post-parse limit check throws)
        ByteBuf pooled = PooledByteBufAllocator.DEFAULT.directBuffer(data.length);
        try {
            pooled.writeBytes(data);
            assertThrows(IndexOutOfBoundsException.class,
                    () -> new Proto3Message().parseFrom(pooled, pooled.readableBytes()));
        } finally {
            pooled.release();
        }

    }

    @Test
    public void testTruncatedVarint() {
        assertThrowsOnAllBufferTypes(TRUNCATED_VARINT);
    }

    @Test
    public void testTruncatedVarint64() {
        assertThrowsOnAllBufferTypes(TRUNCATED_VARINT64);
    }

    @Test
    public void testTruncatedFixed64() {
        assertThrowsOnAllBufferTypes(TRUNCATED_FIXED64);
    }

    @Test
    public void testTruncatedLengthDelimited() {
        // string_field (tag 0x32) claims 5 bytes but only 2 are present:
        // the checked skipBytes() catches this
        byte[] data = {0x32, 0x05, 'a', 'b'};
        assertThrowsOnAllBufferTypes(data);
    }

    @Test
    public void testValidMessageOnTightCapacityBuffer() {
        // A valid message on a zero-slack buffer never overruns
        Proto3Message src = new Proto3Message();
        src.setIntField(42);
        src.setLongField(123456789L);
        src.setStringField("tight-capacity");
        byte[] data = src.toByteArray();

        ByteBuf tight = Unpooled.directBuffer(data.length, data.length);
        try {
            tight.writeBytes(data);
            Proto3Message parsed = new Proto3Message();
            parsed.parseFrom(tight, tight.readableBytes());
            assertEquals(42, parsed.getIntField());
            assertEquals(123456789L, parsed.getLongField());
            assertEquals("tight-capacity", parsed.getStringField());
            assertEquals(0, tight.readableBytes());
        } finally {
            tight.release();
        }
    }
}
