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
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compares the reads of {@code LightProtoCodec.SegmentedByteBuf}, which the gRPC marshaller parses
 * messages spread over several transport buffers from, with those of a heap buffer of the same content.
 */
public class SegmentedByteBufTest {

    private static final byte[] CONTENT = new byte[40];

    static {
        new Random(1).nextBytes(CONTENT);
    }

    @Test
    void testOneSegment() {
        assertSameReads(CONTENT, wrap(split(CONTENT)));
    }

    @Test
    void testTwoSegmentsSplitAtEveryPosition() {
        for (int cut = 1; cut < CONTENT.length; cut++) {
            assertSameReads(CONTENT, wrap(split(CONTENT, cut)));
        }
    }

    @Test
    void testOneByteSegments() {
        assertSameReads(CONTENT, wrap(split(CONTENT, IntStream.range(1, CONTENT.length).toArray())));
    }

    @Test
    void testSingleSegmentIsResetForEachBuffer() {
        // The view a gRPC marshaller parses a message received in one direct buffer through
        LightProtoCodec.SegmentedByteBuf view = new LightProtoCodec.SegmentedByteBuf();
        assertSameReads(CONTENT, view.reset(direct(CONTENT, 0)));
        // A transport buffer's content can start past position 0
        assertSameReads(CONTENT, view.reset(direct(CONTENT, 7)));
        byte[] other = new byte[CONTENT.length + 5];
        new Random(2).nextBytes(other);
        assertSameReads(other, view.reset(direct(other, 3)));
    }

    @Test
    void testHeapEmptyAndDirectSegments() {
        // A heap segment that doesn't start at position 0, an empty segment, and a direct segment
        ByteBuffer heap = ByteBuffer.wrap(new byte[20]);
        System.arraycopy(CONTENT, 0, heap.array(), 5, 12);
        heap.position(5).limit(17);
        ByteBuffer direct = ByteBuffer.allocateDirect(CONTENT.length - 12);
        direct.put(CONTENT, 12, CONTENT.length - 12).flip();
        assertSameReads(CONTENT, wrap(heap, ByteBuffer.allocate(0), direct));
    }

    @Test
    void testVarInt64AcrossSegments() {
        long[] values = {0, 1, 300, 1L << 40, Long.MAX_VALUE, Long.MIN_VALUE, -1};
        byte[] bytes = new byte[values.length * 10];
        int length = 0;
        for (long value : values) {
            length = LightProtoCodec.writeRawVarInt64(bytes, length, value);
        }
        bytes = Arrays.copyOf(bytes, length);
        for (int cut = 1; cut < bytes.length; cut++) {
            ByteBuf buf = wrap(split(bytes, cut));
            for (long value : values) {
                assertEquals(value, LightProtoCodec.readVarInt64(buf), "Split at " + cut);
            }
            assertEquals(bytes.length, buf.readerIndex());
        }
    }

    @Test
    void testReadPastTheEnd() {
        // A truncated varint64, which the unchecked reader would read past the message end
        ByteBuf buf = wrap(split(new byte[] {(byte) 0x80, (byte) 0x80}, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> LightProtoCodec.readVarInt64(buf));
        assertThrows(IndexOutOfBoundsException.class, () -> buf.getByte(2));
        assertThrows(IndexOutOfBoundsException.class, () -> buf.getBytes(1, new byte[2]));
    }

    @Test
    void testReadOnly() {
        ByteBuf buf = wrap(split(CONTENT, 10));
        assertTrue(buf.isReadOnly());
        assertThrows(ReadOnlyBufferException.class, () -> buf.setByte(0, 1));
        assertThrows(ReadOnlyBufferException.class, () -> buf.setBytes(0, new byte[2]));
        assertThrows(ReadOnlyBufferException.class, () -> buf.capacity(100));
        // The reference count is fixed: the ByteBuffers belong to the caller
        assertEquals(1, buf.refCnt());
        assertFalse(buf.release());
        assertEquals(CONTENT[0], buf.getByte(0));
    }

    private static void assertSameReads(byte[] content, ByteBuf actual) {
        ByteBuf expected = Unpooled.wrappedBuffer(content);
        int length = content.length;
        assertEquals(length, actual.capacity());
        assertEquals(length, actual.readableBytes());
        for (int i = 0; i < length; i++) {
            assertEquals(expected.getByte(i), actual.getByte(i), "getByte " + i);
            if (i + 2 <= length) {
                assertEquals(expected.getShort(i), actual.getShort(i), "getShort " + i);
                assertEquals(expected.getShortLE(i), actual.getShortLE(i), "getShortLE " + i);
            }
            if (i + 3 <= length) {
                assertEquals(expected.getUnsignedMedium(i), actual.getUnsignedMedium(i), "getUnsignedMedium " + i);
                assertEquals(expected.getUnsignedMediumLE(i), actual.getUnsignedMediumLE(i), "getUnsignedMediumLE " + i);
            }
            if (i + 4 <= length) {
                assertEquals(expected.getInt(i), actual.getInt(i), "getInt " + i);
                assertEquals(expected.getIntLE(i), actual.getIntLE(i), "getIntLE " + i);
            }
            if (i + 8 <= length) {
                assertEquals(expected.getLong(i), actual.getLong(i), "getLong " + i);
                assertEquals(expected.getLongLE(i), actual.getLongLE(i), "getLongLE " + i);
            }
            for (int end = i; end <= length; end++) {
                byte[] range = Arrays.copyOfRange(content, i, end);
                byte[] bytes = new byte[end - i];
                actual.getBytes(i, bytes);
                assertArrayEquals(range, bytes, "getBytes " + i + ".." + end);

                ByteBuffer nio = ByteBuffer.allocate(end - i);
                actual.getBytes(i, nio);
                assertArrayEquals(range, nio.array(), "getBytes(ByteBuffer) " + i + ".." + end);

                ByteBuf target = Unpooled.buffer(end - i);
                actual.getBytes(i, target, 0, end - i);
                assertArrayEquals(range, target.array(), "getBytes(ByteBuf) " + i + ".." + end);

                assertArrayEquals(range, ByteBufUtil.getBytes(actual.copy(i, end - i)), "copy " + i + ".." + end);
                assertArrayEquals(range, toArray(actual.nioBuffer(i, end - i)), "nioBuffer " + i + ".." + end);
                ByteBuf views = Unpooled.wrappedBuffer(actual.nioBuffers(i, end - i));
                assertArrayEquals(range, ByteBufUtil.getBytes(views), "nioBuffers " + i + ".." + end);
            }
        }
        // Empty ranges at the end
        actual.getBytes(length, new byte[0]);
        assertEquals(0, actual.nioBuffer(length, 0).remaining());
        assertEquals(1, actual.nioBuffers(length, 0).length);
        assertEquals(expected.toString(StandardCharsets.ISO_8859_1), actual.toString(StandardCharsets.ISO_8859_1));
        assertEquals(expected, actual);
        // Sequential reads, as the parser makes them
        for (int i = 0; i < length; i++) {
            assertEquals(content[i], actual.readByte());
        }
        assertEquals(length, actual.readerIndex());
    }

    /** Splits content at the given positions into direct buffers, like the pooled buffers of Netty. */
    private static ByteBuffer[] split(byte[] content, int... cuts) {
        List<ByteBuffer> segments = new ArrayList<>();
        int start = 0;
        for (int end : IntStream.concat(Arrays.stream(cuts), IntStream.of(content.length)).toArray()) {
            ByteBuffer segment = ByteBuffer.allocateDirect(end - start);
            segment.put(content, start, end - start).flip();
            segments.add(segment);
            start = end;
        }
        return segments.toArray(new ByteBuffer[0]);
    }

    /** A direct buffer holding {@code content} from {@code position} to its limit. */
    private static ByteBuffer direct(byte[] content, int position) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(position + content.length);
        buffer.position(position);
        buffer.put(content);
        buffer.position(position);
        return buffer;
    }

    private static ByteBuf wrap(ByteBuffer... segments) {
        return new LightProtoCodec.SegmentedByteBuf(segments, segments.length);
    }

    private static byte[] toArray(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
    }
}
