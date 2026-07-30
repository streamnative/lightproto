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
package io.netty.buffer;

/**
 * Placed in the {@code io.netty.buffer} package to reach {@link AbstractByteBuf}'s
 * protected unchecked accessors ({@code _getByte} etc.) and its {@code readerIndex}
 * field directly. Callers validate readability once for the whole access, so the
 * per-byte {@code checkReadableBytes0()} / {@code ensureAccessible()} checks that
 * dominate parse profiles are skipped, and each varint performs a single
 * {@code readerIndex} store instead of one per byte.
 *
 * <p>NOTE: this class creates a split package with netty-buffer under the JPMS
 * module path; it is intended for classpath deployments.
 */
public final class LightProtoByteBufAccessTemplate {

    private LightProtoByteBufAccessTemplate() {
    }

    /** Readable bytes via direct field access (no accessibility check). */
    public static int readableBytesFast(AbstractByteBuf b) {
        return b.writerIndex - b.readerIndex;
    }

    /**
     * Read a varint of up to 10 bytes, discarding bits above 32.
     * The caller must have verified at least 10 readable bytes.
     */
    public static int readVarIntUnchecked(AbstractByteBuf buf) {
        int i = buf.readerIndex;
        byte tmp = buf._getByte(i++);
        if (tmp >= 0) {
            buf.readerIndex = i;
            return tmp;
        }
        int result = tmp & 0x7f;
        if ((tmp = buf._getByte(i++)) >= 0) {
            result |= tmp << 7;
        } else {
            result |= (tmp & 0x7f) << 7;
            if ((tmp = buf._getByte(i++)) >= 0) {
                result |= tmp << 14;
            } else {
                result |= (tmp & 0x7f) << 14;
                if ((tmp = buf._getByte(i++)) >= 0) {
                    result |= tmp << 21;
                } else {
                    result |= (tmp & 0x7f) << 21;
                    result |= (tmp = buf._getByte(i++)) << 28;
                    if (tmp < 0) {
                        // Discard upper 32 bits.
                        for (int j = 0; j < 5; j++) {
                            if (buf._getByte(i++) >= 0) {
                                buf.readerIndex = i;
                                return result;
                            }
                        }
                        buf.readerIndex = i;
                        throw new IllegalArgumentException("Encountered a malformed varint.");
                    }
                }
            }
        }
        buf.readerIndex = i;
        return result;
    }

    /**
     * Read a varint of up to 10 bytes as a long.
     * The caller must have verified at least 10 readable bytes.
     */
    public static long readVarInt64Unchecked(AbstractByteBuf buf) {
        int i = buf.readerIndex;
        long result;
        byte tmp = buf._getByte(i++);
        if (tmp >= 0) {
            buf.readerIndex = i;
            return tmp;
        }
        result = tmp & 0x7fL;
        if ((tmp = buf._getByte(i++)) >= 0) {
            result |= (long) tmp << 7;
        } else {
            result |= (tmp & 0x7fL) << 7;
            if ((tmp = buf._getByte(i++)) >= 0) {
                result |= (long) tmp << 14;
            } else {
                result |= (tmp & 0x7fL) << 14;
                if ((tmp = buf._getByte(i++)) >= 0) {
                    result |= (long) tmp << 21;
                } else {
                    result |= (tmp & 0x7fL) << 21;
                    if ((tmp = buf._getByte(i++)) >= 0) {
                        result |= (long) tmp << 28;
                    } else {
                        result |= (tmp & 0x7fL) << 28;
                        if ((tmp = buf._getByte(i++)) >= 0) {
                            result |= (long) tmp << 35;
                        } else {
                            result |= (tmp & 0x7fL) << 35;
                            if ((tmp = buf._getByte(i++)) >= 0) {
                                result |= (long) tmp << 42;
                            } else {
                                result |= (tmp & 0x7fL) << 42;
                                if ((tmp = buf._getByte(i++)) >= 0) {
                                    result |= (long) tmp << 49;
                                } else {
                                    result |= (tmp & 0x7fL) << 49;
                                    if ((tmp = buf._getByte(i++)) >= 0) {
                                        result |= (long) tmp << 56;
                                    } else {
                                        result |= (tmp & 0x7fL) << 56;
                                        result |= ((long) buf._getByte(i++)) << 63;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        buf.readerIndex = i;
        return result;
    }

    /** Read a little-endian fixed 32-bit value; caller verified 4 readable bytes. */
    public static int readFixedInt32Unchecked(AbstractByteBuf buf) {
        int i = buf.readerIndex;
        int v = buf._getIntLE(i);
        buf.readerIndex = i + 4;
        return v;
    }

    /** Read a little-endian fixed 64-bit value; caller verified 8 readable bytes. */
    public static long readFixedInt64Unchecked(AbstractByteBuf buf) {
        int i = buf.readerIndex;
        long v = buf._getLongLE(i);
        buf.readerIndex = i + 8;
        return v;
    }
}
