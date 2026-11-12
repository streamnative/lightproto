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

import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A reference decoder for the {@code stringDecoder} generator option.
 *
 * <p>It keeps strings of up to {@value #MAX_LENGTH} bytes in a direct-mapped table of {@value #SLOTS}
 * slots, keyed by a hash of the bytes. A hit compares the bytes and returns the cached instance. A
 * miss decodes the bytes and replaces the entry in its slot. Longer strings are decoded without
 * caching. Each entry is immutable, so threads can share the table without locks.
 */
public final class TestStringInterner {

    static final int SLOTS = 1024;
    static final int MAX_LENGTH = 64;

    private static final Entry[] TABLE = new Entry[SLOTS];
    private static final AtomicLong CALLS = new AtomicLong();

    private TestStringInterner() {
    }

    public static String decode(ByteBuf buffer, int index, int length) {
        CALLS.incrementAndGet();
        if (length > MAX_LENGTH) {
            return buffer.toString(index, length, StandardCharsets.UTF_8);
        }
        int hash = 1;
        for (int i = 0; i < length; i++) {
            hash = 31 * hash + buffer.getByte(index + i);
        }
        int slot = (hash ^ (hash >>> 16)) & (SLOTS - 1);
        Entry entry = TABLE[slot];
        if (entry != null && entry.matches(buffer, index, length)) {
            return entry.value;
        }
        byte[] bytes = new byte[length];
        buffer.getBytes(index, bytes);
        String value = new String(bytes, StandardCharsets.UTF_8);
        TABLE[slot] = new Entry(bytes, value);
        return value;
    }

    /** Returns the number of {@link #decode} calls so far. */
    public static long calls() {
        return CALLS.get();
    }

    private static final class Entry {
        private final byte[] bytes;
        private final String value;

        Entry(byte[] bytes, String value) {
            this.bytes = bytes;
            this.value = value;
        }

        boolean matches(ByteBuf buffer, int index, int length) {
            if (bytes.length != length) {
                return false;
            }
            for (int i = 0; i < length; i++) {
                if (bytes[i] != buffer.getByte(index + i)) {
                    return false;
                }
            }
            return true;
        }
    }
}
