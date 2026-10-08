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
package io.streamnative.lightproto.benchmark;

import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.MethodDescriptor;
import io.grpc.internal.CompositeReadableBuffer;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.MessageFramer;
import io.grpc.internal.ReadableBuffers;
import io.grpc.internal.StatsTraceContext;
import io.grpc.protobuf.ProtoUtils;
import io.streamnative.lightproto.tests.EchoServiceGrpc;
import io.streamnative.lightproto.tests.GrpcPayload;
import io.streamnative.lightproto.tests.GrpcPayloadItem;
import io.streamnative.lightproto.tests.GrpcTestProtos;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * gRPC message serde with the protobuf marshaller ({@code ProtoUtils.marshaller()}, what
 * protoc-gen-grpc-java generates) and with the LightProto generated marshaller, on the same
 * message. Serialize drains the marshaller's stream through gRPC's MessageFramer into pooled
 * direct transport buffers, as AbstractStream.writeMessage() does. Deserialize parses from a
 * {@code ReadableBuffers.BufferInputStream} over direct buffers of at most 16 KiB, the default
 * HTTP/2 DATA frame size, and reads every field. Run with {@code -prof gc} for the allocation per
 * message.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class GrpcSerdeBenchmark {

    private static final MethodDescriptor.Marshaller<GrpcPayload> LIGHTPROTO =
            EchoServiceGrpc.getEchoMethod().getRequestMarshaller();

    private static final MethodDescriptor.Marshaller<GrpcTestProtos.GrpcPayload> PROTOBUF =
            ProtoUtils.marshaller(GrpcTestProtos.GrpcPayload.getDefaultInstance());

    private static final int FRAME_SIZE = 16 * 1024;

    /** Size of the bytes field, or of all the records. */
    @Param({"100", "4096", "65536"})
    public int size;

    /** One bytes field, or records of 64 bytes with small fields, as in a range scan response. */
    @Param({"value", "records"})
    public String shape;

    private GrpcPayload lightProtoMessage;
    private GrpcTestProtos.GrpcPayload protobufMessage;
    private ByteBuffer[] frames;
    private MessageFramer framer;

    @Setup
    public void setup() throws InvalidProtocolBufferException {
        Random random = new Random(42);
        lightProtoMessage = new GrpcPayload().setName("/benchmark/key");
        if (shape.equals("value")) {
            byte[] data = new byte[size];
            random.nextBytes(data);
            lightProtoMessage.setData(data);
        } else {
            for (int i = 0; i < Math.max(1, size / 64); i++) {
                byte[] value = new byte[32];
                random.nextBytes(value);
                lightProtoMessage.addItem().setKey(String.format("/key/%010d", i)).setValue(value)
                        .setVersion(random.nextLong());
            }
        }
        byte[] serialized = lightProtoMessage.toByteArray();
        protobufMessage = GrpcTestProtos.GrpcPayload.parseFrom(serialized);

        int count = (serialized.length + FRAME_SIZE - 1) / FRAME_SIZE;
        frames = new ByteBuffer[count];
        for (int i = 0; i < count; i++) {
            int offset = i * FRAME_SIZE;
            int length = Math.min(FRAME_SIZE, serialized.length - offset);
            frames[i] = ByteBuffer.allocateDirect(length);
            frames[i].put(serialized, offset, length).flip();
        }

        // The transport writes the frames out and releases them
        framer = new MessageFramer((frame, endOfStream, flush, numMessages) -> {
            if (frame != null) {
                frame.release();
            }
        }, GrpcMarshallerBenchmark.TransportBuffer::new, StatsTraceContext.NOOP);
    }

    @Benchmark
    public void protobufSerialize() {
        write(PROTOBUF.stream(protobufMessage));
    }

    @Benchmark
    public void lightProtoSerialize() {
        write(LIGHTPROTO.stream(lightProtoMessage));
    }

    @Benchmark
    public void protobufDeserialize(Blackhole bh) {
        GrpcTestProtos.GrpcPayload msg = PROTOBUF.parse(openStream());
        bh.consume(msg.getName());
        bh.consume(msg.getData());
        for (int i = 0; i < msg.getItemsCount(); i++) {
            GrpcTestProtos.GrpcPayloadItem item = msg.getItems(i);
            bh.consume(item.getKey());
            bh.consume(item.getValue());
            bh.consume(item.getVersion());
        }
    }

    @Benchmark
    public void lightProtoDeserialize(Blackhole bh) {
        GrpcPayload msg = LIGHTPROTO.parse(openStream());
        bh.consume(msg.getName());
        bh.consume(msg.getData());
        for (int i = 0; i < msg.getItemsCount(); i++) {
            GrpcPayloadItem item = msg.getItemAt(i);
            bh.consume(item.getKey());
            bh.consume(item.getValue());
            bh.consume(item.getVersion());
        }
    }

    private void write(InputStream stream) {
        try {
            framer.writePayload(stream);
        } finally {
            GrpcUtil.closeQuietly(stream);
        }
        framer.flush();
    }

    private InputStream openStream() {
        CompositeReadableBuffer composite = new CompositeReadableBuffer();
        for (ByteBuffer frame : frames) {
            composite.addBuffer(ReadableBuffers.wrap(frame.duplicate()));
        }
        return ReadableBuffers.openStream(composite, true);
    }
}
