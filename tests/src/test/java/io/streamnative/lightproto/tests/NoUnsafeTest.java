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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * Generated code uses sun.misc.Unsafe only where Netty does. The first call to one of its
 * memory-access methods prints a warning naming the caller (JEP 498, the default since
 * JDK 24), which Netty's own calls have then printed already.
 */
public class NoUnsafeTest {

    @Test
    public void testNoUnsafeWarningWithoutNettyUnsafe() throws Exception {
        assumeTrue(Runtime.version().feature() >= 23, "--sun-misc-unsafe-memory-access exists since JDK 23");
        ProcessBuilder pb = new ProcessBuilder(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                // The default from Netty 4.2 on Java 25
                "-Dio.netty.noUnsafe=true",
                "--sun-misc-unsafe-memory-access=warn",
                "-cp", System.getProperty("java.class.path"),
                NoUnsafeTest.class.getName());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), output);
        assertFalse(output.contains("sun.misc.Unsafe"), output);
    }

    /** Writes and reads strings through every codec path, in the JVM that the test starts. */
    public static void main(String[] args) {
        String ascii = "persistent://tenant/namespace/topic-" + "t".repeat(2000);
        String utf8 = "UTF16 Ελληνικά Русский 日本語";
        S small = new S().setId("xyz");
        small.addName(utf8);
        S large = new S().setId(ascii);
        large.addName(utf8);
        for (S s : new S[]{small, large}) {
            // Heap buffers are written in place and parsed from their array; direct ones are
            // written through a scratch array or, above NIO_WRITE_MIN, their NIO view
            for (ByteBuf b : new ByteBuf[]{Unpooled.buffer(), PooledByteBufAllocator.DEFAULT.directBuffer()}) {
                s.writeTo(b);
                S parsed = new S();
                parsed.parseFrom(b, b.readableBytes());
                check(s, parsed);
                b.release();
            }
            // A gRPC message received in one direct buffer is parsed through a view
            byte[] serialized = s.toByteArray();
            ByteBuffer nio = ByteBuffer.allocateDirect(serialized.length).put(serialized).flip();
            S parsed = new S();
            parsed.parseFrom(LightProtoCodec.PARSE_VIEW.get().reset(nio), serialized.length);
            check(s, parsed);
        }
    }

    private static void check(S expected, S actual) {
        if (!expected.getId().equals(actual.getId()) || !expected.getNameAt(0).equals(actual.getNameAt(0))) {
            throw new AssertionError("strings differ after a round trip");
        }
    }
}
