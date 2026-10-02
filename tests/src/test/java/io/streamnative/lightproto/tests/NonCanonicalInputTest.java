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
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The size of a message on the wire is not necessarily the size it re-serializes
 * to. Valid but non-canonical encodings (a negative int32 in fewer than 10 bytes,
 * the other packed encoding, a map entry without key or value, overlong varints,
 * repeated or default-valued fields, unknown enum values) and lengths that run
 * past the end of a message make it larger or smaller. Whatever the input, a
 * parsed message must serialize exactly like a copy of it (copyFrom() computes
 * the size from the fields), and writeTo() must write exactly the bytes it
 * reserves: pooled heap buffers share a backing array per chunk, so writing past
 * them corrupts other buffers.
 */
public class NonCanonicalInputTest {

    private static final PooledByteBufAllocator POOLED_HEAP = new PooledByteBufAllocator(false);

    // ---- Re-serialized larger than the wire ----

    @ParameterizedTest
    @ValueSource(ints = {20, 120})
    public void testMapEntriesWithoutValue(int entries) {
        // string_to_int { key: "<i>" } x entries: 5 bytes each, written back as 7 with
        // an explicit value 0. 120 entries take the NIO path of direct buffers.
        assertReserializesLikeCopy(MapMessage::new, MapMessage::copyFrom, mapEntriesWithoutValue(entries));
    }

    @Test
    public void testMapEntryWithoutKey() {
        // int_to_string { value: "v" }
        assertReserializesLikeCopy(MapMessage::new, MapMessage::copyFrom, bytes(0x12, 0x03, 0x12, 0x01, 'v'));
    }

    @Test
    public void testMapEntryWithoutMessageValue() {
        // string_to_msg { key: "k" }
        assertReserializesLikeCopy(MapMessage::new, MapMessage::copyFrom, bytes(0x1A, 0x03, 0x0A, 0x01, 'k'));
    }

    @Test
    public void testWriteToBufferCappedAtWireSize() {
        // The original report: 100 bytes that re-serialize to 140, written to a pooled
        // heap buffer capped at 100 bytes whose chunk holds a neighbouring buffer.
        byte[] wire = mapEntriesWithoutValue(20);
        MapMessage m = new MapMessage();
        m.parseFrom(wire);

        PooledByteBufAllocator alloc = new PooledByteBufAllocator(false);
        ByteBuf target = alloc.heapBuffer(wire.length, wire.length);
        ByteBuf neighbour = alloc.heapBuffer(wire.length, wire.length);
        try {
            assertSame(target.array(), neighbour.array());
            byte[] fill = new byte[wire.length];
            Arrays.fill(fill, (byte) 0x55);
            neighbour.writeBytes(fill);

            assertThrows(IndexOutOfBoundsException.class, () -> m.writeTo(target));
            assertEquals(0, target.writerIndex());
            assertArrayEquals(fill, ByteBufUtil.getBytes(neighbour));
            assertEquals(140, m.getSerializedSize());
        } finally {
            target.release();
            neighbour.release();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 6, 7, 8, 9})
    public void testNegativeInt32InFewerThanTenBytes(int varintLength) {
        // x_int32 = -1 in varintLength bytes: written back as the 10-byte sign extension
        byte[] wire = new byte[1 + varintLength];
        wire[0] = 0x08;
        Arrays.fill(wire, 1, varintLength, (byte) 0xFF);
        wire[varintLength] = (byte) (varintLength == 5 ? 0x0F : 0x01);
        assertReserializesLikeCopy(Numbers::new, Numbers::copyFrom, wire);
    }

    @Test
    public void testPackedEncodingOfUnpackedField() {
        // Repeated.x_int32 [1, 2, 3] as a packed chunk: written back with a tag per element
        assertReserializesLikeCopy(Repeated::new, Repeated::copyFrom, bytes(0x0A, 0x03, 0x01, 0x02, 0x03));
    }

    @Test
    public void testUnpackedEncodingOfPackedField() {
        // RepeatedPacked.x_int32 [5] as a single unpacked element: written back packed
        assertReserializesLikeCopy(RepeatedPacked::new, RepeatedPacked::copyFrom, bytes(0x70, 0x05));
    }

    @Test
    public void testUint32AboveIntMaxFromProtobufJava() {
        // protobuf-java writes a uint32 >= 2^31 in 5 bytes
        assertReserializesLikeCopy(Numbers::new, Numbers::copyFrom,
                NumbersOuterClass.Numbers.newBuilder().setXUint32(0x80000000).build().toByteArray());
    }

    @Test
    public void testNestedMessageReserializedLarger() {
        // oneof_msg { value: -1 } with the int32 in 5 bytes
        assertReserializesLikeCopy(OneofMsg::new, OneofMsg::copyFrom,
                bytes(0x6A, 0x06, 0x08, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F));
    }

    @Test
    public void testMapMessageValueReserializedLarger() {
        // string_to_msg { key: "k" value { id: -1 } } with the int32 in 5 bytes
        assertReserializesLikeCopy(MapMessage::new, MapMessage::copyFrom,
                bytes(0x1A, 0x0B, 0x0A, 0x01, 'k', 0x12, 0x06, 0x08, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F));
    }

    @Test
    public void testRepeatedMessageTwoLevelsDownReserializedLarger() {
        // items { k: "k" v: "v" xx { n: -1 } } with the int32 in 5 bytes
        assertReserializesLikeCopy(M::new, M::copyFrom,
                bytes(0x12, 0x0E, 0x0A, 0x01, 'k', 0x12, 0x01, 'v', 0x1A, 0x06, 0x08, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F));
    }

    @Test
    public void testNestedLengthPastParentEnd() {
        // x is declared 2 bytes long, but its field a runs 5 bytes further. M's bytes
        // are reproduced either way, but x on its own holds 7 bytes, not 2.
        byte[] wire = bytes(0x0A, 0x02, 0x0A, 0x05, 'h', 'e', 'l', 'l', 'o');
        assertReserializesLikeCopy(() -> parse(new M(), wire).getX(), X::new, X::copyFrom);
        assertReserializesLikeCopy(M::new, M::copyFrom, wire);
    }

    @Test
    public void testNestedMessageChangedAfterParsing() {
        // x { a: "a" }, then x.a set through getX(): M's wire size no longer applies
        byte[] wire = bytes(0x0A, 0x03, 0x0A, 0x01, 'a');
        assertReserializesLikeCopy(() -> {
            M m = parse(new M(), wire);
            m.getX().setA("a longer value than the parsed one");
            return m;
        }, M::new, M::copyFrom);
    }

    @Test
    public void testLengthPastSizeLimit() {
        // parseFrom(buffer, 2), where the first field runs 5 bytes past those 2
        byte[] wire = bytes(0x0A, 0x05, 'h', 'e', 'l', 'l', 'o');
        assertReserializesLikeCopy(() -> {
            X x = new X();
            x.parseFrom(Unpooled.wrappedBuffer(wire), 2);
            return x;
        }, X::new, X::copyFrom);
    }

    // ---- Re-serialized smaller than the wire ----

    @Test
    public void testUnknownEnumValue() {
        // E1 has no value 3: the field is dropped
        assertReserializesLikeCopy(EnumTest1Optional::new, EnumTest1Optional::copyFrom, bytes(0x08, 0x03));
    }

    @Test
    public void testUnknownEnumValueInPackedField() {
        assertReserializesLikeCopy(EnumTest1Packed::new, EnumTest1Packed::copyFrom, bytes(0x0A, 0x02, 0x03, 0x01));
    }

    @Test
    public void testOverlongVarint() {
        // x_int32 = 1 in 2 bytes
        assertReserializesLikeCopy(Numbers::new, Numbers::copyFrom, bytes(0x08, 0x81, 0x00));
    }

    @Test
    public void testOverlongTag() {
        // the tag of x_int32 in 2 bytes
        assertReserializesLikeCopy(Numbers::new, Numbers::copyFrom, bytes(0x88, 0x00, 0x01));
    }

    @Test
    public void testOverlongLength() {
        // a = "abc" with its length in 2 bytes
        assertReserializesLikeCopy(X::new, X::copyFrom, bytes(0x0A, 0x83, 0x00, 'a', 'b', 'c'));
    }

    @Test
    public void testSingularFieldRepeated() {
        // x_int32 twice: the last one wins
        assertReserializesLikeCopy(Numbers::new, Numbers::copyFrom, bytes(0x08, 0x01, 0x08, 0x02));
    }

    @Test
    public void testMessageFieldRepeated() {
        // x { a: "a" } then x { b: "b" }
        assertReserializesLikeCopy(M::new, M::copyFrom,
                bytes(0x0A, 0x03, 0x0A, 0x01, 'a', 0x0A, 0x03, 0x12, 0x01, 'b'));
    }

    @Test
    public void testOneofMemberReplaced() {
        // oneof_int = 1, then oneof_bool = true
        assertReserializesLikeCopy(OneofMsg::new, OneofMsg::copyFrom, bytes(0x50, 0x01, 0x60, 0x01));
    }

    @Test
    public void testProto3DefaultValues() {
        // int_field = 0 and string_field = "": implicit presence, so not written back
        assertReserializesLikeCopy(Proto3Message::new, Proto3Message::copyFrom, bytes(0x08, 0x00, 0x32, 0x00));
    }

    @Test
    public void testEmptyPackedChunk() {
        assertReserializesLikeCopy(RepeatedPacked::new, RepeatedPacked::copyFrom, bytes(0x72, 0x00));
    }

    @Test
    public void testSplitPackedChunks() {
        // x_int32 [1] and [2] as two packed chunks: written back as one
        assertReserializesLikeCopy(RepeatedPacked::new, RepeatedPacked::copyFrom,
                bytes(0x72, 0x01, 0x01, 0x72, 0x01, 0x02));
    }

    @Test
    public void testMapEntryWithRepeatedKey() {
        // string_to_int { key: "k" key: "k" value: 7 }
        assertReserializesLikeCopy(MapMessage::new, MapMessage::copyFrom,
                bytes(0x0A, 0x08, 0x0A, 0x01, 'k', 0x0A, 0x01, 'k', 0x10, 0x07));
    }

    @Test
    public void testUnknownFieldInNestedMessage() {
        // x { a: "a" } plus field 15, which X does not declare
        assertReserializesLikeCopy(M::new, M::copyFrom, bytes(0x0A, 0x05, 0x0A, 0x01, 'a', 0x78, 0x01));
    }

    // ---- Helpers ----

    private static <T extends LightProtoCodec.LightProtoMessage> void assertReserializesLikeCopy(
            Supplier<T> factory, BiFunction<T, T, T> copyFrom, byte[] wire) {
        assertReserializesLikeCopy(() -> parse(factory.get(), wire), factory, copyFrom);
    }

    /**
     * Every check gets a newly parsed message, so that none sees a size cached by
     * another one: what writeTo() does right after parseFrom() is the point.
     */
    private static <T extends LightProtoCodec.LightProtoMessage> void assertReserializesLikeCopy(
            Supplier<T> parsed, Supplier<T> factory, BiFunction<T, T, T> copyFrom) {
        byte[] expected = writeTo(copyFrom.apply(factory.get(), parsed.get()), Unpooled.buffer());

        assertEquals(expected.length, parsed.get().getSerializedSize(), "getSerializedSize()");
        assertArrayEquals(expected, toByteArray(parsed.get()), "toByteArray()");
        assertArrayEquals(expected, writeTo(parsed.get(), Unpooled.buffer()), "writeTo() to a heap buffer");
        assertArrayEquals(expected, writeTo(parsed.get(), Unpooled.directBuffer()), "writeTo() to a direct buffer");
        assertArrayEquals(expected, writeToSizedPooledBuffer(parsed.get()), "writeTo() to a pooled heap buffer");
    }

    private static byte[] writeTo(LightProtoCodec.LightProtoMessage m, ByteBuf b) {
        try {
            assertEquals(m.writeTo(b), b.readableBytes());
            return ByteBufUtil.getBytes(b);
        } finally {
            b.release();
        }
    }

    /**
     * Writes to a pooled heap buffer of exactly getSerializedSize() bytes, as the
     * gRPC marshaller does, and checks that no byte of the chunk array that the
     * buffer shares with other pooled buffers changed outside of it.
     */
    private static byte[] writeToSizedPooledBuffer(LightProtoCodec.LightProtoMessage m) {
        int size = m.getSerializedSize();
        ByteBuf b = POOLED_HEAP.heapBuffer(size, size);
        try {
            byte[] chunk = b.array();
            byte[] before = chunk.clone();
            m.writeTo(b);
            int start = b.arrayOffset();
            int end = start + b.capacity();
            assertTrue(Arrays.equals(chunk, 0, start, before, 0, start)
                            && Arrays.equals(chunk, end, chunk.length, before, end, chunk.length),
                    "writeTo() changed bytes outside of its target buffer");
            return ByteBufUtil.getBytes(b);
        } finally {
            b.release();
        }
    }

    private static byte[] toByteArray(LightProtoCodec.LightProtoMessage m) {
        try {
            return (byte[]) m.getClass().getMethod("toByteArray").invoke(m);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static <T extends LightProtoCodec.LightProtoMessage> T parse(T m, byte[] wire) {
        m.parseFrom(wire);
        return m;
    }

    /** string_to_int entries with distinct one-byte keys (so at most 128) and no value. */
    private static byte[] mapEntriesWithoutValue(int entries) {
        byte[] wire = new byte[entries * 5];
        for (int i = 0; i < entries; i++) {
            System.arraycopy(bytes(0x0A, 0x03, 0x0A, 0x01, i), 0, wire, i * 5, 5);
        }
        return wire;
    }

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            b[i] = (byte) values[i];
        }
        return b;
    }
}
