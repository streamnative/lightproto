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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

class LightProtoCodec {

    private static final boolean HAS_UNSAFE;
    private static final long STRING_VALUE_OFFSET;
    static final long BYTE_ARRAY_BASE_OFFSET;
    static final boolean LITTLE_ENDIAN = java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN;
    // True when JDK compact strings are enabled (default since JDK 9).
    // When disabled via -XX:-CompactStrings, String's internal byte[] uses UTF-16
    // and we must not use the Unsafe string fast paths.
    private static final boolean COMPACT_STRINGS;

    // MethodHandles for Unsafe operations, resolved via reflection to avoid
    // referencing sun.misc.Unsafe as a type (which triggers javac warnings).
    // HotSpot inlines invokeExact on static final MethodHandles.
    private static final MethodHandle MH_PUT_BYTE;
    private static final MethodHandle MH_PUT_INT;
    private static final MethodHandle MH_PUT_LONG;
    private static final MethodHandle MH_GET_OBJECT;
    private static final MethodHandle MH_PUT_OBJECT;
    private static final MethodHandle MH_COPY_MEMORY;
    private static final MethodHandle MH_GET_LONG;
    private static final MethodHandle MH_ALLOCATE_INSTANCE;

    static {
        boolean hasUnsafe = false;
        long offset = -1;
        long arrayBase = -1;
        boolean compactStrings = false;
        MethodHandle mhPutByte = null;
        MethodHandle mhPutInt = null;
        MethodHandle mhPutLong = null;
        MethodHandle mhGetObject = null;
        MethodHandle mhPutObject = null;
        MethodHandle mhCopyMemory = null;
        MethodHandle mhGetLong = null;
        MethodHandle mhAllocateInstance = null;
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);

            // Use reflection for init-only operations
            Method objectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);
            Method arrayBaseOffsetMethod = unsafeClass.getMethod("arrayBaseOffset", Class.class);
            Method getObjectMethod = unsafeClass.getMethod("getObject", Object.class, long.class);

            offset = (long) objectFieldOffset.invoke(unsafe, String.class.getDeclaredField("value"));
            arrayBase = (int) arrayBaseOffsetMethod.invoke(unsafe, byte[].class);
            // Detect compact strings: an ASCII string's internal byte[] length
            // equals the string length when compact strings are enabled (LATIN1 coder),
            // but is 2x the string length when disabled (UTF-16 coder).
            byte[] testValue = (byte[]) getObjectMethod.invoke(unsafe, "a", offset);
            compactStrings = (testValue.length == 1);

            // Create MethodHandles for hot-path operations, bound to the Unsafe instance
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            mhPutByte = lookup.unreflect(
                    unsafeClass.getMethod("putByte", Object.class, long.class, byte.class)).bindTo(unsafe);
            mhPutInt = lookup.unreflect(
                    unsafeClass.getMethod("putInt", Object.class, long.class, int.class)).bindTo(unsafe);
            mhPutLong = lookup.unreflect(
                    unsafeClass.getMethod("putLong", Object.class, long.class, long.class)).bindTo(unsafe);
            mhGetObject = lookup.unreflect(getObjectMethod).bindTo(unsafe);
            mhPutObject = lookup.unreflect(
                    unsafeClass.getMethod("putObject", Object.class, long.class, Object.class)).bindTo(unsafe);
            mhCopyMemory = lookup.unreflect(
                    unsafeClass.getMethod("copyMemory", Object.class, long.class, Object.class, long.class, long.class))
                    .bindTo(unsafe);
            mhGetLong = lookup.unreflect(
                    unsafeClass.getMethod("getLong", Object.class, long.class)).bindTo(unsafe);
            mhAllocateInstance = lookup.unreflect(
                    unsafeClass.getMethod("allocateInstance", Class.class)).bindTo(unsafe);
            hasUnsafe = true;
        } catch (Throwable ignore) {
            // Fallback to non-Unsafe path
        }
        HAS_UNSAFE = hasUnsafe;
        STRING_VALUE_OFFSET = offset;
        BYTE_ARRAY_BASE_OFFSET = arrayBase;
        COMPACT_STRINGS = compactStrings;
        MH_PUT_BYTE = mhPutByte;
        MH_PUT_INT = mhPutInt;
        MH_PUT_LONG = mhPutLong;
        MH_GET_OBJECT = mhGetObject;
        MH_PUT_OBJECT = mhPutObject;
        MH_COPY_MEMORY = mhCopyMemory;
        MH_GET_LONG = mhGetLong;
        MH_ALLOCATE_INSTANCE = mhAllocateInstance;
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
            if (HAS_UNSAFE && COMPACT_STRINGS) {
                try {
                    Object _v = (Object) MH_GET_OBJECT.invokeExact((Object) s, STRING_VALUE_OFFSET);
                    b.writeBytes((byte[]) _v, 0, bytesCount);
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
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
        try {
            MH_PUT_BYTE.invokeExact(base, addr, (byte) value);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        return addr + 1;
    }

    static long writeRawVarInt(Object base, long addr, int n) {
        try {
            if (n >= 0) {
                while (true) {
                    if ((n & ~0x7F) == 0) {
                        MH_PUT_BYTE.invokeExact(base, addr++, (byte) n);
                        return addr;
                    }
                    MH_PUT_BYTE.invokeExact(base, addr++, (byte) ((n & 0x7F) | 0x80));
                    n >>>= 7;
                }
            } else {
                return writeRawVarInt64(base, addr, n);
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    static long writeRawVarInt64(Object base, long addr, long value) {
        try {
            while (true) {
                if ((value & ~0x7FL) == 0) {
                    MH_PUT_BYTE.invokeExact(base, addr++, (byte) value);
                    return addr;
                }
                MH_PUT_BYTE.invokeExact(base, addr++, (byte) (((int) value & 0x7F) | 0x80));
                value >>>= 7;
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    static long writeRawSignedVarInt(Object base, long addr, int n) {
        return writeRawVarInt(base, addr, encodeZigZag32(n));
    }

    static long writeRawSignedVarInt64(Object base, long addr, long n) {
        return writeRawVarInt64(base, addr, encodeZigZag64(n));
    }

    static long writeRawLittleEndian32(Object base, long addr, int value) {
        try {
            MH_PUT_INT.invokeExact(base, addr, LITTLE_ENDIAN ? value : Integer.reverseBytes(value));
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        return addr + 4;
    }

    static long writeRawLittleEndian64(Object base, long addr, long value) {
        try {
            MH_PUT_LONG.invokeExact(base, addr, LITTLE_ENDIAN ? value : Long.reverseBytes(value));
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
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
        if (COMPACT_STRINGS && s.length() == bytesCount) {
            try {
                Object _v = (Object) MH_GET_OBJECT.invokeExact((Object) s, STRING_VALUE_OFFSET);
                MH_COPY_MEMORY.invokeExact((Object) _v, BYTE_ARRAY_BASE_OFFSET, base, addr, (long) bytesCount);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
            return addr + bytesCount;
        }
        return -1;
    }

    static String readString(ByteBuf b, int index, int len) {
        if (HAS_UNSAFE && STRING_VALUE_OFFSET >= 0) {
            try {
                // Allocate target byte[] and copy directly from ByteBuf memory,
                // bypassing Netty's getBytes chain (checkIndex, checkRangeBounds, etc.)
                byte[] value = new byte[len];
                if (b.hasMemoryAddress()) {
                    MH_COPY_MEMORY.invokeExact((Object) null, b.memoryAddress() + index,
                            (Object) value, BYTE_ARRAY_BASE_OFFSET, (long) len);
                } else if (b.hasArray()) {
                    MH_COPY_MEMORY.invokeExact((Object) b.array(),
                            BYTE_ARRAY_BASE_OFFSET + b.arrayOffset() + index,
                            (Object) value, BYTE_ARRAY_BASE_OFFSET, (long) len);
                } else {
                    b.getBytes(index, value, 0, len);
                }

                // For ASCII strings (all bytes < 128), create a String directly via Unsafe,
                // injecting the byte[] as the internal value with LATIN1 coder (0).
                // This eliminates the second copy that new String() would do.
                // Only possible when compact strings are enabled (-XX:+CompactStrings, the default).
                if (COMPACT_STRINGS && _isAscii(value, len)) {
                    Object _s = (Object) MH_ALLOCATE_INSTANCE.invokeExact(String.class);
                    MH_PUT_OBJECT.invokeExact(_s, STRING_VALUE_OFFSET, (Object) value);
                    // coder=0 (LATIN1) is already set by zero-initialization from allocateInstance
                    return (String) _s;
                }

                // Non-ASCII or compact strings disabled: decode properly
                return new String(value, 0, len, StandardCharsets.UTF_8);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
        return b.toString(index, len, StandardCharsets.UTF_8);
    }

    private static boolean _isAscii(byte[] bytes, int len) {
        try {
            // Check 8 bytes at a time using long reads — data is in L1 cache from the copy
            int i = 0;
            for (; i + 7 < len; i += 8) {
                if (((long) MH_GET_LONG.invokeExact((Object) bytes, BYTE_ARRAY_BASE_OFFSET + i)
                        & 0x8080808080808080L) != 0) {
                    return false;
                }
            }
            // Check remaining bytes
            for (; i < len; i++) {
                if (bytes[i] < 0) return false;
            }
            return true;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
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

    interface LightProtoMessage {
        int getSerializedSize();
        int writeTo(ByteBuf b);
        void parseFrom(ByteBuf buffer, int size);
        void parseFrom(byte[] a);
        void materialize();
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
