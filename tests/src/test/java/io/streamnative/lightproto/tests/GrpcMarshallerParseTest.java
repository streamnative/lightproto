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

import io.grpc.MethodDescriptor;
import io.grpc.internal.CompositeReadableBuffer;
import io.grpc.internal.ForwardingReadableBuffer;
import io.grpc.internal.ReadableBuffer;
import io.grpc.internal.ReadableBuffers;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Parses with the generated marshaller from the stream gRPC transports pass to parse():
 * a {@code ReadableBuffers.BufferInputStream} over the buffers holding the message, assembled
 * with the same gRPC classes the deframer uses, from buffers that record how they are read.
 */
public class GrpcMarshallerParseTest {

    private static final MethodDescriptor.Marshaller<GrpcPayload> MARSHALLER =
            EchoServiceGrpc.getEchoMethod().getRequestMarshaller();

    @ParameterizedTest
    @ValueSource(ints = {100, 64 * 1024})
    void testMessageInOneBufferIsParsedInPlace(int dataSize) {
        GrpcPayload expected = GrpcPayloads.create(dataSize, dataSize);
        List<TrackingBuffer> buffers = track(split(expected.toByteArray(), 1), true);

        GrpcPayload parsed = MARSHALLER.parse(openStream(buffers));

        TrackingBuffer buffer = buffers.get(0);
        assertEquals(0, buffer.bytesRead, "The message should not be copied out of the buffer");
        assertEquals(1, buffer.closeCount);
        // close() overwrote the buffer, so the message must no longer refer to it
        assertEquals(expected, parsed);
    }

    @Test
    void testConsecutiveMessagesInOneBufferAreParsedInPlace() {
        // Each thread parses a message under 16 KiB received in one direct buffer through the same
        // view, which must not carry anything over from one message to the next. Larger ones get a
        // new wrapper
        List<GrpcPayload> parsed = new ArrayList<>();
        List<GrpcPayload> expected = new ArrayList<>();
        for (int dataSize : new int[] {4096, 100, 0, 64 * 1024, 7, 15 * 1024}) {
            GrpcPayload message = GrpcPayloads.create(dataSize, dataSize);
            List<TrackingBuffer> buffers = track(split(message.toByteArray(), 1), true);
            parsed.add(MARSHALLER.parse(openStream(buffers)));
            expected.add(message);
            assertEquals(0, buffers.get(0).bytesRead, "The message should not be copied out of the buffer");
            assertEquals(1, buffers.get(0).closeCount);
        }
        // close() overwrote every buffer, so no message may still refer to one
        assertEquals(expected, parsed);
    }

    @Test
    void testFailedParseInOneBufferDoesNotAffectTheNext() {
        byte[] serialized = new GrpcPayload().setData(new byte[200]).toByteArray();
        byte[] truncated = java.util.Arrays.copyOf(serialized, 100);
        assertThrows(RuntimeException.class, () -> MARSHALLER.parse(openStream(track(split(truncated, 1), true))));

        GrpcPayload expected = GrpcPayloads.create(5, 100);
        assertEquals(expected, MARSHALLER.parse(openStream(track(split(expected.toByteArray(), 1), true))));
    }

    @Test
    void testMessageInOneHeapBufferIsParsedInPlace() {
        GrpcPayload expected = GrpcPayloads.create(6, 100);
        byte[] serialized = expected.toByteArray();
        ByteBuffer heap = ByteBuffer.allocate(serialized.length).put(serialized).flip();
        List<TrackingBuffer> buffers = track(List.of(heap), true);

        GrpcPayload parsed = MARSHALLER.parse(openStream(buffers));

        assertEquals(0, buffers.get(0).bytesRead, "The message should not be copied out of the buffer");
        assertEquals(1, buffers.get(0).closeCount);
        assertEquals(expected, parsed);
    }

    @ParameterizedTest
    @ValueSource(ints = {16 * 1024, 64 * 1024})
    void testLargeMessageAcrossBuffersIsParsedInPlace(int dataSize) {
        GrpcPayload expected = GrpcPayloads.create(dataSize, dataSize);
        List<TrackingBuffer> buffers = track(split(expected.toByteArray(), 3), true);

        GrpcPayload parsed = MARSHALLER.parse(openStream(buffers));

        for (TrackingBuffer buffer : buffers) {
            assertEquals(0, buffer.bytesRead, "The message should not be copied out of the buffers");
            assertEquals(1, buffer.closeCount);
        }
        // close() overwrote the buffers, so the message must no longer refer to them
        assertEquals(expected, parsed);
    }

    @Test
    void testFieldsSplitAcrossBuffersAreParsedInPlace() {
        // One buffer up to the end of the data, then one byte per buffer: every tag, length, and
        // value after the data straddles buffers. Since gRPC 1.82.4, CompositeReadableBuffer merges
        // a run of small buffers once 1000 have arrived or a large one follows, so one-byte
        // buffers can't carry a whole message (SegmentedByteBufTest reads across one-byte segments)
        GrpcPayload expected = GrpcPayloads.create(3, 16 * 1024);
        byte[] serialized = expected.toByteArray();
        int head = new GrpcPayload().setName(expected.getName()).setData(expected.getData()).getSerializedSize();
        assertTrue(serialized.length - head < 1000);
        List<TrackingBuffer> buffers = track(splitBytesAfter(serialized, head), true);

        GrpcPayload parsed = MARSHALLER.parse(openStream(buffers));

        for (TrackingBuffer buffer : buffers) {
            assertEquals(0, buffer.bytesRead, "The message should not be copied out of the buffers");
            assertEquals(1, buffer.closeCount);
        }
        assertEquals(expected, parsed);
    }

    @Test
    void testSmallMessageAcrossBuffersIsCopiedInOnePass() {
        // Below one HTTP/2 frame, a message only spans buffers when it straddles two frames, and
        // copying it is cheaper than parsing it in place
        GrpcPayload expected = GrpcPayloads.create(2, 100);
        List<TrackingBuffer> buffers = track(split(expected.toByteArray(), 3), true);

        GrpcPayload parsed = MARSHALLER.parse(openStream(buffers));

        assertCopiedInOnePass(buffers);
        assertEquals(expected, parsed);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void testBufferWithoutByteBufferSupportIsCopiedInOnePass(int pieces) {
        GrpcPayload expected = GrpcPayloads.create(1, 20 * 1024);
        List<TrackingBuffer> buffers = track(split(expected.toByteArray(), pieces), false);

        GrpcPayload parsed = MARSHALLER.parse(openStream(buffers));

        assertCopiedInOnePass(buffers);
        assertEquals(expected, parsed);
    }

    @Test
    void testBuffersWithoutMarkSupportAreCopiedInOnePass() {
        // Without a mark, skipping a buffer would hand it back before the message is parsed
        GrpcPayload expected = GrpcPayloads.create(4, 20 * 1024);
        List<TrackingBuffer> buffers = new ArrayList<>();
        for (ByteBuffer piece : split(expected.toByteArray(), 3)) {
            buffers.add(new TrackingBuffer(piece, true, false));
        }

        GrpcPayload parsed = MARSHALLER.parse(openStream(buffers));

        assertCopiedInOnePass(buffers);
        assertEquals(expected, parsed);
    }

    @Test
    void testEmptyBufferWithinMessageIsCopiedInOnePass() {
        // An empty buffer stops the in-place parse after it skipped the first buffer, so the
        // stream must be reset before it is copied
        GrpcPayload expected = GrpcPayloads.create(5, 20 * 1024);
        List<ByteBuffer> memory = split(expected.toByteArray(), 2);
        memory.add(1, ByteBuffer.allocateDirect(0));
        List<TrackingBuffer> buffers = track(memory, true);

        GrpcPayload parsed = MARSHALLER.parse(openStream(buffers));

        assertCopiedInOnePass(List.of(buffers.get(0), buffers.get(2)));
        assertEquals(1, buffers.get(1).closeCount);
        assertEquals(expected, parsed);
    }

    @Test
    void testEmptyMessage() {
        assertEquals(new GrpcPayload(), MARSHALLER.parse(openStream(List.of())));
    }

    @Test
    void testOtherStreamTypes() {
        GrpcPayload expected = GrpcPayloads.create(2, 100);
        byte[] serialized = expected.toByteArray();

        // The marshaller's own stream, which the in-process transport passes along
        assertEquals(expected, MARSHALLER.parse(MARSHALLER.stream(expected)));
        // A stream of unknown length
        assertEquals(expected, MARSHALLER.parse(new BufferedInputStream(new ByteArrayInputStream(serialized))));
        // A Netty ByteBufInputStream
        assertEquals(expected,
                MARSHALLER.parse(new ByteBufInputStream(Unpooled.wrappedBuffer(serialized), true)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void testSmallMessageAllocation(int pieces) {
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());

        MethodDescriptor.Marshaller<GrpcRequest> marshaller =
                TestServiceGrpc.getUnaryMethod().getRequestMarshaller();
        List<ByteBuffer> memory = split(new GrpcRequest().setName("hello").setValue(42).toByteArray(), pieces);

        int iterations = 1000;
        for (int i = 0; i < iterations; i++) {
            marshaller.parse(openStream(wrap(memory)));
        }
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < iterations; i++) {
            assertEquals(42, marshaller.parse(openStream(wrap(memory))).getValue());
        }
        long perMessage = (threads.getCurrentThreadAllocatedBytes() - before) / iterations;

        // readAllBytes() allocated an 8 or 16 KiB chunk per message, depending on the JDK
        assertTrue(perMessage < 4096, "Allocated " + perMessage + " bytes per message");
    }

    private static void assertCopiedInOnePass(List<TrackingBuffer> buffers) {
        for (TrackingBuffer buffer : buffers) {
            assertEquals(1, buffer.readCalls, "Each buffer should be read by a single call");
            assertEquals(buffer.size, buffer.bytesRead);
            assertEquals(1, buffer.closeCount);
        }
    }

    /** Splits a message into pieces of direct memory, like the pooled buffers of Netty. */
    private static List<ByteBuffer> split(byte[] message, int pieces) {
        List<ByteBuffer> memory = new ArrayList<>();
        int pieceSize = (message.length + pieces - 1) / pieces;
        for (int offset = 0; offset < message.length; offset += pieceSize) {
            int length = Math.min(pieceSize, message.length - offset);
            ByteBuffer piece = ByteBuffer.allocateDirect(length);
            piece.put(message, offset, length).flip();
            memory.add(piece);
        }
        return memory;
    }

    /** Splits a message into one piece of its first {@code head} bytes, then one piece per byte. */
    private static List<ByteBuffer> splitBytesAfter(byte[] message, int head) {
        List<ByteBuffer> memory = new ArrayList<>();
        for (int offset = 0; offset < message.length; ) {
            int length = offset == 0 ? head : 1;
            ByteBuffer piece = ByteBuffer.allocateDirect(length);
            piece.put(message, offset, length).flip();
            memory.add(piece);
            offset += length;
        }
        return memory;
    }

    private static List<TrackingBuffer> track(List<ByteBuffer> memory, boolean byteBufferSupported) {
        List<TrackingBuffer> buffers = new ArrayList<>();
        for (ByteBuffer piece : memory) {
            buffers.add(new TrackingBuffer(piece, byteBufferSupported));
        }
        return buffers;
    }

    private static List<ReadableBuffer> wrap(List<ByteBuffer> memory) {
        List<ReadableBuffer> buffers = new ArrayList<>();
        for (ByteBuffer piece : memory) {
            buffers.add(ReadableBuffers.wrap(piece.duplicate()));
        }
        return buffers;
    }

    private static InputStream openStream(List<? extends ReadableBuffer> buffers) {
        // The deframer collects the buffers of each message in a CompositeReadableBuffer
        CompositeReadableBuffer composite = new CompositeReadableBuffer();
        buffers.forEach(composite::addBuffer);
        return ReadableBuffers.openStream(composite, true);
    }

    /**
     * A transport buffer that records the bytes copied out of it, and that overwrites its memory
     * on close(), as a pooled transport buffer does once it is reused.
     */
    private static final class TrackingBuffer extends ForwardingReadableBuffer {
        final ByteBuffer memory;
        final int size;
        final boolean byteBufferSupported;
        final boolean markSupported;
        int readCalls;
        int bytesRead;
        int closeCount;

        TrackingBuffer(ByteBuffer memory, boolean byteBufferSupported) {
            this(memory, byteBufferSupported, true);
        }

        TrackingBuffer(ByteBuffer memory, boolean byteBufferSupported, boolean markSupported) {
            super(ReadableBuffers.wrap(memory.duplicate()));
            this.memory = memory;
            this.size = memory.remaining();
            this.byteBufferSupported = byteBufferSupported;
            this.markSupported = markSupported;
        }

        @Override
        public int readUnsignedByte() {
            bytesRead++;
            return super.readUnsignedByte();
        }

        @Override
        public void readBytes(byte[] dest, int destOffset, int length) {
            readCalls++;
            bytesRead += length;
            super.readBytes(dest, destOffset, length);
        }

        @Override
        public boolean byteBufferSupported() {
            return byteBufferSupported;
        }

        @Override
        public boolean markSupported() {
            return markSupported;
        }

        @Override
        public void close() {
            closeCount++;
            for (int i = 0; i < memory.capacity(); i++) {
                memory.put(i, (byte) 0xff);
            }
            super.close();
        }
    }
}
