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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;

import org.junit.jupiter.api.Test;

/**
 * clear() of a message above {@code CLEAR_RETAIN_MAX} (or of unknown size) must
 * release the data references the O(1) clear leaves in place, so a reused
 * (pooled or per-connection) instance doesn't pin the last message's data —
 * the 0.8.0 regression that kept multi-MB topic lists alive on every Pulsar
 * connection decoder. Below the threshold the O(1) clear is unchanged and may
 * retain.
 */
public class ClearReleaseTest {

    /** Unique, non-interned marker string so a WeakReference observes liveness. */
    private static String marker() {
        return new String("marker-" + System.nanoTime() + "-x".repeat(64));
    }

    private static void assertEventuallyCollected(WeakReference<?> ref) throws InterruptedException {
        for (int i = 0; i < 100 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }
        assertNull(ref.get(), "reference should have been released by clear()");
    }

    /** Adds ~size bytes of repeated names plus one weakly-tracked marker. */
    private static WeakReference<String> fillNames(S s, int size) {
        String m = marker();
        s.addName(m);
        int chunk = 500;
        for (int i = 0; i < size / chunk; i++) {
            s.addName("n".repeat(chunk));
        }
        return new WeakReference<>(m);
    }

    @Test
    public void testLargeBuiltMessageReleasesStringsOnClear() throws Exception {
        S s = new S().setId("big");
        WeakReference<String> ref = fillNames(s, 100 * 1024);
        assertTrue(s.getSerializedSize() > LightProtoCodec.CLEAR_RETAIN_MAX);

        s.clear();
        assertEventuallyCollected(ref);
    }

    @Test
    public void testSmallMessageKeepsO1ClearRetention() {
        S s = new S().setId("small");
        WeakReference<String> ref = fillNames(s, 8 * 1024);
        assertTrue(s.getSerializedSize() <= LightProtoCodec.CLEAR_RETAIN_MAX);

        s.clear();
        System.gc();
        // The O(1) clear path deliberately retains: the holder still references
        // the string, so it must survive GC.
        assertNotNull(ref.get());
    }

    @Test
    public void testSmallParsedMessageKeepsO1ClearRetention() {
        // parseFrom() keeps the wire size for the gate, although getSerializedSize()
        // does not trust it.
        S built = new S().setId("small");
        fillNames(built, 8 * 1024);
        S s = new S();
        s.parseFrom(built.toByteArray());
        WeakReference<String> ref = new WeakReference<>(s.getNameAt(0));

        s.clear();
        System.gc();
        assertNotNull(ref.get());
    }

    @Test
    public void testUnknownSizeReleasesConservatively() throws Exception {
        // Never serialized nor cleanly parsed: _cachedSize is -1, so clear()
        // cannot know the message was small and must take the release path.
        S s = new S().setId("unsized");
        WeakReference<String> ref = fillNames(s, 8 * 1024);

        s.clear();
        assertEventuallyCollected(ref);
    }

    /**
     * The Pulsar decoder shape: one reused message per connection, parseFrom()
     * per command (which invokes clear() on the previous contents), getters
     * materializing the strings. The next parse must release them.
     */
    @Test
    public void testParseReuseReleasesMaterializedStrings() throws Exception {
        S big = new S().setId("big");
        fillNames(big, 100 * 1024);
        byte[] bigBytes = big.toByteArray();
        byte[] smallBytes = new S().setId("small").toByteArray();

        S reused = new S();
        reused.parseFrom(bigBytes);
        WeakReference<String> ref = new WeakReference<>(reused.getNameAt(0));

        reused.parseFrom(smallBytes);
        assertEquals("small", reused.getId());
        assertEventuallyCollected(ref);
    }

    /**
     * A large message spread over many small children: every child is far below
     * the threshold, so the release must recurse unconditionally once the
     * top-level gate triggers.
     */
    @Test
    public void testFanOutReleasesThroughForcedRecursion() throws Exception {
        M m = new M();
        String v = marker();
        WeakReference<String> ref = new WeakReference<>(v);
        for (int i = 0; i < 2000; i++) {
            M.KV kv = m.addItem();
            kv.setK("key-" + "k".repeat(40) + i);
            kv.setV(i == 0 ? v : "val-" + "v".repeat(40) + i);
        }
        v = null;
        assertTrue(m.getSerializedSize() > LightProtoCodec.CLEAR_RETAIN_MAX);

        m.clear();
        assertEventuallyCollected(ref);
    }

    @Test
    public void testLargeBytesPayloadReleasedOnClear() throws Exception {
        byte[] payload = new byte[2 * 1024 * 1024];
        WeakReference<byte[]> ref = new WeakReference<>(payload);
        B b = new B().setPayload(payload);
        payload = null;
        assertTrue(b.getSerializedSize() > LightProtoCodec.CLEAR_RETAIN_MAX);

        b.clear();
        assertEventuallyCollected(ref);
    }

    /** Deep clear must leave the instance as reusable as the O(1) clear does. */
    @Test
    public void testReuseAfterDeepClear() {
        S s = new S().setId("big");
        fillNames(s, 100 * 1024);
        assertTrue(s.getSerializedSize() > LightProtoCodec.CLEAR_RETAIN_MAX);
        s.clear();

        s.setId("after");
        s.addName("one");
        s.addName("two");
        byte[] reused = s.toByteArray();

        S freshMsg = new S().setId("after");
        freshMsg.addName("one");
        freshMsg.addName("two");
        byte[] fresh = freshMsg.toByteArray();
        assertArrayEquals(fresh, reused);

        S parsed = new S();
        parsed.parseFrom(reused);
        assertEquals("after", parsed.getId());
        assertEquals(2, parsed.getNamesCount());
        assertEquals("one", parsed.getNameAt(0));
        assertEquals("two", parsed.getNameAt(1));
    }
}
