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
import io.grpc.KnownLength;
import io.grpc.MethodDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the gRPC marshaller returns a leak-proof, Drainable stream.
 * The marshaller serializes into a byte[]-backed DrainableByteArrayInputStream. Closing
 * the stream hands its array to the closing thread, which serializes its next message
 * into it. The array is not ref-counted, so a stream that gRPC drops without closing only
 * leaves it to the garbage collector. (Large heap bytes values are written from their own
 * buffers, which the stream retains: see GrpcMarshallerGatherTest.)
 */
public class GrpcMarshallerBufferLeakTest {

    private static final MethodDescriptor.Marshaller<GrpcRequest> MARSHALLER =
            TestServiceGrpc.getUnaryMethod().getRequestMarshaller();

    // Larger than the 4 MiB that a thread keeps for reuse
    private static final int OVERSIZED_PAYLOAD = 5 * 1024 * 1024;

    @Test
    void testMarshallerStreamIsDrainableAndKnownLength() throws Exception {
        MethodDescriptor.Marshaller<GrpcRequest> marshaller =
                TestServiceGrpc.getUnaryMethod().getRequestMarshaller();

        GrpcRequest request = new GrpcRequest();
        request.setName("leak-test");
        request.setValue(42);

        InputStream stream = marshaller.stream(request);

        // GC-safe: ByteArrayInputStream-based, no ref-counted resources
        assertTrue(stream instanceof ByteArrayInputStream,
                "Expected ByteArrayInputStream but got " + stream.getClass().getName());

        // Drainable for zero-copy writes into gRPC's MessageFramer
        assertTrue(stream instanceof Drainable,
                "Expected Drainable but got " + stream.getClass().getName());

        // KnownLength so gRPC can pre-allocate the right buffer size
        assertTrue(stream instanceof KnownLength,
                "Expected KnownLength but got " + stream.getClass().getName());

        // Verify drainTo produces correct bytes
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int drained = ((Drainable) stream).drainTo(out);
        assertTrue(drained > 0, "drainTo should write bytes");
        assertEquals(0, stream.available(), "Stream should be fully drained");

        // Verify the drained bytes can be parsed back
        GrpcRequest parsed = marshaller.parse(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("leak-test", parsed.getName());
        assertEquals(42, parsed.getValue());
    }

    @Test
    void testMarshallerRoundTrip() {
        MethodDescriptor.Marshaller<GrpcRequest> marshaller =
                TestServiceGrpc.getUnaryMethod().getRequestMarshaller();

        GrpcRequest original = new GrpcRequest();
        original.setName("round-trip");
        original.setValue(7);

        InputStream stream = marshaller.stream(original);
        GrpcRequest parsed = marshaller.parse(stream);

        assertEquals("round-trip", parsed.getName());
        assertEquals(7, parsed.getValue());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 100, 128 * 1024, OVERSIZED_PAYLOAD})
    void testDrainedBytesMatchSerialization(int payloadSize) throws Exception {
        GrpcRequest request = request("msg-0", payloadSize);
        InputStream stream = MARSHALLER.stream(request);
        assertEquals(request.getSerializedSize(), stream.available());

        RecordingOutputStream out = drainAndClose(stream);
        assertArrayEquals(request.toByteArray(), out.toByteArray());

        GrpcRequest parsed = MARSHALLER.parse(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("msg-0", parsed.getName());
        assertEquals(payloadSize, parsed.getValue());
        assertArrayEquals(request.getPayload(), parsed.getPayload());
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 128 * 1024})
    void testReadInsteadOfDrain(int payloadSize) throws Exception {
        // The in-process transport reads the stream instead of draining it
        GrpcRequest request = request("msg-0", payloadSize);
        byte[] expected = request.toByteArray();
        InputStream stream = MARSHALLER.stream(request);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(stream.read());
        byte[] chunk = new byte[50];
        int n = stream.read(chunk, 0, chunk.length);
        out.write(chunk, 0, n);
        assertEquals(expected.length - 1 - n, stream.available());
        out.write(stream.readAllBytes());
        assertEquals(-1, stream.read());
        assertEquals(-1, stream.read(chunk, 0, chunk.length));
        stream.close();

        assertArrayEquals(expected, out.toByteArray());
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 128 * 1024})
    void testArrayIsReusedAfterDrainAndClose(int payloadSize) throws Exception {
        byte[] array = drainAndClose(MARSHALLER.stream(request("msg-0", payloadSize))).array;

        GrpcRequest next = request("msg-1", payloadSize);
        RecordingOutputStream out = drainAndClose(MARSHALLER.stream(next));
        assertSame(array, out.array);
        assertArrayEquals(next.toByteArray(), out.toByteArray());
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 128 * 1024})
    void testArrayIsReusedAfterCloseWithoutDrain(int payloadSize) throws Exception {
        byte[] array = drainAndClose(MARSHALLER.stream(request("msg-0", payloadSize))).array;

        InputStream stream = MARSHALLER.stream(request("msg-1", payloadSize));
        stream.close();
        assertEquals(0, stream.available());
        assertEquals(-1, stream.read());
        assertEquals(0, stream.readAllBytes().length);
        assertEquals(0, ((Drainable) stream).drainTo(new ByteArrayOutputStream()));

        GrpcRequest next = request("msg-2", payloadSize);
        RecordingOutputStream out = drainAndClose(MARSHALLER.stream(next));
        assertSame(array, out.array);
        assertArrayEquals(next.toByteArray(), out.toByteArray());
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 128 * 1024})
    void testArrayIsReusedAfterFailedDrain(int payloadSize) throws Exception {
        byte[] array = drainAndClose(MARSHALLER.stream(request("msg-0", payloadSize))).array;

        InputStream stream = MARSHALLER.stream(request("msg-1", payloadSize));
        FailingOutputStream target = new FailingOutputStream();
        assertThrows(IOException.class, () -> ((Drainable) stream).drainTo(target));
        assertSame(array, target.array);
        // gRPC closes the stream whether or not writing it succeeded
        stream.close();

        GrpcRequest next = request("msg-2", payloadSize);
        RecordingOutputStream out = drainAndClose(MARSHALLER.stream(next));
        assertSame(array, out.array);
        assertArrayEquals(next.toByteArray(), out.toByteArray());
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 128 * 1024})
    void testArrayIsNotReusedBeforeClose(int payloadSize) throws Exception {
        byte[] array = drainAndClose(MARSHALLER.stream(request("msg-0", payloadSize))).array;

        // The in-process transport, and channels that are still connecting, read a stream
        // after the sending thread has serialized further messages
        GrpcRequest pending = request("msg-1", payloadSize);
        InputStream pendingStream = MARSHALLER.stream(pending);

        GrpcRequest next = request("msg-2", payloadSize);
        RecordingOutputStream nextOut = drainAndClose(MARSHALLER.stream(next));
        assertNotSame(array, nextOut.array);
        assertArrayEquals(next.toByteArray(), nextOut.toByteArray());

        RecordingOutputStream pendingOut = drainAndClose(pendingStream);
        assertSame(array, pendingOut.array);
        assertArrayEquals(pending.toByteArray(), pendingOut.toByteArray());
    }

    @Test
    void testSecondCloseDoesNotHandOutTheArrayAgain() throws Exception {
        InputStream closedTwice = MARSHALLER.stream(request("msg-0", 100));
        byte[] array = drainAndClose(closedTwice).array;

        GrpcRequest pending = request("msg-1", 100);
        InputStream pendingStream = MARSHALLER.stream(pending);
        closedTwice.close();

        assertNotSame(array, drainAndClose(MARSHALLER.stream(request("msg-2", 100))).array);
        assertArrayEquals(pending.toByteArray(), drainAndClose(pendingStream).toByteArray());
    }

    @Test
    void testOversizedArrayIsNotKept() throws Exception {
        byte[] array = drainAndClose(MARSHALLER.stream(request("msg-0", OVERSIZED_PAYLOAD))).array;
        assertNotSame(array, drainAndClose(MARSHALLER.stream(request("msg-1", OVERSIZED_PAYLOAD))).array);
    }

    @Test
    void testStreamThatIsNeverClosedLeavesItsArrayToTheGarbageCollector() throws Exception {
        // gRPC can drop a stream without closing it (the in-process transport does once the
        // call has closed): nothing else may hold its array
        drainAndClose(MARSHALLER.stream(request("msg-0", 128 * 1024)));
        WeakReference<byte[]> array = drainWithoutClosing(MARSHALLER.stream(request("msg-1", 128 * 1024)));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (array.get() != null) {
            assertTrue(System.nanoTime() < deadline, "array of the dropped stream was not collected");
            System.gc();
            Thread.sleep(10);
        }

        GrpcRequest next = request("msg-2", 128 * 1024);
        assertArrayEquals(next.toByteArray(), drainAndClose(MARSHALLER.stream(next)).toByteArray());
    }

    private static GrpcRequest request(String name, int payloadSize) {
        byte[] payload = new byte[payloadSize];
        new Random(name.hashCode()).nextBytes(payload);
        return new GrpcRequest().setName(name).setValue(payloadSize).setPayload(payload);
    }

    private static RecordingOutputStream drainAndClose(InputStream stream) throws IOException {
        RecordingOutputStream out = new RecordingOutputStream();
        try (stream) {
            assertEquals(stream.available(), ((Drainable) stream).drainTo(out));
            assertEquals(0, stream.available());
        }
        return out;
    }

    private static WeakReference<byte[]> drainWithoutClosing(InputStream stream) throws IOException {
        RecordingOutputStream out = new RecordingOutputStream();
        ((Drainable) stream).drainTo(out);
        return new WeakReference<>(out.array);
    }

    /**
     * Drain target that records the array the stream writes from first: the marshaller's own
     * array, which the stream also writes large payloads around.
     */
    private static final class RecordingOutputStream extends ByteArrayOutputStream {
        byte[] array;

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            if (array == null) {
                array = b;
            }
            super.write(b, off, len);
        }
    }

    private static final class FailingOutputStream extends OutputStream {
        byte[] array;

        @Override
        public void write(int b) throws IOException {
            throw new IOException("write failed");
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            array = b;
            throw new IOException("write failed");
        }
    }
}
