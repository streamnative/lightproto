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
package io.github.merlimat.lightproto.generator;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

import java.nio.charset.StandardCharsets;

@SuppressWarnings("sunapi")
class LightProtoCodec {

    static final sun.misc.Unsafe UNSAFE;
    private static final long STRING_VALUE_OFFSET;
    static final long BYTE_ARRAY_BASE_OFFSET;
    static final boolean LITTLE_ENDIAN = java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN;

    static {
        sun.misc.Unsafe unsafe = null;
        long offset = -1;
        long arrayBase = -1;
        try {
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (sun.misc.Unsafe) f.get(null);
            offset = unsafe.objectFieldOffset(String.class.getDeclaredField("value"));
            arrayBase = unsafe.arrayBaseOffset(byte[].class);
        } catch (Exception e) {
            // Fallback to getBytes path
        }
        UNSAFE = unsafe;
        STRING_VALUE_OFFSET = offset;
        BYTE_ARRAY_BASE_OFFSET = arrayBase;
    }

    static final int TAG_TYPE_MASK = 7;
    static final int TAG_TYPE_BITS = 3;
    static final int WIRETYPE_VARINT = 0;
    static final int WIRETYPE_FIXED64 = 1;
    static final int WIRETYPE_LENGTH_DELIMITED = 2;
    static final int WIRETYPE_START_GROUP = 3;
    static final int WIRETYPE_END_GROUP = 4;
    static final int WIRETYPE_FIXED32 = 5;
    private LightProtoCodec() {
    }

    private static int getTagType(int tag) {
        return tag & TAG_TYPE_MASK;
    }

    static int getFieldId(int tag) {
        return tag >>> TAG_TYPE_BITS;
    }

    static void writeVarInt(ByteBuf b, int n) {
        if (n >= 0) {
            _writeVarInt(b, n);
        } else {
            writeVarInt64(b, n);
        }
    }

    static void writeSignedVarInt(ByteBuf b, int n) {
        writeVarInt(b, encodeZigZag32(n));
    }

    static int readSignedVarInt(ByteBuf b) {
        return decodeZigZag32(readVarInt(b));
    }

    static long readSignedVarInt64(ByteBuf b) {
        return decodeZigZag64(readVarInt64(b));
    }

    static void writeFloat(ByteBuf b, float n) {
        writeFixedInt32(b, Float.floatToRawIntBits(n));
    }

    static void writeDouble(ByteBuf b, double n) {
        writeFixedInt64(b, Double.doubleToRawLongBits(n));
    }

    static float readFloat(ByteBuf b) {
        return Float.intBitsToFloat(readFixedInt32(b));
    }

    static double readDouble(ByteBuf b) {
        return Double.longBitsToDouble(readFixedInt64(b));
    }

    private static void _writeVarInt(ByteBuf b, int n) {
        while (true) {
            if ((n & ~0x7F) == 0) {
                b.writeByte(n);
                return;
            } else {
                b.writeByte((n & 0x7F) | 0x80);
                n >>>= 7;
            }
        }
    }

    static void writeVarInt64(ByteBuf b, long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                b.writeByte((int) value);
                return;
            } else {
                b.writeByte(((int) value & 0x7F) | 0x80);
                value >>>= 7;
            }
        }
    }

    static void writeFixedInt32(ByteBuf b, int n) {
        b.writeIntLE(n);
    }

    static void writeFixedInt64(ByteBuf b, long n) {
        b.writeLongLE(n);
    }

    static int readFixedInt32(ByteBuf b) {
        return b.readIntLE();
    }

    static long readFixedInt64(ByteBuf b) {
        return b.readLongLE();
    }


    static void writeSignedVarInt64(ByteBuf b, long n) {
        writeVarInt64(b, encodeZigZag64(n));
    }

    private static int encodeZigZag32(final int n) {
        return (n << 1) ^ (n >> 31);
    }

    private static long encodeZigZag64(final long n) {
        return (n << 1) ^ (n >> 63);
    }

    private static int decodeZigZag32(int n) {
        return n >>> 1 ^ -(n & 1);
    }

    private static long decodeZigZag64(long n) {
        return n >>> 1 ^ -(n & 1L);
    }

    static int readVarInt(ByteBuf buf) {
        byte tmp = buf.readByte();
        if (tmp >= 0) {
            return tmp;
        }
        int result = tmp & 0x7f;
        if ((tmp = buf.readByte()) >= 0) {
            result |= tmp << 7;
        } else {
            result |= (tmp & 0x7f) << 7;
            if ((tmp = buf.readByte()) >= 0) {
                result |= tmp << 14;
            } else {
                result |= (tmp & 0x7f) << 14;
                if ((tmp = buf.readByte()) >= 0) {
                    result |= tmp << 21;
                } else {
                    result |= (tmp & 0x7f) << 21;
                    result |= (tmp = buf.readByte()) << 28;
                    if (tmp < 0) {
                        // Discard upper 32 bits.
                        for (int i = 0; i < 5; i++) {
                            if (buf.readByte() >= 0) {
                                return result;
                            }
                        }
                        throw new IllegalArgumentException("Encountered a malformed varint.");
                    }
                }
            }
        }
        return result;
    }

    static long readVarInt64(ByteBuf buf) {
        long result;
        byte tmp = buf.readByte();
        if (tmp >= 0) {
            return tmp;
        }
        result = tmp & 0x7fL;
        if ((tmp = buf.readByte()) >= 0) {
            result |= (long) tmp << 7;
        } else {
            result |= (tmp & 0x7fL) << 7;
            if ((tmp = buf.readByte()) >= 0) {
                result |= (long) tmp << 14;
            } else {
                result |= (tmp & 0x7fL) << 14;
                if ((tmp = buf.readByte()) >= 0) {
                    result |= (long) tmp << 21;
                } else {
                    result |= (tmp & 0x7fL) << 21;
                    if ((tmp = buf.readByte()) >= 0) {
                        result |= (long) tmp << 28;
                    } else {
                        result |= (tmp & 0x7fL) << 28;
                        if ((tmp = buf.readByte()) >= 0) {
                            result |= (long) tmp << 35;
                        } else {
                            result |= (tmp & 0x7fL) << 35;
                            if ((tmp = buf.readByte()) >= 0) {
                                result |= (long) tmp << 42;
                            } else {
                                result |= (tmp & 0x7fL) << 42;
                                if ((tmp = buf.readByte()) >= 0) {
                                    result |= (long) tmp << 49;
                                } else {
                                    result |= (tmp & 0x7fL) << 49;
                                    if ((tmp = buf.readByte()) >= 0) {
                                        result |= (long) tmp << 56;
                                    } else {
                                        result |= (tmp & 0x7fL) << 56;
                                        result |= ((long) buf.readByte()) << 63;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    static int computeSignedVarIntSize(final int value) {
        return computeVarUIntSize(encodeZigZag32(value));
    }

    static int computeSignedVarInt64Size(final long value) {
        return computeVarInt64Size(encodeZigZag64(value));
    }

    static int computeVarIntSize(final int value) {
        if (value < 0) {
            return 10;
        } else {
            return computeVarUIntSize(value);
        }
    }

    static int computeVarUIntSize(final int value) {
        if ((value & (0xffffffff << 7)) == 0) {
            return 1;
        } else if ((value & (0xffffffff << 14)) == 0) {
            return 2;
        } else if ((value & (0xffffffff << 21)) == 0) {
            return 3;
        } else if ((value & (0xffffffff << 28)) == 0) {
            return 4;
        } else {
            return 5;
        }
    }

    static int computeVarInt64Size(final long value) {
        if ((value & (0xffffffffffffffffL << 7)) == 0) {
            return 1;
        } else if ((value & (0xffffffffffffffffL << 14)) == 0) {
            return 2;
        } else if ((value & (0xffffffffffffffffL << 21)) == 0) {
            return 3;
        } else if ((value & (0xffffffffffffffffL << 28)) == 0) {
            return 4;
        } else if ((value & (0xffffffffffffffffL << 35)) == 0) {
            return 5;
        } else if ((value & (0xffffffffffffffffL << 42)) == 0) {
            return 6;
        } else if ((value & (0xffffffffffffffffL << 49)) == 0) {
            return 7;
        } else if ((value & (0xffffffffffffffffL << 56)) == 0) {
            return 8;
        } else if ((value & (0xffffffffffffffffL << 63)) == 0) {
            return 9;
        } else {
            return 10;
        }
    }

    static int computeStringUTF8Size(String s) {
        return ByteBufUtil.utf8Bytes(s);
    }

    static void writeString(ByteBuf b, String s, int bytesCount) {
        if (s.length() == bytesCount) {
            // ASCII fast path: read String's internal byte[] directly via Unsafe,
            // then writeBytes in a single copy with zero intermediate allocation.
            // On JDK 9+ compact strings, ASCII strings use LATIN1 coder and the
            // internal value byte[] contains exactly the bytes we need.
            if (UNSAFE != null) {
                byte[] value = (byte[]) UNSAFE.getObject(s, STRING_VALUE_OFFSET);
                b.writeBytes(value, 0, bytesCount);
            } else {
                b.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
            }
        } else {
            ByteBufUtil.reserveAndWriteUtf8(b, s, bytesCount);
        }
    }

    // --- Unsafe raw write methods for zero-overhead serialization ---
    // These bypass all Netty ByteBuf boundary checks by writing directly to memory.
    // Used by generated writeTo() methods after a single ensureWritable() call.

    static long writeRawByte(Object base, long addr, int value) {
        UNSAFE.putByte(base, addr, (byte) value);
        return addr + 1;
    }

    static long writeRawVarInt(Object base, long addr, int n) {
        if (n >= 0) {
            while (true) {
                if ((n & ~0x7F) == 0) {
                    UNSAFE.putByte(base, addr++, (byte) n);
                    return addr;
                }
                UNSAFE.putByte(base, addr++, (byte) ((n & 0x7F) | 0x80));
                n >>>= 7;
            }
        } else {
            return writeRawVarInt64(base, addr, n);
        }
    }

    static long writeRawVarInt64(Object base, long addr, long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                UNSAFE.putByte(base, addr++, (byte) value);
                return addr;
            }
            UNSAFE.putByte(base, addr++, (byte) (((int) value & 0x7F) | 0x80));
            value >>>= 7;
        }
    }

    static long writeRawSignedVarInt(Object base, long addr, int n) {
        return writeRawVarInt(base, addr, encodeZigZag32(n));
    }

    static long writeRawSignedVarInt64(Object base, long addr, long n) {
        return writeRawVarInt64(base, addr, encodeZigZag64(n));
    }

    static long writeRawLittleEndian32(Object base, long addr, int value) {
        UNSAFE.putInt(base, addr, LITTLE_ENDIAN ? value : Integer.reverseBytes(value));
        return addr + 4;
    }

    static long writeRawLittleEndian64(Object base, long addr, long value) {
        UNSAFE.putLong(base, addr, LITTLE_ENDIAN ? value : Long.reverseBytes(value));
        return addr + 8;
    }

    static long writeRawFloat(Object base, long addr, float n) {
        return writeRawLittleEndian32(base, addr, Float.floatToRawIntBits(n));
    }

    static long writeRawDouble(Object base, long addr, double n) {
        return writeRawLittleEndian64(base, addr, Double.doubleToRawLongBits(n));
    }

    /**
     * Write an ASCII string directly via Unsafe. Returns new addr on success,
     * or -1 if the string is non-ASCII and needs UTF-8 encoding via ByteBuf.
     */
    static long writeRawString(Object base, long addr, String s, int bytesCount) {
        if (s.length() == bytesCount) {
            byte[] value = (byte[]) UNSAFE.getObject(s, STRING_VALUE_OFFSET);
            UNSAFE.copyMemory(value, BYTE_ARRAY_BASE_OFFSET, base, addr, bytesCount);
            return addr + bytesCount;
        }
        return -1;
    }

    static String readString(ByteBuf b, int index, int len) {
        return b.toString(index, len, StandardCharsets.UTF_8);
    }

    static void skipUnknownField(int tag, ByteBuf buffer) {
        int tagType = getTagType(tag);
        switch (tagType) {
            case WIRETYPE_VARINT:
                readVarInt(buffer);
                break;
            case WIRETYPE_FIXED64:
                buffer.skipBytes(8);
                break;
            case WIRETYPE_LENGTH_DELIMITED:
                int len = readVarInt(buffer);
                buffer.skipBytes(len);
                break;
            case WIRETYPE_FIXED32:
                buffer.skipBytes(4);
                break;
            default:
                throw new IllegalArgumentException("Invalid unknonwn tag type: " + tagType);
        }
    }

    static final class StringHolder {
        String s;
        int idx;
        int len;
    }

    static final class BytesHolder {
        ByteBuf b;
        int idx;
        int len;
    }
}
