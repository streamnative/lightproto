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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.pulsar.common.api.proto.BaseCommand;
import org.junit.jupiter.api.Test;

/**
 * Direct-buffer writes above {@code NIO_WRITE_MIN} go through the buffer's NIO
 * view: no scratch array, no transient allocation, whatever the message size.
 * At or below the threshold, and for buffers without a single NIO region, the
 * scratch path is kept.
 */
public class NioWriteTest {

    private static byte[] scratchOf(Object msg) throws Exception {
        Field f = msg.getClass().getDeclaredField("_scratch");
        f.setAccessible(true);
        return (byte[]) f.get(msg);
    }

    private static byte[] drain(ByteBuf b) {
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        return out;
    }

    private static S withNameOfSerializedSize(int size) {
        // tag(1) + length varint + payload: search the payload length that lands on size
        for (int len = Math.max(0, size - 6); len <= size; len++) {
            S s = new S();
            s.addName("y".repeat(len));
            if (s.getSerializedSize() == size) {
                return s;
            }
        }
        throw new AssertionError("no single-name message of serialized size " + size);
    }

    @Test
    public void testThresholdRoutesToScratchOrView() throws Exception {
        int t = LightProtoCodec.NIO_WRITE_MIN;
        for (int size : new int[]{64, t - 1, t}) {
            S s = withNameOfSerializedSize(size);
            byte[] expected = s.toByteArray();
            ByteBuf direct = PooledByteBufAllocator.DEFAULT.directBuffer(size);
            try {
                s.writeTo(direct);
                assertArrayEquals(expected, drain(direct));
            } finally {
                direct.release();
            }
            byte[] scratch = scratchOf(s);
            assertNotNull(scratch, "size " + size + " should use the scratch path");
            assertTrue(scratch.length <= Math.max(64, t));
        }
        for (int size : new int[]{t + 1, 4096, 100 * 1024}) {
            S s = withNameOfSerializedSize(size);
            byte[] expected = s.toByteArray();
            ByteBuf direct = PooledByteBufAllocator.DEFAULT.directBuffer(size);
            try {
                s.writeTo(direct);
                assertArrayEquals(expected, drain(direct));
            } finally {
                direct.release();
            }
            assertNull(scratchOf(s), "size " + size + " should be written through the NIO view");
        }
    }

    @Test
    public void testCompositeKeepsScratchPath() throws Exception {
        S s = withNameOfSerializedSize(3000);
        byte[] expected = s.toByteArray();
        CompositeByteBuf composite = Unpooled.compositeBuffer();
        composite.addComponent(true, PooledByteBufAllocator.DEFAULT.directBuffer(1000).writeZero(1000));
        composite.addComponent(true, PooledByteBufAllocator.DEFAULT.directBuffer(2001).writeZero(2001));
        composite.writerIndex(0);
        try {
            s.writeTo(composite);
            assertArrayEquals(expected, drain(composite));
        } finally {
            composite.release();
        }
        assertNotNull(scratchOf(s));
    }

    @Test
    public void testAllocationFreeLargeWritesBytes() throws Exception {
        byte[] payload = new byte[5 * 1024 * 1024];
        new Random(7).nextBytes(payload);
        B b = new B().setPayload(payload);
        assertAllocationFree(b::writeTo, b.getSerializedSize());
        assertNull(scratchOf(b));
    }

    @Test
    public void testAllocationFreeLargeWritesTopicList() throws Exception {
        // With -XX:-CompactStrings the ASCII fast path cannot bulk-put the String's
        // internal byte[] and copies each string through a temporary array.
        assumeTrue(!ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:-CompactStrings"));

        List<String> topics = new ArrayList<>();
        String base = "persistent://public/default/" + "t".repeat(520) + "-";
        for (int i = 0; i < 8192; i++) {
            topics.add(base + i);
        }
        BaseCommand cmd = new BaseCommand().setType(BaseCommand.Type.GET_TOPICS_OF_NAMESPACE_RESPONSE);
        cmd.setGetTopicsOfNamespaceResponse().setRequestId(42).addAllTopics(topics);
        assertAllocationFree(cmd::writeTo, cmd.getSerializedSize());
        assertNull(scratchOf(cmd));
    }

    private static void assertAllocationFree(java.util.function.ToIntFunction<ByteBuf> writeTo, int size)
            throws Exception {
        var mxBean = ManagementFactory.getThreadMXBean();
        assumeTrue(mxBean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean tb = (com.sun.management.ThreadMXBean) mxBean;
        assumeTrue(tb.isThreadAllocatedMemorySupported());
        if (!tb.isThreadAllocatedMemoryEnabled()) {
            tb.setThreadAllocatedMemoryEnabled(true);
        }
        assertTrue(size > 1024 * 1024);

        ByteBuf direct = PooledByteBufAllocator.DEFAULT.directBuffer(size + 64);
        try {
            for (int i = 0; i < 3; i++) {
                direct.clear();
                writeTo.applyAsInt(direct);
            }
            long tid = Thread.currentThread().getId();
            long before = tb.getThreadAllocatedBytes(tid);
            for (int i = 0; i < 5; i++) {
                direct.clear();
                writeTo.applyAsInt(direct);
            }
            long allocated = tb.getThreadAllocatedBytes(tid) - before;
            // Staging would allocate a fresh full-size array per write (5x the
            // message size over this loop); the in-place view allocates none of it.
            assertTrue(allocated < size / 4,
                    "expected allocation-free serialization but " + allocated
                            + " bytes were allocated for 5 writes of a " + size + "-byte message");
        } finally {
            direct.release();
        }
    }
}
