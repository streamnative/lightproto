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
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A marshalled stream that is closed before it was read to the end keeps its bytes, as someone
 * else may still read it: a proxy that forwards the stream it received closes it once the
 * in-process transport has taken it, and the peer reads it later. The sizes below take the
 * stream that copies the value into its array, and the one that writes it from its buffer
 * (at least GATHER_MIN), which releases the buffer on that early close.
 */
public class GrpcMarshallerEarlyCloseTest {

    private static final MethodDescriptor.Marshaller<GrpcPayload> MARSHALLER =
            EchoServiceGrpc.getEchoMethod().getRequestMarshaller();

    private static final int LARGE = 64 * 1024;

    @ParameterizedTest
    @ValueSource(ints = {100, LARGE})
    void testParseAfterClose(int size) throws Exception {
        ByteBuf data = heapBuffer(size);
        GrpcPayload msg = message(data);

        InputStream stream = MARSHALLER.stream(msg);
        stream.close();
        assertEquals(1, data.refCnt());

        assertEquals(msg, MARSHALLER.parse(stream));
        assertEquals(1, data.refCnt());
    }

    @ParameterizedTest
    @ValueSource(ints = {100, LARGE})
    void testDrainThenClose(int size) throws Exception {
        ByteBuf data = heapBuffer(size);
        GrpcPayload msg = message(data);
        byte[] expected = msg.toByteArray();

        InputStream stream = MARSHALLER.stream(msg);
        assertEquals(size >= LightProtoCodec.GATHER_MIN ? 2 : 1, data.refCnt());
        assertArrayEquals(expected, drain(stream));
        stream.close();
        assertEquals(1, data.refCnt());
        assertEquals(0, stream.available());
        assertEquals(-1, stream.read());

        stream.close();
        assertEquals(1, data.refCnt());
    }

    @ParameterizedTest
    @ValueSource(ints = {100, LARGE})
    void testCloseThenDrainThenClose(int size) throws Exception {
        ByteBuf data = heapBuffer(size);
        GrpcPayload msg = message(data);
        byte[] expected = msg.toByteArray();

        InputStream stream = MARSHALLER.stream(msg);
        stream.close();
        stream.close();
        assertEquals(1, data.refCnt());
        assertEquals(expected.length, stream.available());

        assertArrayEquals(expected, drain(stream));
        stream.close();
        assertEquals(1, data.refCnt());
        assertEquals(0, stream.available());
        assertEquals(-1, stream.read());

        stream.close();
        assertEquals(1, data.refCnt());
    }

    @ParameterizedTest
    @ValueSource(ints = {100, LARGE})
    void testCloseAfterPartialRead(int size) throws Exception {
        ByteBuf data = heapBuffer(size);
        GrpcPayload msg = message(data);
        byte[] expected = msg.toByteArray();

        InputStream stream = MARSHALLER.stream(msg);
        // Stop inside the value
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(stream.readNBytes(expected.length / 2));
        stream.close();
        assertEquals(1, data.refCnt());
        assertEquals(expected.length - out.size(), stream.available());

        assertEquals(expected.length - out.size(), ((Drainable) stream).drainTo(out));
        stream.close();
        assertArrayEquals(expected, out.toByteArray());
        assertEquals(1, data.refCnt());
    }

    @Test
    void testBufferMayChangeAfterClose() throws Exception {
        // After close() the caller may change its buffer: a stream closed early has copied it
        ByteBuf data = heapBuffer(LARGE);
        GrpcPayload msg = message(data);
        byte[] expected = msg.toByteArray();

        InputStream stream = MARSHALLER.stream(msg);
        stream.close();
        data.setZero(0, data.capacity());

        assertArrayEquals(expected, drain(stream));
        stream.close();
    }

    @Test
    void testCloseWhileAnotherThreadReads() throws Exception {
        // The proxy closes the stream on its own thread while the in-process peer reads it
        byte[] data = new byte[1024 * 1024];
        new Random(1).nextBytes(data);
        GrpcPayload msg = new GrpcPayload().setName("concurrent").setData(data);

        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            for (int i = 0; i < 200; i++) {
                InputStream stream = MARSHALLER.stream(msg);
                CountDownLatch started = new CountDownLatch(1);
                CompletableFuture<GrpcPayload> parsed = CompletableFuture.supplyAsync(() -> {
                    started.countDown();
                    return MARSHALLER.parse(stream);
                }, reader);
                started.await();
                stream.close();
                assertEquals(msg, parsed.get(30, TimeUnit.SECONDS));
            }
        } finally {
            reader.shutdownNow();
        }
    }

    private static GrpcPayload message(ByteBuf data) {
        return new GrpcPayload().setName("early-close").setData(data);
    }

    private static ByteBuf heapBuffer(int size) {
        return Unpooled.buffer(size).writeBytes(random(size));
    }

    private static byte[] random(int size) {
        byte[] b = new byte[size];
        new Random(size).nextBytes(b);
        return b;
    }

    private static byte[] drain(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(stream.available(), ((Drainable) stream).drainTo(out));
        assertEquals(0, stream.available());
        return out.toByteArray();
    }
}
