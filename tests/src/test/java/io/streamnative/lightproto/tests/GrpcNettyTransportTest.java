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

import io.grpc.CallOptions;
import io.grpc.HasByteBuffer;
import io.grpc.KnownLength;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the generated stubs over a real Netty transport on localhost. Unlike the in-process
 * transport, which passes the stream returned by stream() straight to parse(), it hands parse()
 * a stream over the transport buffers the message was received in.
 */
public class GrpcNettyTransportTest {

    private static final String ONE_BUFFER = "one buffer";
    private static final String SEVERAL_BUFFERS = "several buffers";
    // Since gRPC 1.82.4 the deframer merges a run of small buffers that a large one follows into a
    // heap array without ByteBuffer access, so the marshaller copies a message that starts with one
    private static final String MERGED_BUFFERS = "merged buffers";

    private final List<GrpcPayload> serverReceived = new CopyOnWriteArrayList<>();
    private final InspectingMarshaller responseMarshaller =
            new InspectingMarshaller(EchoServiceGrpc.getEchoMethod().getResponseMarshaller());
    private final MethodDescriptor<GrpcPayload, GrpcPayload> inspectedEchoMethod = EchoServiceGrpc.getEchoMethod()
            .toBuilder(EchoServiceGrpc.getEchoMethod().getRequestMarshaller(), responseMarshaller)
            .build();
    private final MethodDescriptor<GrpcPayload, GrpcPayload> inspectedEchoStreamMethod =
            EchoServiceGrpc.getEchoStreamMethod()
                    .toBuilder(EchoServiceGrpc.getEchoStreamMethod().getRequestMarshaller(), responseMarshaller)
                    .build();
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws IOException {
        server = NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                .addService(new EchoServiceImpl())
                .build()
                .start();
        channel = NettyChannelBuilder.forAddress(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getPort()))
                .usePlaintext()
                .build();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
    }

    @Test
    void testSmallMessages() {
        List<GrpcPayload> sent = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            GrpcPayload request = GrpcPayloads.create(i, 100);
            sent.add(request);
            assertEquals(request, echo(request));
        }
        assertEquals(sent, serverReceived);
        // Each response was received whole in a single buffer, which parse() reads in place
        assertEquals(Collections.nCopies(sent.size(), ONE_BUFFER), responseMarshaller.shapes);
        assertEquals(Collections.nCopies(sent.size(), 0), responseMarshaller.bytesCopied);
    }

    @Test
    void testMessagesLargerThanAFrame() {
        List<GrpcPayload> sent = new ArrayList<>();
        for (int dataSize : new int[] {16 * 1024, 64 * 1024, 1024 * 1024}) {
            GrpcPayload request = GrpcPayloads.create(dataSize, dataSize);
            sent.add(request);
            assertEquals(request, echo(request));
        }
        assertEquals(sent, serverReceived);
        // HTTP/2 DATA frames carry at most 16 KiB by default, so each response spans several buffers,
        // which parse() also reads in place
        assertEquals(Collections.nCopies(sent.size(), SEVERAL_BUFFERS), responseMarshaller.shapes);
        assertEquals(Collections.nCopies(sent.size(), 0), responseMarshaller.bytesCopied);
    }

    @Test
    void testMessagesOutliveTheirTransportBuffers() throws Exception {
        // The pooled buffers that early messages were parsed from get reused for later ones,
        // which would corrupt any field that still referred to them
        int[] dataSizes = {10, 100, 1000, 20 * 1024};
        List<GrpcPayload> sent = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            sent.add(GrpcPayloads.create(i, dataSizes[i % dataSizes.length]));
        }

        List<GrpcPayload> received = echoStream(EchoServiceGrpc.newStub(channel)::echoStream, sent);

        assertEquals(sent.size(), serverReceived.size());
        assertEquals(sent.size(), received.size());
        for (int i = 0; i < sent.size(); i++) {
            assertEquals(sent.get(i), serverReceived.get(i), "Request " + i);
            assertEquals(sent.get(i), received.get(i), "Response " + i);
        }
    }

    @Test
    void testMessagesAcrossFramesOutliveTheirTransportBuffers() throws Exception {
        // Each message is parsed in place from several pooled buffers, which later messages reuse.
        // The sizes vary so that the frame boundaries fall on different fields
        List<GrpcPayload> sent = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            sent.add(GrpcPayloads.create(i, 16 * 1024 + i * 1531));
        }

        List<GrpcPayload> received = echoStream(responses -> ClientCalls.asyncBidiStreamingCall(
                channel.newCall(inspectedEchoStreamMethod, CallOptions.DEFAULT), responses), sent);

        assertEquals(sent.size(), serverReceived.size());
        assertEquals(sent.size(), received.size());
        for (int i = 0; i < sent.size(); i++) {
            assertEquals(sent.get(i), serverReceived.get(i), "Request " + i);
            assertEquals(sent.get(i), received.get(i), "Response " + i);
        }
        int inPlace = 0;
        for (int i = 0; i < sent.size(); i++) {
            int copied = responseMarshaller.bytesCopied.get(i);
            if (responseMarshaller.shapes.get(i).equals(SEVERAL_BUFFERS)) {
                assertEquals(0, copied, "Response " + i);
                inPlace++;
            } else {
                assertEquals(MERGED_BUFFERS, responseMarshaller.shapes.get(i), "Response " + i);
                assertEquals(sent.get(i).getSerializedSize(), copied, "Response " + i);
            }
        }
        assertTrue(inPlace > sent.size() / 2, inPlace + " of " + sent.size() + " responses were parsed in place");
    }

    private GrpcPayload echo(GrpcPayload request) {
        return ClientCalls.blockingUnaryCall(channel, inspectedEchoMethod,
                CallOptions.DEFAULT.withDeadlineAfter(30, TimeUnit.SECONDS), request);
    }

    /** Sends the requests on an EchoStream call and returns the responses once the call completes. */
    private static List<GrpcPayload> echoStream(
            Function<StreamObserver<GrpcPayload>, StreamObserver<GrpcPayload>> call, List<GrpcPayload> requests)
            throws Exception {
        List<GrpcPayload> received = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        StreamObserver<GrpcPayload> requestObserver = call.apply(new StreamObserver<GrpcPayload>() {
            @Override
            public void onNext(GrpcPayload value) {
                received.add(value);
            }

            @Override
            public void onError(Throwable t) {
                done.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
                done.complete(null);
            }
        });
        requests.forEach(requestObserver::onNext);
        requestObserver.onCompleted();
        done.get(30, TimeUnit.SECONDS);
        return received;
    }

    /**
     * Records how the transport hands each message to parse(), then parses it with the generated marshaller,
     * counting the bytes it copies out of the transport buffers.
     */
    private static final class InspectingMarshaller implements MethodDescriptor.Marshaller<GrpcPayload> {
        private final MethodDescriptor.Marshaller<GrpcPayload> delegate;
        final List<String> shapes = new CopyOnWriteArrayList<>();
        final List<Integer> bytesCopied = new CopyOnWriteArrayList<>();

        InspectingMarshaller(MethodDescriptor.Marshaller<GrpcPayload> delegate) {
            this.delegate = delegate;
        }

        @Override
        public InputStream stream(GrpcPayload value) {
            return delegate.stream(value);
        }

        @Override
        public GrpcPayload parse(InputStream stream) {
            shapes.add(shapeOf(stream));
            CountingStream counting = new CountingStream(stream);
            GrpcPayload parsed = delegate.parse(counting);
            bytesCopied.add(counting.bytesRead);
            return parsed;
        }

        private static String shapeOf(InputStream stream) {
            if (!(stream instanceof KnownLength && stream instanceof HasByteBuffer)) {
                return stream.getClass().getName();
            }
            if (!((HasByteBuffer) stream).byteBufferSupported()) {
                return MERGED_BUFFERS;
            }
            try {
                ByteBuffer first = ((HasByteBuffer) stream).getByteBuffer();
                return first != null && first.remaining() == stream.available() ? ONE_BUFFER : SEVERAL_BUFFERS;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Passes a transport stream on to the marshaller, counting the bytes read out of it. */
    private static final class CountingStream extends FilterInputStream implements KnownLength, HasByteBuffer {
        int bytesRead;

        CountingStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            bytesRead += b < 0 ? 0 : 1;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            bytesRead += Math.max(n, 0);
            return n;
        }

        @Override
        public boolean byteBufferSupported() {
            return in instanceof HasByteBuffer && ((HasByteBuffer) in).byteBufferSupported();
        }

        @Override
        public ByteBuffer getByteBuffer() {
            return ((HasByteBuffer) in).getByteBuffer();
        }
    }

    private final class EchoServiceImpl extends EchoServiceGrpc.EchoServiceImplBase {
        @Override
        public void echo(GrpcPayload request, StreamObserver<GrpcPayload> responseObserver) {
            serverReceived.add(request);
            responseObserver.onNext(request);
            responseObserver.onCompleted();
        }

        @Override
        public StreamObserver<GrpcPayload> echoStream(StreamObserver<GrpcPayload> responseObserver) {
            return new StreamObserver<GrpcPayload>() {
                @Override
                public void onNext(GrpcPayload value) {
                    serverReceived.add(value);
                    responseObserver.onNext(value);
                }

                @Override
                public void onError(Throwable t) {
                    responseObserver.onError(t);
                }

                @Override
                public void onCompleted() {
                    responseObserver.onCompleted();
                }
            };
        }
    }
}
