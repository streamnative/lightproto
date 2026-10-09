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
package io.streamnative.lightproto.generator;

import java.io.PrintWriter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class LightProtoService {
    private final ProtoServiceDescriptor service;

    public LightProtoService(ProtoServiceDescriptor service) {
        this.service = service;
    }

    public String getName() {
        return service.getName();
    }

    public void generate(PrintWriter w) {
        String serviceName = service.getName();
        String grpcClassName = serviceName + "Grpc";
        String fullServiceName = service.getProtoPackage().isEmpty()
                ? serviceName
                : service.getProtoPackage() + "." + serviceName;
        List<ProtoMethodDescriptor> methods = service.getMethods();

        Util.writeJavadoc(w, service.getDoc(), "");
        w.format("public final class %s {\n", grpcClassName);
        w.format("    private %s() {}\n\n", grpcClassName);

        // SERVICE_NAME constant
        w.format("    public static final String SERVICE_NAME = \"%s\";\n\n", fullServiceName);

        // ByteBufInputStream reflection field for zero-copy deserialization
        generateByteBufInputStreamField(w);

        // Drainable InputStream for efficient writes into gRPC framer
        generateDrainableByteArrayInputStream(w);
        generateGatheringInputStream(w);

        // Marshaller factory method
        generateMarshallerFactory(w);

        // Zero-copy access to messages spanning several gRPC transport buffers
        generateWrapTransportBuffers(w);

        // Marshaller fields (one per unique message type)
        Set<String> uniqueTypes = new LinkedHashSet<>();
        for (ProtoMethodDescriptor m : methods) {
            uniqueTypes.add(m.getInputType());
            uniqueTypes.add(m.getOutputType());
        }
        for (String type : uniqueTypes) {
            String constName = Util.upperCase(type) + "_MARSHALLER";
            w.format("    private static final io.grpc.MethodDescriptor.Marshaller<%s> %s = marshaller(%s::new);\n",
                    type, constName, type);
        }
        w.println();

        // Method ID constants
        for (int i = 0; i < methods.size(); i++) {
            w.format("    private static final int METHODID_%s = %d;\n",
                    Util.upperCase(methods.get(i).getName()), i);
        }
        w.println();

        // MethodDescriptor fields and getters
        for (ProtoMethodDescriptor m : methods) {
            generateMethodDescriptor(w, m, grpcClassName);
        }

        // Factory methods
        w.format("    /** Creates a new async stub for the {@code %s} service. */\n", serviceName);
        w.format("    public static %sStub newStub(io.grpc.Channel channel) {\n", serviceName);
        w.format("        return %sStub.newStub(new %sStub.%sStubFactory(), channel);\n", serviceName, serviceName, serviceName);
        w.format("    }\n\n");

        w.format("    /** Creates a new blocking stub for the {@code %s} service. */\n", serviceName);
        w.format("    public static %sBlockingStub newBlockingStub(io.grpc.Channel channel) {\n", serviceName);
        w.format("        return %sBlockingStub.newStub(new %sBlockingStub.%sBlockingStubFactory(), channel);\n",
                serviceName, serviceName, serviceName);
        w.format("    }\n\n");

        // AsyncService interface
        generateAsyncService(w, methods);

        // ImplBase
        generateImplBase(w, serviceName);

        // Stub (async)
        generateStub(w, serviceName, methods);

        // BlockingStub
        generateBlockingStub(w, serviceName, methods);

        // MethodHandlers
        generateMethodHandlers(w, serviceName, methods);

        // ServiceDescriptor
        generateServiceDescriptor(w, grpcClassName, methods);

        // bindService()
        generateBindService(w, methods);

        w.println("}");
    }

    private void generateByteBufInputStreamField(PrintWriter w) {
        w.println("    private static final java.lang.reflect.Field BYTE_BUF_INPUT_STREAM_BUFFER;");
        w.println("    static {");
        w.println("        java.lang.reflect.Field f = null;");
        w.println("        try {");
        w.println("            f = io.netty.buffer.ByteBufInputStream.class.getDeclaredField(\"buffer\");");
        w.println("            f.setAccessible(true);");
        w.println("        } catch (Exception e) {");
        w.println("            // Fall back to byte array copy if reflection fails");
        w.println("        }");
        w.println("        BYTE_BUF_INPUT_STREAM_BUFFER = f;");
        w.println("    }\n");
    }

    private void generateDrainableByteArrayInputStream(PrintWriter w) {
        // Each thread reuses one array for its outbound messages: stream() takes the thread's
        // array, and closing the stream hands it back. Over Netty, gRPC drains and closes the
        // stream inside writeMessage(), on the sending thread, so steady-state sends allocate no
        // array. The array is not reference counted: a stream that is never closed only leaves
        // it to the garbage collector (GatheringInputStream also retains the buffers of the large
        // values it writes). Arrays above SPARE_ARRAY_MAX, gRPC's default maximum
        // inbound message size, are not kept, so outlier messages don't pin larger allocations
        // on every thread that sent one.
        w.println("    private static final int SPARE_ARRAY_MAX = 4 * 1024 * 1024;");
        w.println("    private static final ThreadLocal<byte[]> SPARE_ARRAY = new ThreadLocal<>();\n");

        w.println("    private static final class DrainableByteArrayInputStream");
        w.println("            extends java.io.ByteArrayInputStream");
        w.println("            implements io.grpc.Drainable, io.grpc.KnownLength {");
        w.println("        private static final byte[] CLOSED = new byte[0];");
        w.println();
        w.println("        DrainableByteArrayInputStream(byte[] buf, int length) {");
        w.println("            super(buf, 0, length);");
        w.println("        }");
        w.println();
        w.println("        @Override");
        w.println("        public int drainTo(java.io.OutputStream target) throws java.io.IOException {");
        w.println("            int count = this.count - this.pos;");
        w.println("            if (count > 0) {");
        w.println("                target.write(this.buf, this.pos, count);");
        w.println("                this.pos = this.count;");
        w.println("            }");
        w.println("            return count;");
        w.println("        }");
        w.println();
        // The array is reused only once its stream is closed, so a stream that outlives the
        // next stream() call (in-process transport, or queued while the channel connects)
        // keeps its bytes. A closed stream reads as empty.
        w.println("        @Override");
        w.println("        public void close() {");
        w.println("            byte[] a = this.buf;");
        w.println("            if (a.length == 0) {");
        w.println("                return;");
        w.println("            }");
        w.println("            this.buf = CLOSED;");
        w.println("            this.pos = this.count = this.mark = 0;");
        w.println("            if (a.length <= SPARE_ARRAY_MAX) {");
        w.println("                SPARE_ARRAY.set(a);");
        w.println("            }");
        w.println("        }");
        w.println("    }\n");
    }

    private void generateGatheringInputStream(PrintWriter w) {
        // A message with large heap bytes values: the array holds everything else, and the
        // stream reads its pieces in order, the array up to region 0, region 0, the array up
        // to region 1, and so on. Each region's buffer stays retained until close(), so the
        // caller may release it once stream() returns, but must not change its contents.
        w.println("    private static final class GatheringInputStream extends java.io.InputStream");
        w.println("            implements io.grpc.Drainable, io.grpc.KnownLength {");
        w.println("        private static final byte[] CLOSED = new byte[0];");
        w.println();
        w.println("        private byte[] buf;");
        w.println("        private final int length;");
        w.println("        private final int count;");
        w.println("        private final int[] pos;");
        w.println("        private final io.netty.buffer.ByteBuf[] bufs;");
        w.println("        private final byte[][] arrays;");
        w.println("        private final int[] idx;");
        w.println("        private final int[] len;");
        w.println("        private int remaining;");
        // The piece being read, 2k for the array before region k and 2k + 1 for region k,
        // and the bytes of it already read
        w.println("        private int piece;");
        w.println("        private int offset;");
        w.println();
        w.println("        GatheringInputStream(byte[] buf, int length, int size, LightProtoCodec.Gather g) {");
        w.println("            this.buf = buf;");
        w.println("            this.length = length;");
        w.println("            this.count = g.count;");
        w.println("            this.pos = java.util.Arrays.copyOf(g.pos, count);");
        w.println("            this.bufs = java.util.Arrays.copyOf(g.bufs, count);");
        w.println("            this.arrays = java.util.Arrays.copyOf(g.arrays, count);");
        w.println("            this.idx = java.util.Arrays.copyOf(g.idx, count);");
        w.println("            this.len = java.util.Arrays.copyOf(g.len, count);");
        w.println("            this.remaining = size;");
        w.println("            java.util.Arrays.fill(g.bufs, 0, count, null);");
        w.println("            java.util.Arrays.fill(g.arrays, 0, count, null);");
        w.println("            g.count = 0;");
        w.println("        }");
        w.println();
        w.println("        private byte[] pieceArray() {");
        w.println("            return (piece & 1) == 0 ? buf : arrays[piece >> 1];");
        w.println("        }");
        w.println();
        w.println("        private int pieceStart() {");
        w.println("            int k = piece >> 1;");
        w.println("            if ((piece & 1) != 0) {");
        w.println("                return idx[k];");
        w.println("            }");
        w.println("            return k == 0 ? 0 : pos[k - 1];");
        w.println("        }");
        w.println();
        w.println("        private int pieceLength() {");
        w.println("            int k = piece >> 1;");
        w.println("            if ((piece & 1) != 0) {");
        w.println("                return len[k];");
        w.println("            }");
        w.println("            return (k < count ? pos[k] : length) - (k == 0 ? 0 : pos[k - 1]);");
        w.println("        }");
        w.println();
        w.println("        @Override");
        w.println("        public int available() {");
        w.println("            return remaining;");
        w.println("        }");
        w.println();
        w.println("        @Override");
        w.println("        public int read() {");
        w.println("            while (remaining > 0) {");
        w.println("                if (offset < pieceLength()) {");
        w.println("                    remaining--;");
        w.println("                    return pieceArray()[pieceStart() + offset++] & 0xFF;");
        w.println("                }");
        w.println("                piece++;");
        w.println("                offset = 0;");
        w.println("            }");
        w.println("            return -1;");
        w.println("        }");
        w.println();
        w.println("        @Override");
        w.println("        public int read(byte[] b, int off, int n) {");
        w.println("            java.util.Objects.checkFromIndexSize(off, n, b.length);");
        w.println("            if (n == 0) {");
        w.println("                return 0;");
        w.println("            }");
        w.println("            if (remaining == 0) {");
        w.println("                return -1;");
        w.println("            }");
        w.println("            int total = 0;");
        w.println("            while (total < n && remaining > 0) {");
        w.println("                int c = Math.min(pieceLength() - offset, n - total);");
        w.println("                if (c == 0) {");
        w.println("                    piece++;");
        w.println("                    offset = 0;");
        w.println("                    continue;");
        w.println("                }");
        w.println("                System.arraycopy(pieceArray(), pieceStart() + offset, b, off + total, c);");
        w.println("                offset += c;");
        w.println("                total += c;");
        w.println("                remaining -= c;");
        w.println("            }");
        w.println("            return total;");
        w.println("        }");
        w.println();
        w.println("        @Override");
        w.println("        public int drainTo(java.io.OutputStream target) throws java.io.IOException {");
        w.println("            int total = 0;");
        w.println("            while (remaining > 0) {");
        w.println("                int c = pieceLength() - offset;");
        w.println("                if (c > 0) {");
        w.println("                    target.write(pieceArray(), pieceStart() + offset, c);");
        w.println("                    total += c;");
        w.println("                    remaining -= c;");
        w.println("                }");
        w.println("                piece++;");
        w.println("                offset = 0;");
        w.println("            }");
        w.println("            return total;");
        w.println("        }");
        w.println();
        w.println("        @Override");
        w.println("        public void close() {");
        w.println("            byte[] a = this.buf;");
        w.println("            if (a.length == 0) {");
        w.println("                return;");
        w.println("            }");
        w.println("            this.buf = CLOSED;");
        w.println("            this.remaining = 0;");
        w.println("            for (int k = 0; k < count; k++) {");
        w.println("                bufs[k].release();");
        w.println("                bufs[k] = null;");
        w.println("                arrays[k] = null;");
        w.println("            }");
        w.println("            if (a.length <= SPARE_ARRAY_MAX) {");
        w.println("                SPARE_ARRAY.set(a);");
        w.println("            }");
        w.println("        }");
        w.println("    }\n");
    }

    private void generateMarshallerFactory(PrintWriter w) {
        w.println("    private static <T extends LightProtoCodec.LightProtoMessage> io.grpc.MethodDescriptor.Marshaller<T> marshaller(");
        w.println("            java.util.function.Supplier<T> factory) {");
        w.println("        return new io.grpc.MethodDescriptor.Marshaller<T>() {");
        w.println("            @Override");
        w.println("            public java.io.InputStream stream(T value) {");
        w.println("                int size = value.getSerializedSize();");
        w.println("                byte[] a = SPARE_ARRAY.get();");
        w.println("                if (a != null && a.length >= size) {");
        w.println("                    SPARE_ARRAY.set(null);");
        w.println("                } else {");
        w.println("                    a = new byte[size];");
        w.println("                }");
        // Large heap bytes values are gathered rather than copied: the stream writes them
        // to the transport from the buffers holding them, and close() releases them. A
        // message smaller than GATHER_MIN has no value to gather, and skips the lookup
        w.println("                if (size < LightProtoCodec.GATHER_MIN) {");
        w.println("                    value._writeTo(a, 0);");
        w.println("                    return new DrainableByteArrayInputStream(a, size);");
        w.println("                }");
        w.println("                LightProtoCodec.Gather g = LightProtoCodec.GATHER.get();");
        w.println("                int length;");
        w.println("                g.active = true;");
        w.println("                try {");
        w.println("                    length = value._writeTo(a, 0);");
        w.println("                } catch (Throwable t) {");
        w.println("                    g.releaseAll();");
        w.println("                    throw t;");
        w.println("                } finally {");
        w.println("                    g.active = false;");
        w.println("                }");
        w.println("                if (g.count == 0) {");
        w.println("                    return new DrainableByteArrayInputStream(a, size);");
        w.println("                }");
        w.println("                return new GatheringInputStream(a, length, size, g);");
        w.println("            }");
        w.println("            @Override");
        w.println("            public T parse(java.io.InputStream stream) {");
        w.println("                try (stream) {");
        w.println("                    if (BYTE_BUF_INPUT_STREAM_BUFFER != null");
        w.println("                            && stream instanceof io.netty.buffer.ByteBufInputStream) {");
        w.println("                        io.netty.buffer.ByteBuf buf =");
        w.println("                                (io.netty.buffer.ByteBuf) BYTE_BUF_INPUT_STREAM_BUFFER.get(stream);");
        w.println("                        T msg = factory.get();");
        w.println("                        msg.parseFrom(buf, buf.readableBytes());");
        w.println("                        msg.materialize();");
        w.println("                        return msg;");
        w.println("                    }");
        w.println("                    T msg = factory.get();");
        w.println("                    if (stream instanceof io.grpc.KnownLength) {");
        w.println("                        int size = stream.available();");
        w.println("                        if (stream instanceof io.grpc.HasByteBuffer");
        w.println("                                && ((io.grpc.HasByteBuffer) stream).byteBufferSupported()) {");
        w.println("                            java.nio.ByteBuffer nioBuf = ((io.grpc.HasByteBuffer) stream).getByteBuffer();");
        w.println("                            if (nioBuf != null && nioBuf.remaining() == size) {");
        w.println("                                // The whole message is in one transport buffer: parse it in place,");
        w.println("                                // and materialize before close() hands the buffer back");
        w.println("                                msg.parseFrom(io.netty.buffer.Unpooled.wrappedBuffer(nioBuf), size);");
        w.println("                                msg.materialize();");
        w.println("                                return msg;");
        w.println("                            }");
        w.println("                            // A message larger than an HTTP/2 DATA frame spans several transport");
        w.println("                            // buffers: parse it in place from all of them. A smaller one that");
        w.println("                            // straddles two frames is cheaper to copy");
        w.println("                            io.netty.buffer.ByteBuf buf = size >= LightProtoCodec.SEGMENTED_PARSE_MIN");
        w.println("                                    && nioBuf != null && stream.markSupported() ? wrapTransportBuffers(stream, size) : null;");
        w.println("                            if (buf != null) {");
        w.println("                                msg.parseFrom(buf, size);");
        w.println("                                msg.materialize();");
        w.println("                                return msg;");
        w.println("                            }");
        w.println("                        }");
        w.println("                        // Copy once into an exact-size array: readAllBytes() would read into");
        w.println("                        // 8-16 KiB chunks and then copy again");
        w.println("                        byte[] bytes = new byte[size];");
        w.println("                        int read = stream.readNBytes(bytes, 0, size);");
        w.println("                        if (read != size) {");
        w.println("                            throw new java.io.EOFException(\"Expected \" + size + \" bytes, read \" + read);");
        w.println("                        }");
        w.println("                        msg.parseFrom(bytes);");
        w.println("                        return msg;");
        w.println("                    }");
        w.println("                    msg.parseFrom(stream.readAllBytes());");
        w.println("                    return msg;");
        w.println("                } catch (java.io.IOException e) {");
        w.println("                    throw new RuntimeException(e);");
        w.println("                } catch (IllegalAccessException e) {");
        w.println("                    throw new RuntimeException(e);");
        w.println("                }");
        w.println("            }");
        w.println("        };");
        w.println("    }\n");
    }

    private void generateWrapTransportBuffers(PrintWriter w) {
        w.println("    /**");
        w.println("     * Wraps the next {@code size} bytes of a gRPC stream, without copying, in a buffer over the");
        w.println("     * transport buffers holding them. skip() would hand each buffer it moves past back to the");
        w.println("     * transport, unless the stream is marked: then they stay valid until close(). Returns null,");
        w.println("     * with the stream reset, if the stream doesn't expose a buffer for every byte.");
        w.println("     */");
        w.println("    private static io.netty.buffer.ByteBuf wrapTransportBuffers(java.io.InputStream stream, int size)");
        w.println("            throws java.io.IOException {");
        w.println("        java.nio.ByteBuffer[] buffers = new java.nio.ByteBuffer[8];");
        w.println("        int count = 0;");
        w.println("        stream.mark(size);");
        w.println("        for (int remaining = size; remaining > 0; ) {");
        w.println("            java.nio.ByteBuffer nioBuf = ((io.grpc.HasByteBuffer) stream).getByteBuffer();");
        w.println("            int length = nioBuf == null ? 0 : Math.min(nioBuf.remaining(), remaining);");
        w.println("            if (length == 0 || stream.skip(length) != length) {");
        w.println("                stream.reset();");
        w.println("                return null;");
        w.println("            }");
        w.println("            nioBuf.limit(nioBuf.position() + length);");
        w.println("            if (count == buffers.length) {");
        w.println("                buffers = java.util.Arrays.copyOf(buffers, count * 2);");
        w.println("            }");
        w.println("            buffers[count++] = nioBuf;");
        w.println("            remaining -= length;");
        w.println("        }");
        w.println("        return new LightProtoCodec.SegmentedByteBuf(buffers, count);");
        w.println("    }\n");
    }

    private void generateMethodDescriptor(PrintWriter w, ProtoMethodDescriptor m, String grpcClassName) {
        String methodName = m.getName();
        String ccMethod = javaMethodName(methodName);
        String getterName = "get" + methodName + "Method";
        String fieldName = getterName;
        String inputType = m.getInputType();
        String outputType = m.getOutputType();
        String methodType = getMethodType(m);
        String inputMarshallerConst = Util.upperCase(inputType) + "_MARSHALLER";
        String outputMarshallerConst = Util.upperCase(outputType) + "_MARSHALLER";

        w.format("    private static volatile io.grpc.MethodDescriptor<%s, %s> %s;\n\n",
                inputType, outputType, fieldName);

        if (m.getDoc() != null && !m.getDoc().isEmpty()) {
            Util.writeJavadoc(w, m.getDoc(), "    ");
        } else {
            w.format("    /** Returns the method descriptor for the {@code %s} RPC. */\n", methodName);
        }
        w.format("    public static io.grpc.MethodDescriptor<%s, %s> %s() {\n",
                inputType, outputType, getterName);
        w.format("        io.grpc.MethodDescriptor<%s, %s> result;\n", inputType, outputType);
        w.format("        if ((result = %s) == null) {\n", fieldName);
        w.format("            synchronized (%s.class) {\n", grpcClassName);
        w.format("                if ((result = %s) == null) {\n", fieldName);
        w.format("                    %s = result = io.grpc.MethodDescriptor.<%s, %s>newBuilder()\n",
                fieldName, inputType, outputType);
        w.format("                        .setType(io.grpc.MethodDescriptor.MethodType.%s)\n", methodType);
        w.format("                        .setFullMethodName(io.grpc.MethodDescriptor.generateFullMethodName(SERVICE_NAME, \"%s\"))\n",
                methodName);
        w.format("                        .setRequestMarshaller(%s)\n", inputMarshallerConst);
        w.format("                        .setResponseMarshaller(%s)\n", outputMarshallerConst);
        w.format("                        .build();\n");
        w.format("                }\n");
        w.format("            }\n");
        w.format("        }\n");
        w.format("        return result;\n");
        w.format("    }\n\n");
    }

    private void generateAsyncService(PrintWriter w, List<ProtoMethodDescriptor> methods) {
        w.format("    /** Async service interface for the {@code %s} service. */\n",
                service.getName());
        w.println("    public interface AsyncService {");
        for (ProtoMethodDescriptor m : methods) {
            String ccMethod = javaMethodName(m.getName());
            String inputType = m.getInputType();
            String outputType = m.getOutputType();
            String getterName = "get" + m.getName() + "Method";

            Util.writeJavadoc(w, m.getDoc(), "        ");
            if (isClientStreaming(m)) {
                w.format("        default io.grpc.stub.StreamObserver<%s> %s(\n", inputType, ccMethod);
                w.format("                io.grpc.stub.StreamObserver<%s> responseObserver) {\n", outputType);
                w.format("            return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(%s(), responseObserver);\n",
                        getterName);
            } else {
                w.format("        default void %s(%s request,\n", ccMethod, inputType);
                w.format("                io.grpc.stub.StreamObserver<%s> responseObserver) {\n", outputType);
                w.format("            io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(%s(), responseObserver);\n",
                        getterName);
            }
            w.println("        }");
        }
        w.println("    }\n");
    }

    private void generateImplBase(PrintWriter w, String serviceName) {
        w.format("    /** Base implementation of the {@code %s} service. */\n", serviceName);
        w.format("    public static abstract class %sImplBase implements io.grpc.BindableService, AsyncService {\n",
                serviceName);
        w.format("        @Override\n");
        w.format("        public final io.grpc.ServerServiceDefinition bindService() {\n");
        w.format("            return %sGrpc.bindService(this);\n", serviceName);
        w.format("        }\n");
        w.format("    }\n\n");
    }

    private void generateStub(PrintWriter w, String serviceName, List<ProtoMethodDescriptor> methods) {
        String stubName = serviceName + "Stub";

        w.format("    /** Async stub for the {@code %s} service. */\n", serviceName);
        w.format("    public static final class %s extends io.grpc.stub.AbstractStub<%s> {\n", stubName, stubName);
        w.format("        private %s(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {\n", stubName);
        w.format("            super(channel, callOptions);\n");
        w.format("        }\n\n");

        w.format("        @Override\n");
        w.format("        protected %s build(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {\n", stubName);
        w.format("            return new %s(channel, callOptions);\n", stubName);
        w.format("        }\n\n");

        // StubFactory
        w.format("        static final class %sStubFactory implements io.grpc.stub.AbstractStub.StubFactory<%s> {\n",
                serviceName, stubName);
        w.format("            @Override\n");
        w.format("            public %s newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {\n", stubName);
        w.format("                return new %s(channel, callOptions);\n", stubName);
        w.format("            }\n");
        w.format("        }\n\n");

        for (ProtoMethodDescriptor m : methods) {
            String ccMethod = javaMethodName(m.getName());
            String inputType = m.getInputType();
            String outputType = m.getOutputType();
            String getterName = "get" + m.getName() + "Method";

            Util.writeJavadoc(w, m.getDoc(), "        ");
            if (isClientStreaming(m)) {
                // client-streaming or bidi: returns StreamObserver<Req>
                w.format("        public io.grpc.stub.StreamObserver<%s> %s(\n", inputType, ccMethod);
                w.format("                io.grpc.stub.StreamObserver<%s> responseObserver) {\n", outputType);
                if (m.isServerStreaming()) {
                    w.format("            return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(\n");
                } else {
                    w.format("            return io.grpc.stub.ClientCalls.asyncClientStreamingCall(\n");
                }
                w.format("                getChannel().newCall(%s(), getCallOptions()), responseObserver);\n", getterName);
            } else if (m.isServerStreaming()) {
                // server-streaming
                w.format("        public void %s(%s request,\n", ccMethod, inputType);
                w.format("                io.grpc.stub.StreamObserver<%s> responseObserver) {\n", outputType);
                w.format("            io.grpc.stub.ClientCalls.asyncServerStreamingCall(\n");
                w.format("                getChannel().newCall(%s(), getCallOptions()), request, responseObserver);\n", getterName);
            } else {
                // unary
                w.format("        public void %s(%s request,\n", ccMethod, inputType);
                w.format("                io.grpc.stub.StreamObserver<%s> responseObserver) {\n", outputType);
                w.format("            io.grpc.stub.ClientCalls.asyncUnaryCall(\n");
                w.format("                getChannel().newCall(%s(), getCallOptions()), request, responseObserver);\n", getterName);
            }
            w.println("        }");
        }

        w.format("    }\n\n");
    }

    private void generateBlockingStub(PrintWriter w, String serviceName, List<ProtoMethodDescriptor> methods) {
        String stubName = serviceName + "BlockingStub";

        w.format("    /** Blocking stub for the {@code %s} service. */\n", serviceName);
        w.format("    public static final class %s extends io.grpc.stub.AbstractStub<%s> {\n", stubName, stubName);
        w.format("        private %s(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {\n", stubName);
        w.format("            super(channel, callOptions);\n");
        w.format("        }\n\n");

        w.format("        @Override\n");
        w.format("        protected %s build(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {\n", stubName);
        w.format("            return new %s(channel, callOptions);\n", stubName);
        w.format("        }\n\n");

        // StubFactory
        w.format("        static final class %sBlockingStubFactory implements io.grpc.stub.AbstractStub.StubFactory<%s> {\n",
                serviceName, stubName);
        w.format("            @Override\n");
        w.format("            public %s newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {\n", stubName);
        w.format("                return new %s(channel, callOptions);\n", stubName);
        w.format("            }\n");
        w.format("        }\n\n");

        // Only UNARY and SERVER_STREAMING methods
        for (ProtoMethodDescriptor m : methods) {
            if (isClientStreaming(m)) {
                continue; // BlockingStub doesn't support client-streaming or bidi
            }

            String ccMethod = javaMethodName(m.getName());
            String inputType = m.getInputType();
            String outputType = m.getOutputType();
            String getterName = "get" + m.getName() + "Method";

            Util.writeJavadoc(w, m.getDoc(), "        ");
            if (m.isServerStreaming()) {
                w.format("        public java.util.Iterator<%s> %s(%s request) {\n",
                        outputType, ccMethod, inputType);
                w.format("            return io.grpc.stub.ClientCalls.blockingServerStreamingCall(\n");
                w.format("                getChannel(), %s(), getCallOptions(), request);\n", getterName);
            } else {
                w.format("        public %s %s(%s request) {\n", outputType, ccMethod, inputType);
                w.format("            return io.grpc.stub.ClientCalls.blockingUnaryCall(\n");
                w.format("                getChannel(), %s(), getCallOptions(), request);\n", getterName);
            }
            w.println("        }");
        }

        w.format("    }\n\n");
    }

    private void generateMethodHandlers(PrintWriter w, String serviceName, List<ProtoMethodDescriptor> methods) {
        w.println("    private static final class MethodHandlers<Req, Resp> implements");
        w.println("            io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,");
        w.println("            io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,");
        w.println("            io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,");
        w.println("            io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {");
        w.println("        private final AsyncService serviceImpl;");
        w.println("        private final int methodId;");
        w.println();
        w.println("        MethodHandlers(AsyncService serviceImpl, int methodId) {");
        w.println("            this.serviceImpl = serviceImpl;");
        w.println("            this.methodId = methodId;");
        w.println("        }");
        w.println();

        // invoke(Req, StreamObserver<Resp>) for UNARY and SERVER_STREAMING
        w.println("        @Override");
        w.println("        @SuppressWarnings(\"unchecked\")");
        w.println("        public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {");
        w.println("            switch (methodId) {");
        for (ProtoMethodDescriptor m : methods) {
            if (!isClientStreaming(m)) {
                String ccMethod = javaMethodName(m.getName());
                w.format("                case METHODID_%s:\n", Util.upperCase(m.getName()));
                w.format("                    serviceImpl.%s((%s) request, (io.grpc.stub.StreamObserver<%s>) responseObserver);\n",
                        ccMethod, m.getInputType(), m.getOutputType());
                w.format("                    break;\n");
            }
        }
        w.println("                default:");
        w.println("                    throw new AssertionError();");
        w.println("            }");
        w.println("        }");
        w.println();

        // invoke(StreamObserver<Resp>) for CLIENT_STREAMING and BIDI_STREAMING
        w.println("        @Override");
        w.println("        @SuppressWarnings(\"unchecked\")");
        w.println("        public io.grpc.stub.StreamObserver<Req> invoke(io.grpc.stub.StreamObserver<Resp> responseObserver) {");
        w.println("            switch (methodId) {");
        for (ProtoMethodDescriptor m : methods) {
            if (isClientStreaming(m)) {
                String ccMethod = javaMethodName(m.getName());
                w.format("                case METHODID_%s:\n", Util.upperCase(m.getName()));
                w.format("                    return (io.grpc.stub.StreamObserver<Req>) serviceImpl.%s(\n", ccMethod);
                w.format("                        (io.grpc.stub.StreamObserver<%s>) responseObserver);\n", m.getOutputType());
            }
        }
        w.println("                default:");
        w.println("                    throw new AssertionError();");
        w.println("            }");
        w.println("        }");
        w.println("    }\n");
    }

    private void generateServiceDescriptor(PrintWriter w, String grpcClassName, List<ProtoMethodDescriptor> methods) {
        w.format("    private static volatile io.grpc.ServiceDescriptor serviceDescriptor;\n\n");

        w.format("    /** Returns the service descriptor for the {@code %s} service. */\n",
                grpcClassName.replace("Grpc", ""));
        w.format("    public static io.grpc.ServiceDescriptor getServiceDescriptor() {\n");
        w.format("        io.grpc.ServiceDescriptor result = serviceDescriptor;\n");
        w.format("        if (result == null) {\n");
        w.format("            synchronized (%s.class) {\n", grpcClassName);
        w.format("                result = serviceDescriptor;\n");
        w.format("                if (result == null) {\n");
        w.format("                    serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)\n");
        for (ProtoMethodDescriptor m : methods) {
            String getterName = "get" + m.getName() + "Method";
            w.format("                        .addMethod(%s())\n", getterName);
        }
        w.format("                        .build();\n");
        w.format("                }\n");
        w.format("            }\n");
        w.format("        }\n");
        w.format("        return result;\n");
        w.format("    }\n\n");
    }

    private void generateBindService(PrintWriter w, List<ProtoMethodDescriptor> methods) {
        w.println("    /** Binds the given {@code AsyncService} implementation and returns a {@code ServerServiceDefinition}. */");
        w.println("    public static io.grpc.ServerServiceDefinition bindService(AsyncService service) {");
        w.println("        return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())");
        for (ProtoMethodDescriptor m : methods) {
            String getterName = "get" + m.getName() + "Method";
            String methodType = getMethodType(m);
            String serverCallMethod;
            switch (methodType) {
                case "UNARY":
                    serverCallMethod = "asyncUnaryCall";
                    break;
                case "SERVER_STREAMING":
                    serverCallMethod = "asyncServerStreamingCall";
                    break;
                case "CLIENT_STREAMING":
                    serverCallMethod = "asyncClientStreamingCall";
                    break;
                default:
                    serverCallMethod = "asyncBidiStreamingCall";
                    break;
            }
            w.format("            .addMethod(%s(),\n", getterName);
            w.format("                io.grpc.stub.ServerCalls.%s(\n", serverCallMethod);
            w.format("                    new MethodHandlers<>(service, METHODID_%s)))\n", Util.upperCase(m.getName()));
        }
        w.println("            .build();");
        w.println("    }");
    }

    private static String getMethodType(ProtoMethodDescriptor m) {
        if (m.isClientStreaming() && m.isServerStreaming()) {
            return "BIDI_STREAMING";
        } else if (m.isClientStreaming()) {
            return "CLIENT_STREAMING";
        } else if (m.isServerStreaming()) {
            return "SERVER_STREAMING";
        } else {
            return "UNARY";
        }
    }

    private static boolean isClientStreaming(ProtoMethodDescriptor m) {
        return m.isClientStreaming();
    }

    /**
     * Converts a proto method name (PascalCase) to a Java method name (lowerCamelCase).
     */
    private static String javaMethodName(String protoMethodName) {
        if (protoMethodName == null || protoMethodName.isEmpty()) {
            return protoMethodName;
        }
        return Character.toLowerCase(protoMethodName.charAt(0)) + protoMethodName.substring(1);
    }
}
