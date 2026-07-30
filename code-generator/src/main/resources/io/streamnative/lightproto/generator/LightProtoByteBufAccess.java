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
 * protected unchecked accessors ({@code _getByte}) and its {@code readerIndex}
 * field directly. The per-byte {@code checkReadableBytes0()} /
 * {@code ensureAccessible()} checks that dominate parse profiles are skipped, and
 * each varint performs a single {@code readerIndex} store instead of one per byte.
 *
 * <p>Reads are not bounds-checked here: on truncated input a read may advance up
 * to 9 bytes past the intended message limit (a 10-byte read whose first byte is
 * inside the limit). The generated {@code parseFrom()} detects that afterwards
 * and throws, preserving the throw-on-truncated contract.
 *
 * <p>NOTE: this class creates a split package with netty-buffer under the JPMS
 * module path; it is intended for classpath deployments.
 */
public final class LightProtoByteBufAccessTemplate {

    private LightProtoByteBufAccessTemplate() {
    }

    /**
     * Read a varint of up to 10 bytes as a long.
     *
     * <p>The 1-2 byte cases are decoded here so the method stays far below C2's
     * FreqInlineSize: the full nested chain compiles to ~371 bytecodes and is
     * never inlined, so routing short varints through it costs a call per read —
     * a net loss on small values. Longer varints fall into the chain, whose
     * multi-byte decode amortizes the call.
     */
    public static long readVarInt64Unchecked(AbstractByteBuf buf) {
        int i = buf.readerIndex;
        byte b0 = buf._getByte(i);
        if (b0 >= 0) {
            buf.readerIndex = i + 1;
            return b0;
        }
        byte b1 = buf._getByte(i + 1);
        if (b1 >= 0) {
            buf.readerIndex = i + 2;
            return (b0 & 0x7f) | ((long) b1 << 7);
        }
        return readVarInt64UncheckedChain(buf);
    }

    private static long readVarInt64UncheckedChain(AbstractByteBuf buf) {
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
}
