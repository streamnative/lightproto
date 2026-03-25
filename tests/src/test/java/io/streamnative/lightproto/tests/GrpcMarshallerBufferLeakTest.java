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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the gRPC marshaller returns a leak-proof, Drainable stream.
 * The marshaller serializes into a pooled ByteBuf, copies the bytes out, releases
 * the ByteBuf immediately, and returns a byte[]-backed DrainableByteArrayInputStream.
 * No ref-counted object escapes stream().
 */
public class GrpcMarshallerBufferLeakTest {

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
}
