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

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class GrpcTest {

    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        String serverName = InProcessServerBuilder.generateName();

        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new TestServiceImpl())
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void testUnary() {
        TestServiceGrpc.TestServiceBlockingStub stub = TestServiceGrpc.newBlockingStub(channel);

        GrpcRequest request = new GrpcRequest();
        request.setName("hello");
        request.setValue(42);

        GrpcResponse response = stub.unary(request);
        assertEquals("echo: hello", response.getMessage());
        assertEquals(84, response.getResult());
    }

    @Test
    void testServerStreaming() {
        TestServiceGrpc.TestServiceBlockingStub stub = TestServiceGrpc.newBlockingStub(channel);

        GrpcRequest request = new GrpcRequest();
        request.setName("stream");
        request.setValue(3);

        Iterator<GrpcResponse> responses = stub.serverStream(request);
        List<GrpcResponse> results = new ArrayList<>();
        responses.forEachRemaining(results::add);

        assertEquals(3, results.size());
        for (int i = 0; i < 3; i++) {
            assertEquals("stream-" + i, results.get(i).getMessage());
            assertEquals(i, results.get(i).getResult());
        }
    }

    @Test
    void testClientStreaming() throws Exception {
        TestServiceGrpc.TestServiceStub stub = TestServiceGrpc.newStub(channel);
        CountDownLatch latch = new CountDownLatch(1);
        List<GrpcResponse> responses = new ArrayList<>();

        StreamObserver<GrpcRequest> requestObserver = stub.clientStream(new StreamObserver<GrpcResponse>() {
            @Override
            public void onNext(GrpcResponse value) {
                responses.add(value);
            }

            @Override
            public void onError(Throwable t) {
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        });

        for (int i = 0; i < 3; i++) {
            GrpcRequest req = new GrpcRequest();
            req.setName("client-" + i);
            req.setValue(i + 1);
            requestObserver.onNext(req);
        }
        requestObserver.onCompleted();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, responses.size());
        assertEquals("received 3 messages", responses.get(0).getMessage());
        assertEquals(6, responses.get(0).getResult()); // 1+2+3
    }

    @Test
    void testBidiStreaming() throws Exception {
        TestServiceGrpc.TestServiceStub stub = TestServiceGrpc.newStub(channel);
        CountDownLatch latch = new CountDownLatch(1);
        List<GrpcResponse> responses = new ArrayList<>();

        StreamObserver<GrpcRequest> requestObserver = stub.bidiStream(new StreamObserver<GrpcResponse>() {
            @Override
            public void onNext(GrpcResponse value) {
                responses.add(value);
            }

            @Override
            public void onError(Throwable t) {
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        });

        for (int i = 0; i < 3; i++) {
            GrpcRequest req = new GrpcRequest();
            req.setName("bidi-" + i);
            req.setValue(i);
            requestObserver.onNext(req);
        }
        requestObserver.onCompleted();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(3, responses.size());
        for (int i = 0; i < 3; i++) {
            assertEquals("echo: bidi-" + i, responses.get(i).getMessage());
            assertEquals(i * 2, responses.get(i).getResult());
        }
    }

    @Test
    void testMethodDescriptors() {
        assertNotNull(TestServiceGrpc.getUnaryMethod());
        assertEquals(io.grpc.MethodDescriptor.MethodType.UNARY, TestServiceGrpc.getUnaryMethod().getType());

        assertNotNull(TestServiceGrpc.getServerStreamMethod());
        assertEquals(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING, TestServiceGrpc.getServerStreamMethod().getType());

        assertNotNull(TestServiceGrpc.getClientStreamMethod());
        assertEquals(io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING, TestServiceGrpc.getClientStreamMethod().getType());

        assertNotNull(TestServiceGrpc.getBidiStreamMethod());
        assertEquals(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING, TestServiceGrpc.getBidiStreamMethod().getType());
    }

    @Test
    void testServiceDescriptor() {
        io.grpc.ServiceDescriptor sd = TestServiceGrpc.getServiceDescriptor();
        assertNotNull(sd);
        assertEquals("io.streamnative.lightproto.tests.TestService", sd.getName());
    }

    // --- Service implementation ---

    private static class TestServiceImpl extends TestServiceGrpc.TestServiceImplBase {
        @Override
        public void unary(GrpcRequest request, StreamObserver<GrpcResponse> responseObserver) {
            GrpcResponse response = new GrpcResponse();
            response.setMessage("echo: " + request.getName());
            response.setResult(request.getValue() * 2);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void serverStream(GrpcRequest request, StreamObserver<GrpcResponse> responseObserver) {
            for (int i = 0; i < request.getValue(); i++) {
                GrpcResponse response = new GrpcResponse();
                response.setMessage(request.getName() + "-" + i);
                response.setResult(i);
                responseObserver.onNext(response);
            }
            responseObserver.onCompleted();
        }

        @Override
        public StreamObserver<GrpcRequest> clientStream(StreamObserver<GrpcResponse> responseObserver) {
            return new StreamObserver<GrpcRequest>() {
                int count = 0;
                int sum = 0;

                @Override
                public void onNext(GrpcRequest value) {
                    count++;
                    sum += value.getValue();
                }

                @Override
                public void onError(Throwable t) {
                    responseObserver.onError(t);
                }

                @Override
                public void onCompleted() {
                    GrpcResponse response = new GrpcResponse();
                    response.setMessage("received " + count + " messages");
                    response.setResult(sum);
                    responseObserver.onNext(response);
                    responseObserver.onCompleted();
                }
            };
        }

        @Override
        public StreamObserver<GrpcRequest> bidiStream(StreamObserver<GrpcResponse> responseObserver) {
            return new StreamObserver<GrpcRequest>() {
                @Override
                public void onNext(GrpcRequest value) {
                    GrpcResponse response = new GrpcResponse();
                    response.setMessage("echo: " + value.getName());
                    response.setResult(value.getValue() * 2);
                    responseObserver.onNext(response);
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
