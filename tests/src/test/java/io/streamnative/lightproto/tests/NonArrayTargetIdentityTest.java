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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.ToIntFunction;
import org.apache.pulsar.common.api.proto.BaseCommand;
import org.junit.jupiter.api.Test;

/**
 * Whatever strategy writeTo() uses for non-array targets (scratch, chunked
 * scratch, NIO view, write-through), the bytes must equal the heap-array path
 * (toByteArray()) for every message size — in particular around any internal
 * chunk/threshold boundary — on pooled direct buffers, on multi-component
 * composites, for freshly built and for parsed (lazy passthrough) messages, and
 * across repeated writes of the same instance.
 */
public class NonArrayTargetIdentityTest {

    private static final int[] BOUNDARIES = {
            64, 512, 1024, 4096, 8192, 16384, 65536, 1024 * 1024
    };

    private static byte[] drain(ByteBuf b) {
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        return out;
    }

    /** A composite whose capacity spans two direct components. */
    private static CompositeByteBuf splitComposite(int size) {
        int first = Math.max(1, size / 3);
        CompositeByteBuf c = Unpooled.compositeBuffer();
        c.addComponent(true, PooledByteBufAllocator.DEFAULT.directBuffer(first).writeZero(first));
        c.addComponent(true, PooledByteBufAllocator.DEFAULT.directBuffer(size - first + 1).writeZero(size - first + 1));
        c.writerIndex(0);
        return c;
    }

    private static void assertAllTargets(byte[] expected, int serializedSize, ToIntFunction<ByteBuf> msg) {
        assertEquals(expected.length, serializedSize);

        ByteBuf direct = PooledByteBufAllocator.DEFAULT.directBuffer(expected.length);
        try {
            assertEquals(expected.length, msg.applyAsInt(direct));
            assertArrayEquals(expected, drain(direct), "direct");
            direct.clear();
            msg.applyAsInt(direct);
            assertArrayEquals(expected, drain(direct), "direct, second write");
        } finally {
            direct.release();
        }

        // A direct buffer with room to spare, written at a non-zero writerIndex
        ByteBuf offset = PooledByteBufAllocator.DEFAULT.directBuffer(expected.length + 64);
        try {
            offset.writeZero(13);
            msg.applyAsInt(offset);
            offset.skipBytes(13);
            assertArrayEquals(expected, drain(offset), "direct at offset");
        } finally {
            offset.release();
        }

        CompositeByteBuf composite = splitComposite(expected.length);
        try {
            msg.applyAsInt(composite);
            assertArrayEquals(expected, drain(composite), "composite");
        } finally {
            composite.release();
        }
    }

    private static void assertIdentity(S s) {
        byte[] expected = s.toByteArray();
        assertAllTargets(expected, s.getSerializedSize(), s::writeTo);
        S parsed = new S();
        parsed.parseFrom(expected);
        assertAllTargets(expected, parsed.getSerializedSize(), parsed::writeTo);
    }

    private static S strings(int count, int len, boolean nonAscii) {
        S s = new S().setId("id");
        for (int i = 0; i < count; i++) {
            String body = (nonAscii && i % 3 == 0 ? "λ∞≈" : "abc") + "x".repeat(len);
            s.addName(body + i);
        }
        return s;
    }

    @Test
    public void testStringSizeSweepAroundBoundaries() {
        // One long name: total size walks through every boundary byte by byte
        for (int boundary : BOUNDARIES) {
            for (int len = Math.max(0, boundary - 40); len <= boundary + 40; len++) {
                S s = new S().setId("i");
                s.addName("y".repeat(len));
                assertIdentity(s);
            }
        }
    }

    @Test
    public void testManyStringsAcrossBoundaries() {
        // Many ~500-byte elements: boundaries fall inside elements, tags and lengths
        for (int count : new int[]{1, 7, 8, 9, 16, 17, 33, 130, 131, 132, 135, 2100, 8192}) {
            assertIdentity(strings(count, 500, true));
        }
    }

    @Test
    public void testSmallStringsAcrossBoundaries() {
        // Tiny elements: many tag/length pairs straddle the boundaries
        for (int count : new int[]{300, 800, 1000, 1200, 1500, 3000, 6000}) {
            assertIdentity(strings(count, 1, false));
        }
    }

    @Test
    public void testBytesPayloadSweep() {
        Random rnd = new Random(1);
        List<Integer> sizes = new ArrayList<>();
        for (int boundary : BOUNDARIES) {
            for (int d = -12; d <= 12; d += 3) {
                sizes.add(Math.max(0, boundary + d));
            }
        }
        sizes.add(2 * 1024 * 1024 + 7);
        for (int size : sizes) {
            byte[] payload = new byte[size];
            rnd.nextBytes(payload);
            B b = new B().setPayload(payload);
            b.addExtraItem(new byte[]{1, 2, 3});
            b.addExtraItem(new byte[0]);
            b.addExtraItem(payload.length > 100 ? java.util.Arrays.copyOf(payload, 100) : payload);
            byte[] expected = b.toByteArray();
            assertAllTargets(expected, b.getSerializedSize(), b::writeTo);
            B parsed = new B();
            parsed.parseFrom(expected);
            assertAllTargets(expected, parsed.getSerializedSize(), parsed::writeTo);
        }
    }

    @Test
    public void testNestedTreeAcrossBoundaries() {
        for (int count : new int[]{1, 60, 120, 130, 140, 260, 2000, 2200, 9000}) {
            M m = new M();
            m.setX().setA("a-value").setB("b-value");
            for (int i = 0; i < count; i++) {
                M.KV kv = m.addItem();
                kv.setK("key-" + i);
                kv.setV("value-" + "v".repeat(i % 17) + i);
                if (i % 10 == 0) {
                    kv.setXx().setN(i);
                }
            }
            byte[] expected = m.toByteArray();
            assertAllTargets(expected, m.getSerializedSize(), m::writeTo);
            M parsed = new M();
            parsed.parseFrom(expected);
            assertAllTargets(expected, parsed.getSerializedSize(), parsed::writeTo);
        }
    }

    @Test
    public void testPulsarTopicListShape() {
        for (int topics : new int[]{1, 7, 8, 9, 28, 180, 8192}) {
            List<String> list = new ArrayList<>(topics);
            String base = "persistent://public/default/" + "t".repeat(520) + "-";
            for (int i = 0; i < topics; i++) {
                list.add(base + i);
            }
            BaseCommand cmd = new BaseCommand().setType(BaseCommand.Type.GET_TOPICS_OF_NAMESPACE_RESPONSE);
            cmd.setGetTopicsOfNamespaceResponse().setRequestId(42).addAllTopics(list);
            byte[] expected = cmd.toByteArray();
            assertAllTargets(expected, cmd.getSerializedSize(), cmd::writeTo);
            BaseCommand parsed = new BaseCommand();
            parsed.parseFrom(expected);
            assertAllTargets(expected, parsed.getSerializedSize(), parsed::writeTo);
        }
    }
}
