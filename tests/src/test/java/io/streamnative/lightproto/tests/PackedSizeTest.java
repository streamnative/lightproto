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
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Packed varint fields cache their payload size between getSerializedSize() and _writeTo().
 * Each test changes the elements after the size was cached and compares the serialized bytes
 * with protobuf-java's. The messages exceed NIO_WRITE_MIN, so a direct target is written
 * through its NIO view and a heap target through its array.
 */
public class PackedSizeTest {

    private static final int COUNT = 200;

    private static void add(RepeatedPacked lp, RepeatedNumbers.RepeatedPacked.Builder pb, long base, int count) {
        for (int i = 0; i < count; i++) {
            long v = base + i * 1000003L;
            lp.addXInt64(v);
            pb.addXInt64(v);
            lp.addXSint32((int) -v);
            pb.addXSint32((int) -v);
            lp.addXUint32((int) v);
            pb.addXUint32((int) v);
            lp.addEnum1(i % 2 == 0 ? RepeatedPacked.Enum.X2_1 : RepeatedPacked.Enum.X2_2);
            pb.addEnum1(i % 2 == 0 ? RepeatedNumbers.RepeatedPacked.Enum.X2_1 : RepeatedNumbers.RepeatedPacked.Enum.X2_2);
        }
    }

    private static byte[] serialize(RepeatedPacked lp, boolean direct) {
        if (!direct) {
            return lp.toByteArray();
        }
        ByteBuf b = Unpooled.directBuffer(lp.getSerializedSize());
        try {
            lp.writeTo(b);
            return ByteBufUtil.getBytes(b);
        } finally {
            b.release();
        }
    }

    private static void assertSerializesAs(RepeatedNumbers.RepeatedPacked.Builder pb, RepeatedPacked lp, boolean direct) {
        assertEquals(pb.build().getSerializedSize(), lp.getSerializedSize());
        assertArrayEquals(pb.build().toByteArray(), serialize(lp, direct));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testAddAfterSerialize(boolean direct) {
        RepeatedPacked lp = new RepeatedPacked();
        RepeatedNumbers.RepeatedPacked.Builder pb = RepeatedNumbers.RepeatedPacked.newBuilder();
        add(lp, pb, 0, COUNT);
        assertSerializesAs(pb, lp, direct);

        add(lp, pb, 1L << 40, 10);
        assertSerializesAs(pb, lp, direct);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testClearFieldAfterSerialize(boolean direct) {
        RepeatedPacked lp = new RepeatedPacked();
        RepeatedNumbers.RepeatedPacked.Builder pb = RepeatedNumbers.RepeatedPacked.newBuilder();
        add(lp, pb, 0, COUNT);
        assertSerializesAs(pb, lp, direct);

        lp.clearXInt64();
        pb.clearXInt64();
        for (int i = 0; i < COUNT; i++) {
            lp.addXInt64(-i);
            pb.addXInt64(-i);
        }
        assertSerializesAs(pb, lp, direct);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testClearAfterSerialize(boolean direct) {
        RepeatedPacked lp = new RepeatedPacked();
        RepeatedNumbers.RepeatedPacked.Builder pb = RepeatedNumbers.RepeatedPacked.newBuilder();
        add(lp, pb, 0, COUNT);
        assertSerializesAs(pb, lp, direct);

        lp.clear();
        pb = RepeatedNumbers.RepeatedPacked.newBuilder();
        add(lp, pb, 1L << 40, COUNT / 2);
        assertSerializesAs(pb, lp, direct);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testParseAfterSerialize(boolean direct) {
        // parseFrom() caches the wire size without computing the fields: the payload sizes
        // cached by the earlier serialization must not survive it
        RepeatedPacked lp = new RepeatedPacked();
        add(lp, RepeatedNumbers.RepeatedPacked.newBuilder(), 0, COUNT);
        serialize(lp, direct);

        RepeatedNumbers.RepeatedPacked.Builder pb = RepeatedNumbers.RepeatedPacked.newBuilder();
        add(new RepeatedPacked(), pb, 1L << 40, COUNT / 2);
        lp.parseFrom(pb.build().toByteArray());
        assertSerializesAs(pb, lp, direct);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testAddAfterReserializingParsed(boolean direct) {
        // A parsed message computes its payload sizes in _writeTo() and keeps them for the
        // next serialization, until its elements change
        RepeatedNumbers.RepeatedPacked.Builder pb = RepeatedNumbers.RepeatedPacked.newBuilder();
        add(new RepeatedPacked(), pb, 0, COUNT);
        RepeatedPacked lp = new RepeatedPacked();
        lp.parseFrom(pb.build().toByteArray());
        assertSerializesAs(pb, lp, direct);
        assertSerializesAs(pb, lp, direct);

        add(lp, pb, 1L << 40, 10);
        assertSerializesAs(pb, lp, direct);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testCopyFromAfterSerialize(boolean direct) {
        RepeatedPacked lp = new RepeatedPacked();
        RepeatedNumbers.RepeatedPacked.Builder pb = RepeatedNumbers.RepeatedPacked.newBuilder();
        add(lp, pb, 0, COUNT);
        assertSerializesAs(pb, lp, direct);

        RepeatedPacked other = new RepeatedPacked();
        add(other, pb, 1L << 40, 10);
        lp.copyFrom(other);
        assertSerializesAs(pb, lp, direct);
    }
}
