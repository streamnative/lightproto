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

import io.grpc.Drainable;
import io.grpc.MethodDescriptor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.util.IllegalReferenceCountException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The gRPC marshaller writes bytes values of at least GATHER_MIN held in heap buffers to the
 * transport from those buffers, instead of copying them into its own array first. The stream
 * retains each such buffer until it is closed.
 */
public class GrpcMarshallerGatherTest {

    private static final MethodDescriptor.Marshaller<GrpcPayload> MARSHALLER =
            EchoServiceGrpc.getEchoMethod().getRequestMarshaller();

    private static final int LARGE = 64 * 1024;

    @ParameterizedTest
    @ValueSource(ints = {LightProtoCodec.GATHER_MIN - 1, LightProtoCodec.GATHER_MIN, LARGE})
    void testLargeValueIsWrittenFromItsArray(int size) throws Exception {
        byte[] data = random(size, 1);
        GrpcPayload msg = new GrpcPayload().setName("gather").setData(data);
        byte[] expected = msg.toByteArray();

        InputStream stream = MARSHALLER.stream(msg);
        assertEquals(expected.length, stream.available());
        RecordingOutputStream out = drainAndClose(stream);
        assertArrayEquals(expected, out.toByteArray());
        assertEquals(size >= LightProtoCodec.GATHER_MIN, out.wrote(data));
    }

    @Test
    void testEveryBytesFieldKindIsGathered() throws Exception {
        List<byte[]> large = new ArrayList<>();
        GrpcPayload msg = everyFieldKind(large);
        byte[] expected = msg.toByteArray();

        RecordingOutputStream out = drainAndClose(MARSHALLER.stream(msg));
        assertArrayEquals(expected, out.toByteArray());
        for (byte[] value : large) {
            assertTrue(out.wrote(value));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 4096, 100 * 1024})
    void testReadInsteadOfDrain(int chunkSize) throws Exception {
        // The in-process transport reads the stream instead of draining it
        GrpcPayload msg = everyFieldKind(new ArrayList<>());
        byte[] expected = msg.toByteArray();
        InputStream stream = MARSHALLER.stream(msg);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(stream.read());
        byte[] chunk = new byte[chunkSize];
        int n;
        while ((n = stream.read(chunk, 0, chunk.length)) > 0) {
            out.write(chunk, 0, n);
            assertEquals(expected.length - out.size(), stream.available());
        }
        assertEquals(-1, n);
        assertEquals(-1, stream.read());
        assertEquals(0, stream.read(chunk, 0, 0));
        stream.close();

        assertArrayEquals(expected, out.toByteArray());
    }

    @Test
    void testReadByteByByte() throws Exception {
        GrpcPayload msg = everyFieldKind(new ArrayList<>());
        byte[] expected = msg.toByteArray();
        byte[] read = new byte[expected.length];
        try (InputStream stream = MARSHALLER.stream(msg)) {
            for (int i = 0; i < read.length; i++) {
                int b = stream.read();
                assertTrue(b >= 0);
                read[i] = (byte) b;
            }
            assertEquals(-1, stream.read());
        }
        assertArrayEquals(expected, read);
    }

    @Test
    void testDrainAfterPartialRead() throws Exception {
        GrpcPayload msg = everyFieldKind(new ArrayList<>());
        byte[] expected = msg.toByteArray();
        InputStream stream = MARSHALLER.stream(msg);

        // Stop inside the first gathered value
        byte[] head = new byte[LightProtoCodec.GATHER_MIN];
        assertEquals(head.length, stream.readNBytes(head, 0, head.length));
        RecordingOutputStream out = new RecordingOutputStream();
        out.write(head, 0, head.length);
        assertEquals(expected.length - head.length, ((Drainable) stream).drainTo(out));
        assertEquals(0, stream.available());
        stream.close();

        assertArrayEquals(expected, out.toByteArray());
    }

    @Test
    void testRoundTripThroughTheMarshaller() {
        GrpcPayload msg = everyFieldKind(new ArrayList<>());
        assertEquals(msg, MARSHALLER.parse(MARSHALLER.stream(msg)));
    }

    @Test
    void testCallerMayReleaseTheBufferAfterStream() throws Exception {
        ByteBuf data = PooledByteBufAllocator.DEFAULT.heapBuffer(LARGE).writeBytes(random(LARGE, 1));
        GrpcPayload msg = new GrpcPayload().setName("released").setData(data);
        byte[] expected = msg.toByteArray();

        InputStream stream = MARSHALLER.stream(msg);
        assertEquals(2, data.refCnt());
        data.release();
        assertEquals(1, data.refCnt());

        assertArrayEquals(expected, drainAndClose(stream).toByteArray());
        assertEquals(0, data.refCnt());
        // A second close releases nothing
        stream.close();
    }

    @Test
    void testCloseWithoutDrainReleasesTheBuffers() throws Exception {
        ByteBuf data = Unpooled.buffer(LARGE).writeBytes(random(LARGE, 1));
        InputStream stream = MARSHALLER.stream(new GrpcPayload().setData(data));
        assertEquals(2, data.refCnt());

        stream.close();
        assertEquals(1, data.refCnt());
        assertEquals(0, stream.available());
        assertEquals(-1, stream.read());
        assertEquals(0, stream.readAllBytes().length);
        assertEquals(0, ((Drainable) stream).drainTo(new ByteArrayOutputStream()));
        stream.close();
        assertEquals(1, data.refCnt());
    }

    @Test
    void testParsedMessageIsGatheredFromItsBuffer() throws Exception {
        // Forwarding a message parsed from a pooled buffer: its large values, and its string
        // that was never decoded, are written from that buffer
        GrpcPayload original = new GrpcPayload().setName(new String(new char[LARGE]).replace('\0', 's'))
                .setData(random(LARGE, 1));
        byte[] expected = original.toByteArray();
        ByteBuf buffer = PooledByteBufAllocator.DEFAULT.heapBuffer(expected.length).writeBytes(expected);
        GrpcPayload parsed = new GrpcPayload();
        parsed.parseFrom(buffer, buffer.readableBytes());

        byte[] memory = buffer.array();

        InputStream stream = MARSHALLER.stream(parsed);
        assertEquals(3, buffer.refCnt());
        buffer.release();

        RecordingOutputStream out = drainAndClose(stream);
        assertArrayEquals(expected, out.toByteArray());
        assertTrue(out.wrote(memory));
        assertEquals(0, buffer.refCnt());
    }

    @Test
    void testDirectBufferIsCopied() throws Exception {
        ByteBuf data = Unpooled.directBuffer(LARGE).writeBytes(random(LARGE, 1));
        GrpcPayload msg = new GrpcPayload().setData(data);
        byte[] expected = msg.toByteArray();

        InputStream stream = MARSHALLER.stream(msg);
        assertEquals(1, data.refCnt());
        assertArrayEquals(expected, drainAndClose(stream).toByteArray());
        assertEquals(1, data.refCnt());
        data.release();
    }

    @Test
    void testMessageMayChangeAfterStream() throws Exception {
        GrpcPayload msg = new GrpcPayload().setName("first").setData(random(LARGE, 1));
        byte[] expected = msg.toByteArray();
        InputStream stream = MARSHALLER.stream(msg);

        msg.clear();
        msg.setName("second").setData(random(LARGE, 2));
        assertArrayEquals(expected, drainAndClose(stream).toByteArray());
    }

    @Test
    void testFailedWriteReleasesTheGatheredBuffers() throws Exception {
        ByteBuf data = Unpooled.buffer(LARGE).writeBytes(random(LARGE, 1));
        ByteBuf released = Unpooled.buffer(LARGE).writeBytes(random(LARGE, 2));
        GrpcPayload msg = new GrpcPayload().setData(data);
        msg.setNested().setValue(released);
        released.release();

        // data is gathered before the nested value fails
        assertThrows(IllegalReferenceCountException.class, () -> MARSHALLER.stream(msg));
        assertEquals(1, data.refCnt());

        // The thread stops gathering: other writes copy their large values again
        GrpcPayload next = new GrpcPayload().setData(random(LARGE, 3));
        GrpcPayload parsed = new GrpcPayload();
        parsed.parseFrom(next.toByteArray());
        assertArrayEquals(next.getData(), parsed.getData());
        assertArrayEquals(next.toByteArray(), drainAndClose(MARSHALLER.stream(next)).toByteArray());
    }

    @Test
    void testOtherWritesAreNotGathered() {
        // Only the marshaller gathers: writeTo() and toByteArray() copy their large values
        byte[] data = random(LARGE, 1);
        GrpcPayload msg = new GrpcPayload().setData(data);
        ByteBuf heap = Unpooled.buffer();
        msg.writeTo(heap);
        GrpcPayload parsed = new GrpcPayload();
        parsed.parseFrom(heap, heap.readableBytes());
        assertArrayEquals(data, parsed.getData());
        parsed.parseFrom(msg.toByteArray());
        assertArrayEquals(data, parsed.getData());
    }

    /** A message with a large value in every kind of bytes field, among small fields. */
    private static GrpcPayload everyFieldKind(List<byte[]> large) {
        GrpcPayload msg = new GrpcPayload().setName("every-kind").setData(add(large, random(LARGE, 1)));
        msg.setNested().setKey("nested").setValue(add(large, random(LARGE, 2))).setVersion(3);
        msg.addTag("tag");
        msg.addChunk(random(100, 4));
        msg.addChunk(add(large, random(LARGE, 5)));
        msg.addItem().setKey("item-0").setValue(add(large, random(LARGE, 6))).setVersion(6);
        msg.addItem().setKey("item-1").setValue(random(10, 7));
        msg.putLabels("label", "value");
        msg.putBlobs("blob-0", add(large, random(LARGE, 8)));
        msg.putBlobs("blob-1", random(5, 9));
        msg.putIndex("index").setKey("indexed").setValue(add(large, random(LARGE, 10)));
        return msg;
    }

    private static byte[] add(List<byte[]> list, byte[] value) {
        list.add(value);
        return value;
    }

    private static byte[] random(int size, int seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static RecordingOutputStream drainAndClose(InputStream stream) throws IOException {
        RecordingOutputStream out = new RecordingOutputStream();
        try (stream) {
            assertEquals(stream.available(), ((Drainable) stream).drainTo(out));
            assertEquals(0, stream.available());
        }
        return out;
    }

    /** Drain target that records every array the stream writes from. */
    private static final class RecordingOutputStream extends ByteArrayOutputStream {
        final List<byte[]> arrays = new ArrayList<>();

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            arrays.add(b);
            super.write(b, off, len);
        }

        boolean wrote(byte[] array) {
            return arrays.stream().anyMatch(a -> a == array);
        }
    }
}
