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
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Calls a service through a proxy that forwards the streams it receives as they are, like
 * Apache BookKeeper's gRPC proxy: an identity InputStream marshaller, and call listeners that
 * pass each message on. gRPC closes the stream it gave the proxy once onMessage() returns, but
 * the in-process transport hands that same stream to the next hop, which reads it later on its
 * own executor. Payloads of at least GATHER_MIN take the gathering stream.
 */
public class GrpcProxyTest {

    private static final int CALLS = 10;
    private static final int STREAMED_MESSAGES = 10;

    private final List<GrpcRequest> received = Collections.synchronizedList(new ArrayList<>());
    private final List<GrpcResponse> sent = Collections.synchronizedList(new ArrayList<>());

    private Server backend;
    private ManagedChannel backendChannel;
    private Server proxy;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws IOException {
        // No directExecutor() anywhere: each hop reads its messages on its own executor, after
        // the proxy has closed them
        String backendName = InProcessServerBuilder.generateName();
        backend = InProcessServerBuilder.forName(backendName)
                .addService(new Backend())
                .build()
                .start();
        backendChannel = InProcessChannelBuilder.forName(backendName).build();

        String proxyName = InProcessServerBuilder.generateName();
        proxy = InProcessServerBuilder.forName(proxyName)
                .addService(proxyService(backendChannel))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(proxyName).build();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
        proxy.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
        backendChannel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
        backend.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @ValueSource(ints = {100, LightProtoCodec.GATHER_MIN, 2 * 1024 * 1024})
    void testUnary(int payloadSize) {
        TestServiceGrpc.TestServiceBlockingStub stub = TestServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(30, TimeUnit.SECONDS);
        List<GrpcRequest> requests = new ArrayList<>();
        List<GrpcResponse> responses = new ArrayList<>();
        for (int i = 0; i < CALLS; i++) {
            GrpcRequest request = request("unary-" + i, payloadSize);
            requests.add(request);
            responses.add(stub.unary(request));
        }

        assertEquals(requests, received);
        assertEquals(sent, responses);
    }

    @ParameterizedTest
    @ValueSource(ints = {100, LightProtoCodec.GATHER_MIN, 2 * 1024 * 1024})
    void testServerStreaming(int payloadSize) {
        GrpcRequest request = request("server-stream", payloadSize);
        List<GrpcResponse> responses = new ArrayList<>();
        TestServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(30, TimeUnit.SECONDS)
                .serverStream(request)
                .forEachRemaining(responses::add);

        assertEquals(List.of(request), received);
        assertEquals(STREAMED_MESSAGES, responses.size());
        assertEquals(sent, responses);
    }

    private static GrpcRequest request(String name, int payloadSize) {
        byte[] payload = new byte[payloadSize];
        new Random(name.hashCode()).nextBytes(payload);
        return new GrpcRequest().setName(name).setValue(payloadSize).setPayload(payload);
    }

    /** The service's methods, taking and returning the streams of the calls they forward. */
    private static ServerServiceDefinition proxyService(Channel backend) {
        ServerServiceDefinition.Builder service = ServerServiceDefinition.builder(TestServiceGrpc.SERVICE_NAME);
        for (MethodDescriptor<?, ?> method : TestServiceGrpc.getServiceDescriptor().getMethods()) {
            service.addMethod(
                    MethodDescriptor.newBuilder(IdentityMarshaller.INSTANCE, IdentityMarshaller.INSTANCE)
                            .setFullMethodName(method.getFullMethodName())
                            .setType(method.getType())
                            .build(),
                    (serverCall, headers) -> forward(serverCall, headers, backend));
        }
        return service.build();
    }

    private static ServerCall.Listener<InputStream> forward(
            ServerCall<InputStream, InputStream> serverCall, Metadata headers, Channel backend) {
        ClientCall<InputStream, InputStream> clientCall =
                backend.newCall(serverCall.getMethodDescriptor(), CallOptions.DEFAULT);
        clientCall.start(new ClientCall.Listener<InputStream>() {
            @Override
            public void onHeaders(Metadata headers) {
                serverCall.sendHeaders(headers);
            }

            @Override
            public void onMessage(InputStream message) {
                serverCall.sendMessage(message);
                clientCall.request(1);
            }

            @Override
            public void onClose(Status status, Metadata trailers) {
                serverCall.close(status, trailers);
            }
        }, headers);
        serverCall.request(1);
        clientCall.request(1);
        return new ServerCall.Listener<InputStream>() {
            @Override
            public void onMessage(InputStream message) {
                clientCall.sendMessage(message);
                serverCall.request(1);
            }

            @Override
            public void onHalfClose() {
                clientCall.halfClose();
            }

            @Override
            public void onCancel() {
                clientCall.cancel("Server cancelled", null);
            }
        };
    }

    private static final class IdentityMarshaller implements MethodDescriptor.Marshaller<InputStream> {
        static final IdentityMarshaller INSTANCE = new IdentityMarshaller();

        @Override
        public InputStream stream(InputStream value) {
            return value;
        }

        @Override
        public InputStream parse(InputStream stream) {
            return stream;
        }
    }

    /** Records the requests it receives and the responses it sends. */
    private final class Backend extends TestServiceGrpc.TestServiceImplBase {
        @Override
        public void unary(GrpcRequest request, StreamObserver<GrpcResponse> responseObserver) {
            received.add(request);
            send(responseObserver, new GrpcResponse()
                    .setMessage("echo: " + request.getName())
                    .setResult(request.getValue())
                    .setPayload(request.getPayload()));
            responseObserver.onCompleted();
        }

        @Override
        public void serverStream(GrpcRequest request, StreamObserver<GrpcResponse> responseObserver) {
            received.add(request);
            for (int i = 0; i < STREAMED_MESSAGES; i++) {
                send(responseObserver, new GrpcResponse()
                        .setMessage(request.getName() + "-" + i)
                        .setResult(i)
                        .setPayload(request.getPayload()));
            }
            responseObserver.onCompleted();
        }

        private void send(StreamObserver<GrpcResponse> responseObserver, GrpcResponse response) {
            sent.add(response);
            responseObserver.onNext(response);
        }
    }
}
