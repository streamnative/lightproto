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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.streamnative.lightproto.tests.decoder.Interned;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Messages generated with the {@code stringDecoder} option decode their lazy strings with
 * {@link TestStringInterner}.
 */
public class StringDecoderTest {

    private static final String ASCII = "service-name";
    private static final String NON_ASCII = "naïve café 日本 ✓";

    private static ByteBuf serialize(String name, String tag, String labelKey, String labelValue, String code) {
        Interned m = new Interned().setName(name);
        m.addTag(tag);
        m.addTag(name);
        m.putLabels(labelKey, labelValue);
        m.putCodes(7, code);
        return Unpooled.wrappedBuffer(m.toByteArray());
    }

    private static ByteBuf sample() {
        return serialize(ASCII, NON_ASCII, "region", NON_ASCII, ASCII);
    }

    private static Interned parse(ByteBuf buf) {
        Interned m = new Interned();
        m.parseFrom(buf.duplicate(), buf.readableBytes());
        return m;
    }

    private static List<String> labelKeys(Interned m) {
        List<String> keys = new ArrayList<>();
        m.forEachLabels((k, v) -> keys.add(k));
        return keys;
    }

    private static void assertSameStrings(Interned expected, Interned actual) {
        assertSame(expected.getName(), actual.getName());
        assertSame(expected.getTagAt(0), actual.getTagAt(0));
        assertSame(expected.getTagAt(1), actual.getTagAt(1));
        assertSame(labelKeys(expected).get(0), labelKeys(actual).get(0));
        assertSame(expected.getLabels("region"), actual.getLabels("region"));
        assertSame(expected.getCodes(7), actual.getCodes(7));
    }

    @Test
    public void testValuesEqualThePlainDecode() {
        long calls = TestStringInterner.calls();
        Interned m = parse(sample());
        assertEquals(ASCII, m.getName());
        assertEquals(NON_ASCII, m.getTagAt(0));
        assertEquals(ASCII, m.getTagAt(1));
        assertEquals(List.of("region"), labelKeys(m));
        assertEquals(NON_ASCII, m.getLabels("region"));
        assertEquals(ASCII, m.getCodes(7));
        // name, 2 tags, 1 label key, 1 label value, 1 code value
        assertEquals(6, TestStringInterner.calls() - calls);

        // Strings longer than the interner's limit are decoded without caching
        String longValue = "x".repeat(TestStringInterner.MAX_LENGTH + 1) + NON_ASCII;
        ByteBuf longBuf = serialize(longValue, longValue, longValue, longValue, longValue);
        Interned first = parse(longBuf);
        Interned second = parse(longBuf);
        assertEquals(longValue, first.getName());
        assertEquals(longValue, first.getLabels(longValue));
        assertNotSame(first.getName(), second.getName());
    }

    @Test
    public void testSameInstanceAcrossParses() {
        ByteBuf buf = sample();
        assertSameStrings(parse(buf), parse(buf));
        // Equal bytes at a different offset in a different buffer
        ByteBuf shifted = Unpooled.buffer();
        shifted.writeZero(13).writeBytes(buf.duplicate()).readerIndex(13);
        assertSameStrings(parse(buf), parse(shifted));
    }

    @Test
    public void testMaterialize() {
        ByteBuf buf = sample();
        Interned reference = parse(buf);
        ByteBuf copy = Unpooled.copiedBuffer(buf);
        Interned m = parse(copy);
        long calls = TestStringInterner.calls();
        m.materialize();
        assertEquals(6, TestStringInterner.calls() - calls);
        // The strings no longer depend on the parsed buffer
        copy.setZero(0, copy.capacity());
        assertSameStrings(reference, m);
    }

    @Test
    public void testCopyFrom() {
        ByteBuf buf = sample();
        Interned reference = parse(buf);
        Interned copy = new Interned().copyFrom(parse(buf));
        assertSameStrings(reference, copy);
    }

    @Test
    public void testClearAndReuse() {
        ByteBuf a = sample();
        ByteBuf b = serialize("other", "tag", "zone", "value", "code");
        Interned reference = parse(a);

        Interned m = new Interned();
        m.parseFrom(a.duplicate(), a.readableBytes());
        assertSameStrings(reference, m);
        m.parseFrom(b.duplicate(), b.readableBytes());
        assertEquals("other", m.getName());
        assertEquals("value", m.getLabels("zone"));
        assertSame(parse(b).getName(), m.getName());

        m.clear();
        m.setName("set");
        assertEquals("set", m.getName());
        m.clear();
        m.parseFrom(a.duplicate(), a.readableBytes());
        assertSameStrings(reference, m);
    }

    @Test
    public void testNoAllocationToReadAsciiStrings() {
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());

        ByteBuf buf = serialize("name", "tag", "key", "value", "code");
        Interned m = new Interned();
        int iterations = 10_000;
        long length = 0;
        for (int i = 0; i < iterations; i++) {
            length += parseAndRead(m, buf);
        }
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < iterations; i++) {
            length += parseAndRead(m, buf);
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertEquals(2 * iterations * 20L, length);
        assertTrue(allocated < iterations, "Allocated " + allocated + " bytes in " + iterations + " iterations");
    }

    private static int parseAndRead(Interned m, ByteBuf buf) {
        m.parseFrom(buf, buf.readableBytes());
        buf.readerIndex(0);
        return m.getName().length() + m.getTagAt(0).length() + m.getTagAt(1).length()
                + m.getLabels("key").length() + m.getCodes(7).length();
    }
}
