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

        // Marshaller factory method
        generateMarshallerFactory(w);

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
        w.format("    public static %sStub newStub(io.grpc.Channel channel) {\n", serviceName);
        w.format("        return %sStub.newStub(new %sStub.%sStubFactory(), channel);\n", serviceName, serviceName, serviceName);
        w.format("    }\n\n");

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

    private void generateMarshallerFactory(PrintWriter w) {
        w.println("    private static <T extends LightProtoCodec.LightProtoMessage> io.grpc.MethodDescriptor.Marshaller<T> marshaller(");
        w.println("            java.util.function.Supplier<T> factory) {");
        w.println("        return new io.grpc.MethodDescriptor.Marshaller<T>() {");
        w.println("            @Override");
        w.println("            public java.io.InputStream stream(T value) {");
        w.println("                int size = value.getSerializedSize();");
        w.println("                io.netty.buffer.ByteBuf buf = io.netty.buffer.PooledByteBufAllocator.DEFAULT.directBuffer(size);");
        w.println("                value.writeTo(buf);");
        w.println("                return new io.netty.buffer.ByteBufInputStream(buf, true);");
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
