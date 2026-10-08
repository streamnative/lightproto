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

import io.grpc.Drainable;
import io.grpc.KnownLength;
import io.grpc.MethodDescriptor;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.MessageFramer;
import io.grpc.internal.StatsTraceContext;
import io.grpc.internal.WritableBuffer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.streamnative.lightproto.tests.GrpcRequest;
import io.streamnative.lightproto.tests.TestServiceGrpc;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

/**
 * Outbound gRPC marshalling of a message with a bytes payload: the marshaller's stream is framed
 * by gRPC's MessageFramer and then closed, as AbstractStream.writeMessage() does, into pooled
 * direct transport buffers sized as grpc-netty sizes them. The generated marshaller
 * ({@code spareArray}) is compared with the one it replaces ({@code master}) and with two
 * pooled-buffer alternatives. Run with {@code -prof gc} for the allocation per message.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class GrpcMarshallerBenchmark {

    private static final MethodDescriptor.Marshaller<GrpcRequest> GENERATED =
            TestServiceGrpc.getUnaryMethod().getRequestMarshaller();

    // Transport buffers come from their own allocator, as grpc-netty-shaded's do
    private static final ByteBufAllocator TRANSPORT_ALLOCATOR = new PooledByteBufAllocator(true);

    @Param({"64", "1024", "16384", "131072", "2097152"})
    int payloadSize;

    private GrpcRequest message;
    private MessageFramer framer;

    @Setup
    public void setup() {
        byte[] payload = new byte[payloadSize];
        new Random(42).nextBytes(payload);
        message = new GrpcRequest().setName("benchmark").setValue(payloadSize).setPayload(payload);
        // The transport writes the frames out and releases them
        framer = new MessageFramer((frame, endOfStream, flush, numMessages) -> {
            if (frame != null) {
                frame.release();
            }
        }, TransportBuffer::new, StatsTraceContext.NOOP);
    }

    /** The marshaller on master: a new exact-size array per message. */
    @Benchmark
    public void master() {
        write(masterStream(message));
    }

    /** The generated marshaller: each thread reuses the array of the last stream it closed. */
    @Benchmark
    public void spareArray() {
        write(GENERATED.stream(message));
    }

    /** A pooled heap buffer, drained in place and released when gRPC closes the stream. */
    @Benchmark
    public void pooledHeap() {
        write(pooledHeapStream(message));
    }

    /** The marshaller before 0d3ae50: a pooled direct buffer in a ByteBufInputStream. */
    @Benchmark
    public void revert0d3ae50() {
        write(revertStream(message));
    }

    private void write(InputStream stream) {
        try {
            framer.writePayload(stream);
        } finally {
            GrpcUtil.closeQuietly(stream);
        }
        framer.flush();
    }

    private static InputStream masterStream(GrpcRequest value) {
        int size = value.getSerializedSize();
        ByteBuf buf = Unpooled.buffer(size, size);
        value.writeTo(buf);
        return new MasterInputStream(buf.array(), 0, buf.readableBytes());
    }

    private static InputStream pooledHeapStream(GrpcRequest value) {
        int size = value.getSerializedSize();
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(size, size);
        value.writeTo(buf);
        return new PooledHeapInputStream(buf);
    }

    private static InputStream revertStream(GrpcRequest value) {
        int size = value.getSerializedSize();
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(size);
        value.writeTo(buf);
        return new ByteBufInputStream(buf, true);
    }

    private static final class MasterInputStream extends ByteArrayInputStream
            implements Drainable, KnownLength {
        MasterInputStream(byte[] buf, int offset, int length) {
            super(buf, offset, length);
        }

        @Override
        public int drainTo(OutputStream target) throws IOException {
            int count = this.count - this.pos;
            if (count > 0) {
                target.write(this.buf, this.pos, count);
                this.pos = this.count;
            }
            return count;
        }
    }

    private static final class PooledHeapInputStream extends InputStream implements Drainable, KnownLength {
        private ByteBuf buf;

        PooledHeapInputStream(ByteBuf buf) {
            this.buf = buf;
        }

        @Override
        public int available() {
            return buf == null ? 0 : buf.readableBytes();
        }

        @Override
        public int read() {
            return available() == 0 ? -1 : buf.readUnsignedByte();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            int n = Math.min(available(), len);
            if (n == 0) {
                return len == 0 ? 0 : -1;
            }
            buf.readBytes(b, off, n);
            return n;
        }

        @Override
        public int drainTo(OutputStream target) throws IOException {
            int n = available();
            if (n > 0) {
                buf.readBytes(target, n);
            }
            return n;
        }

        @Override
        public void close() {
            if (buf != null) {
                buf.release();
                buf = null;
            }
        }
    }

    /** grpc-netty's NettyWritableBuffer, with its allocator's 4 KiB to 1 MiB sizing. */
    static final class TransportBuffer implements WritableBuffer {
        private final ByteBuf buf;

        TransportBuffer(int capacityHint) {
            int capacity = Math.min(1024 * 1024, Math.max(4 * 1024, capacityHint));
            buf = TRANSPORT_ALLOCATOR.buffer(capacity, capacity);
        }

        @Override
        public void write(byte[] src, int srcIndex, int length) {
            buf.writeBytes(src, srcIndex, length);
        }

        @Override
        public void write(byte b) {
            buf.writeByte(b);
        }

        @Override
        public int writableBytes() {
            return buf.writableBytes();
        }

        @Override
        public int readableBytes() {
            return buf.readableBytes();
        }

        @Override
        public void release() {
            buf.release();
        }
    }
}
