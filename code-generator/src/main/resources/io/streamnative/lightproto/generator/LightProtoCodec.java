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
        int writeJsonTo(ByteBuf b);
        void parseFrom(ByteBuf buffer, int size);
        void parseFrom(byte[] a);
        void parseFromJson(byte[] a);
        void parseFromJson(ByteBuf b);
        void materialize();
    }

    static void parseFromJson(LightProtoMessage msg, String json) {
        msg.parseFromJson(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String toJson(LightProtoMessage msg) {
        ByteBuf buf = io.netty.buffer.Unpooled.buffer(256);
        try {
            msg.writeJsonTo(buf);
            return buf.toString(java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            buf.release();
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

    // ==================== JSON serialization helpers ====================

    private static final byte[] HEX_CHARS = "0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    static void writeJsonFieldName(ByteBuf b, String name) {
        b.writeByte('"');
        b.writeCharSequence(name, java.nio.charset.StandardCharsets.US_ASCII);
        b.writeByte('"');
        b.writeByte(':');
    }

    static void writeJsonString(ByteBuf b, String s) {
        b.writeByte('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.writeByte('\\');
                    b.writeByte('"');
                    break;
                case '\\':
                    b.writeByte('\\');
                    b.writeByte('\\');
                    break;
                case '\b':
                    b.writeByte('\\');
                    b.writeByte('b');
                    break;
                case '\f':
                    b.writeByte('\\');
                    b.writeByte('f');
                    break;
                case '\n':
                    b.writeByte('\\');
                    b.writeByte('n');
                    break;
                case '\r':
                    b.writeByte('\\');
                    b.writeByte('r');
                    break;
                case '\t':
                    b.writeByte('\\');
                    b.writeByte('t');
                    break;
                default:
                    if (c < 0x20) {
                        b.writeByte('\\');
                        b.writeByte('u');
                        b.writeByte(HEX_CHARS[(c >> 12) & 0xF]);
                        b.writeByte(HEX_CHARS[(c >> 8) & 0xF]);
                        b.writeByte(HEX_CHARS[(c >> 4) & 0xF]);
                        b.writeByte(HEX_CHARS[c & 0xF]);
                    } else {
                        b.writeCharSequence(String.valueOf(c), java.nio.charset.StandardCharsets.UTF_8);
                    }
            }
        }
        b.writeByte('"');
    }

    static void writeJsonBase64(ByteBuf b, ByteBuf data, int offset, int len) {
        byte[] raw = new byte[len];
        data.getBytes(offset, raw);
        b.writeByte('"');
        b.writeCharSequence(java.util.Base64.getEncoder().encodeToString(raw),
                java.nio.charset.StandardCharsets.US_ASCII);
        b.writeByte('"');
    }

    static void writeJsonAscii(ByteBuf b, String s) {
        b.writeCharSequence(s, java.nio.charset.StandardCharsets.US_ASCII);
    }

    // ==================== JSON parsing utilities ====================

    /**
     * Lightweight recursive-descent JSON reader operating on a byte array.
     * Supports the subset of JSON used by protobuf's JsonFormat.
     */
    static final class JsonReader {
        private final ByteBuf buf;

        JsonReader(ByteBuf buf) {
            this.buf = buf;
        }

        ByteBuf buf() { return buf; }

        private int readable() { return buf.readableBytes(); }
        private byte at(int offset) { return buf.getByte(buf.readerIndex() + offset); }

        void skipWhitespace() {
            while (readable() > 0) {
                byte c = at(0);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    buf.skipBytes(1);
                } else {
                    break;
                }
            }
        }

        byte peek() {
            skipWhitespace();
            if (readable() <= 0) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            return at(0);
        }

        void expect(byte expected) {
            skipWhitespace();
            if (readable() <= 0 || at(0) != expected) {
                throw new IllegalArgumentException("Expected '" + (char) expected
                        + "' at position " + buf.readerIndex() + " but found "
                        + (readable() > 0 ? "'" + (char) at(0) + "'" : "end of input"));
            }
            buf.skipBytes(1);
        }

        boolean tryConsume(byte expected) {
            skipWhitespace();
            if (readable() > 0 && at(0) == expected) {
                buf.skipBytes(1);
                return true;
            }
            return false;
        }

        /**
         * Read a JSON string value (the opening '"' must be next).
         * Handles escape sequences including unicode escapes.
         */
        String readString() {
            expect((byte) '"');
            StringBuilder sb = new StringBuilder();
            while (readable() > 0) {
                byte c = buf.readByte();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (readable() <= 0) {
                        throw new IllegalArgumentException("Unexpected end of JSON in string escape");
                    }
                    byte esc = buf.readByte();
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (readable() < 4) {
                                throw new IllegalArgumentException("Incomplete \\u escape");
                            }
                            byte[] hex = new byte[4];
                            buf.readBytes(hex);
                            int cp = Integer.parseInt(new String(hex, java.nio.charset.StandardCharsets.US_ASCII), 16);
                            sb.append((char) cp);
                            break;
                        default:
                            throw new IllegalArgumentException("Invalid escape: \\" + (char) esc);
                    }
                } else {
                    // Handle multi-byte UTF-8
                    if ((c & 0x80) == 0) {
                        sb.append((char) c);
                    } else if ((c & 0xE0) == 0xC0) {
                        int c2 = buf.readByte() & 0xFF;
                        sb.append((char) (((c & 0x1F) << 6) | (c2 & 0x3F)));
                    } else if ((c & 0xF0) == 0xE0) {
                        int c2 = buf.readByte() & 0xFF;
                        int c3 = buf.readByte() & 0xFF;
                        sb.append((char) (((c & 0x0F) << 12) | ((c2 & 0x3F) << 6) | (c3 & 0x3F)));
                    } else if ((c & 0xF8) == 0xF0) {
                        int c2 = buf.readByte() & 0xFF;
                        int c3 = buf.readByte() & 0xFF;
                        int c4 = buf.readByte() & 0xFF;
                        int codePoint = ((c & 0x07) << 18) | ((c2 & 0x3F) << 12)
                                | ((c3 & 0x3F) << 6) | (c4 & 0x3F);
                        sb.appendCodePoint(codePoint);
                    }
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        /**
         * Read a JSON number token as a raw string (for parsing into int/long/float/double).
         * Also handles quoted numbers (protobuf JSON quotes int64 types).
         */
        String readNumberToken() {
            skipWhitespace();
            boolean quoted = false;
            if (readable() > 0 && at(0) == '"') {
                quoted = true;
                buf.skipBytes(1);
            }
            int start = buf.readerIndex();
            while (readable() > 0) {
                byte c = at(0);
                if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E'
                        || (c >= '0' && c <= '9')) {
                    buf.skipBytes(1);
                } else {
                    break;
                }
            }
            int len = buf.readerIndex() - start;
            byte[] tokenBytes = new byte[len];
            buf.getBytes(start, tokenBytes);
            String token = new String(tokenBytes, java.nio.charset.StandardCharsets.US_ASCII);
            if (quoted) {
                expect((byte) '"');
            }
            return token;
        }

        int readInt() {
            return Integer.parseInt(readNumberToken());
        }

        long readLong() {
            return Long.parseLong(readNumberToken());
        }

        float readFloat() {
            skipWhitespace();
            if (readable() > 0 && at(0) == '"') {
                // Handle special float values: "NaN", "Infinity", "-Infinity"
                String s = readString();
                return Float.parseFloat(s);
            }
            return Float.parseFloat(readNumberToken());
        }

        double readDouble() {
            skipWhitespace();
            if (readable() > 0 && at(0) == '"') {
                String s = readString();
                return Double.parseDouble(s);
            }
            return Double.parseDouble(readNumberToken());
        }

        boolean readBool() {
            skipWhitespace();
            if (readable() >= 4 && at(0) == 't' && at(1) == 'r'
                    && at(2) == 'u' && at(3) == 'e') {
                buf.skipBytes(4);
                return true;
            }
            if (readable() >= 5 && at(0) == 'f' && at(1) == 'a'
                    && at(2) == 'l' && at(3) == 's' && at(4) == 'e') {
                buf.skipBytes(5);
                return false;
            }
            throw new IllegalArgumentException("Expected 'true' or 'false' at position " + buf.readerIndex());
        }

        /**
         * Read a base64-encoded bytes field value.
         */
        byte[] readBase64Bytes() {
            String encoded = readString();
            return java.util.Base64.getDecoder().decode(encoded);
        }

        /**
         * Skip an unknown JSON value (object, array, string, number, boolean, null).
         */
        void skipValue() {
            skipWhitespace();
            if (readable() <= 0) return;
            byte c = at(0);
            if (c == '"') {
                readString();
            } else if (c == '{') {
                buf.skipBytes(1);
                if (!tryConsume((byte) '}')) {
                    do {
                        readString(); // key
                        expect((byte) ':');
                        skipValue();
                    } while (tryConsume((byte) ','));
                    expect((byte) '}');
                }
            } else if (c == '[') {
                buf.skipBytes(1);
                if (!tryConsume((byte) ']')) {
                    do {
                        skipValue();
                    } while (tryConsume((byte) ','));
                    expect((byte) ']');
                }
            } else if (c == 't' || c == 'f') {
                readBool();
            } else if (c == 'n') {
                // null
                if (readable() >= 4 && at(1) == 'u'
                        && at(2) == 'l' && at(3) == 'l') {
                    buf.skipBytes(4);
                } else {
                    throw new IllegalArgumentException("Invalid token at position " + buf.readerIndex());
                }
            } else {
                // number
                readNumberToken();
            }
        }

        boolean isEof() {
            skipWhitespace();
            return readable() <= 0;
        }
    }

    // ==================== TextFormat serialization helpers ====================

    interface LightProtoTextFormatMessage {
        void writeTextFormatTo(StringBuilder sb, int indent);
    }

    static void writeTextFormatIndent(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
    }

    static void writeTextFormatString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            appendTextFormatEscapedChar(sb, c);
        }
        sb.append('"');
    }

    static void writeTextFormatBytes(StringBuilder sb, ByteBuf data, int offset, int len) {
        sb.append('"');
        for (int i = 0; i < len; i++) {
            int b = data.getByte(offset + i) & 0xFF;
            appendTextFormatEscapedByte(sb, b);
        }
        sb.append('"');
    }

    private static void appendTextFormatEscapedChar(StringBuilder sb, char c) {
        switch (c) {
            case '\b': sb.append("\\b"); return;
            case '\f': sb.append("\\f"); return;
            case '\n': sb.append("\\n"); return;
            case '\r': sb.append("\\r"); return;
            case '\t': sb.append("\\t"); return;
            case '\\': sb.append("\\\\"); return;
            case '\'': sb.append("\\'"); return;
            case '"':  sb.append("\\\""); return;
            default:
                if (c >= 0x20 && c < 0x7F) {
                    sb.append(c);
                } else {
                    // Encode as UTF-8 escape sequences (\xNN per byte)
                    byte[] enc = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    for (byte b : enc) {
                        appendOctalEscape(sb, b & 0xFF);
                    }
                }
        }
    }

    private static void appendTextFormatEscapedByte(StringBuilder sb, int b) {
        switch (b) {
            case '\b': sb.append("\\b"); return;
            case '\f': sb.append("\\f"); return;
            case '\n': sb.append("\\n"); return;
            case '\r': sb.append("\\r"); return;
            case '\t': sb.append("\\t"); return;
            case '\\': sb.append("\\\\"); return;
            case '\'': sb.append("\\'"); return;
            case '"':  sb.append("\\\""); return;
            default:
                if (b >= 0x20 && b < 0x7F) {
                    sb.append((char) b);
                } else {
                    appendOctalEscape(sb, b);
                }
        }
    }

    private static void appendOctalEscape(StringBuilder sb, int b) {
        sb.append('\\');
        sb.append((char) ('0' + ((b >> 6) & 0x3)));
        sb.append((char) ('0' + ((b >> 3) & 0x7)));
        sb.append((char) ('0' + (b & 0x7)));
    }

    static void writeTextFormatFloat(StringBuilder sb, float f) {
        if (Float.isNaN(f)) {
            sb.append("nan");
        } else if (f == Float.POSITIVE_INFINITY) {
            sb.append("inf");
        } else if (f == Float.NEGATIVE_INFINITY) {
            sb.append("-inf");
        } else {
            sb.append(Float.toString(f));
        }
    }

    static void writeTextFormatDouble(StringBuilder sb, double d) {
        if (Double.isNaN(d)) {
            sb.append("nan");
        } else if (d == Double.POSITIVE_INFINITY) {
            sb.append("inf");
        } else if (d == Double.NEGATIVE_INFINITY) {
            sb.append("-inf");
        } else {
            sb.append(Double.toString(d));
        }
    }

    // ==================== TextFormat parsing utilities ====================

    /**
     * Lightweight reader for protobuf canonical TextFormat, operating on a {@link ByteBuf} so
     * that nested sub-messages from another generated package can advance the same cursor by
     * sharing the underlying buffer (mirrors the {@link JsonReader} pattern).
     *
     * <p>Handles the syntax produced by protobuf-java's {@code TextFormat.printer()} plus a few
     * common variants (angle-bracket sub-messages, single-quoted strings, '#' comments, optional
     * commas/semicolons between fields, '[..]' array syntax for repeated values).
     */
    static final class TextFormatReader {
        private final ByteBuf buf;

        TextFormatReader(ByteBuf buf) {
            this.buf = buf;
        }

        ByteBuf buf() { return buf; }

        private int readable() { return buf.readableBytes(); }
        private byte at(int offset) { return buf.getByte(buf.readerIndex() + offset); }

        void skipWhitespaceAndComments() {
            while (readable() > 0) {
                byte b = at(0);
                if (b == ' ' || b == '\t' || b == '\n' || b == '\r') {
                    buf.skipBytes(1);
                } else if (b == '#') {
                    while (readable() > 0 && at(0) != '\n') {
                        buf.skipBytes(1);
                    }
                } else {
                    break;
                }
            }
        }

        boolean isEof() {
            skipWhitespaceAndComments();
            return readable() <= 0;
        }

        /** True at EOF or when the next non-whitespace byte is '}' or '>'. */
        boolean atFieldsEnd() {
            skipWhitespaceAndComments();
            if (readable() <= 0) return true;
            byte b = at(0);
            return b == '}' || b == '>';
        }

        /** True when the next non-whitespace byte is '{' or '<' (start of a sub-message body). */
        boolean atMessageStart() {
            skipWhitespaceAndComments();
            if (readable() <= 0) return false;
            byte b = at(0);
            return b == '{' || b == '<';
        }

        boolean tryConsume(char c) {
            skipWhitespaceAndComments();
            if (readable() > 0 && at(0) == (byte) c) {
                buf.skipBytes(1);
                return true;
            }
            return false;
        }

        void expect(char c) {
            skipWhitespaceAndComments();
            if (readable() <= 0 || at(0) != (byte) c) {
                throw new IllegalArgumentException("Expected '" + c + "' at position " + buf.readerIndex()
                        + " but found " + (readable() > 0 ? "'" + (char) at(0) + "'" : "end of input"));
            }
            buf.skipBytes(1);
        }

        /** Read an identifier ([a-zA-Z_][a-zA-Z0-9_]*). Used for field names and enum values. */
        String readIdentifier() {
            skipWhitespaceAndComments();
            int start = buf.readerIndex();
            if (readable() > 0) {
                byte b = at(0);
                if (b == '_' || (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')) {
                    buf.skipBytes(1);
                    while (readable() > 0) {
                        b = at(0);
                        if (b == '_' || (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')
                                || (b >= '0' && b <= '9')) {
                            buf.skipBytes(1);
                        } else {
                            break;
                        }
                    }
                }
            }
            int end = buf.readerIndex();
            if (end == start) {
                throw new IllegalArgumentException("Expected identifier at position " + start);
            }
            byte[] tmp = new byte[end - start];
            buf.getBytes(start, tmp);
            return new String(tmp, java.nio.charset.StandardCharsets.US_ASCII);
        }

        /**
         * Consume the separator after a field name. Either ':' (always valid) or, when the
         * next significant character is '{' or '<' (sub-message), the colon may be omitted.
         */
        void consumeFieldSeparator() {
            skipWhitespaceAndComments();
            if (readable() > 0) {
                byte b = at(0);
                if (b == '{' || b == '<') {
                    return; // colon optional before sub-message
                }
            }
            expect(':');
        }

        /** Consume the message opener ('{' or '<') and return the matching closer character. */
        char consumeMessageOpen() {
            skipWhitespaceAndComments();
            if (readable() > 0) {
                byte b = at(0);
                if (b == '{') { buf.skipBytes(1); return '}'; }
                if (b == '<') { buf.skipBytes(1); return '>'; }
            }
            throw new IllegalArgumentException("Expected '{' or '<' at position " + buf.readerIndex());
        }

        /** Optional separator between fields/elements ('','' or '';''). */
        void skipOptionalSeparator() {
            skipWhitespaceAndComments();
            if (readable() > 0) {
                byte b = at(0);
                if (b == ',' || b == ';') buf.skipBytes(1);
            }
        }

        boolean atArrayStart() {
            skipWhitespaceAndComments();
            return readable() > 0 && at(0) == '[';
        }

        /** Read raw bytes of a quoted string (handles concatenation of adjacent strings). */
        byte[] readBytes() {
            skipWhitespaceAndComments();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            readQuotedBytesInto(out);
            // Concatenated string literals: "abc" "def" → "abcdef"
            while (true) {
                int save = buf.readerIndex();
                skipWhitespaceAndComments();
                if (readable() > 0 && (at(0) == '"' || at(0) == '\'')) {
                    readQuotedBytesInto(out);
                } else {
                    buf.readerIndex(save);
                    break;
                }
            }
            return out.toByteArray();
        }

        String readString() {
            return new String(readBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        private void readQuotedBytesInto(java.io.ByteArrayOutputStream out) {
            if (readable() <= 0) {
                throw new IllegalArgumentException("Expected string at position " + buf.readerIndex());
            }
            byte quote = at(0);
            if (quote != '"' && quote != '\'') {
                throw new IllegalArgumentException("Expected '\"' or '\\'' at position " + buf.readerIndex());
            }
            buf.skipBytes(1);
            while (readable() > 0) {
                byte b = buf.readByte();
                if (b == quote) {
                    return;
                }
                if (b == '\\') {
                    if (readable() <= 0) {
                        throw new IllegalArgumentException("Unterminated escape");
                    }
                    byte esc = buf.readByte();
                    switch (esc) {
                        case 'a': out.write(0x07); break;
                        case 'b': out.write(0x08); break;
                        case 'f': out.write(0x0C); break;
                        case 'n': out.write(0x0A); break;
                        case 'r': out.write(0x0D); break;
                        case 't': out.write(0x09); break;
                        case 'v': out.write(0x0B); break;
                        case '\\': out.write('\\'); break;
                        case '\'': out.write('\''); break;
                        case '"': out.write('"'); break;
                        case '?': out.write('?'); break;
                        case 'x':
                        case 'X': {
                            int val = 0;
                            int n = 0;
                            while (readable() > 0 && n < 2 && isHexDigit(at(0))) {
                                val = (val << 4) | hexDigitValue(at(0));
                                buf.skipBytes(1);
                                n++;
                            }
                            if (n == 0) {
                                throw new IllegalArgumentException("Invalid \\x escape");
                            }
                            out.write(val);
                            break;
                        }
                        case 'u': {
                            int val = readFixedHex(4);
                            byte[] enc = new String(Character.toChars(val))
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            out.write(enc, 0, enc.length);
                            break;
                        }
                        case 'U': {
                            int val = readFixedHex(8);
                            byte[] enc = new String(Character.toChars(val))
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            out.write(enc, 0, enc.length);
                            break;
                        }
                        default:
                            if (esc >= '0' && esc <= '7') {
                                int val = esc - '0';
                                int n = 1;
                                while (readable() > 0 && n < 3 && at(0) >= '0' && at(0) <= '7') {
                                    val = (val << 3) | (at(0) - '0');
                                    buf.skipBytes(1);
                                    n++;
                                }
                                out.write(val);
                            } else {
                                throw new IllegalArgumentException("Invalid escape '\\" + (char) esc + "'");
                            }
                    }
                } else {
                    out.write(b & 0xFF);
                }
            }
            throw new IllegalArgumentException("Unterminated string literal");
        }

        private int readFixedHex(int n) {
            int val = 0;
            for (int i = 0; i < n; i++) {
                if (readable() <= 0 || !isHexDigit(at(0))) {
                    throw new IllegalArgumentException("Expected " + n + " hex digits");
                }
                val = (val << 4) | hexDigitValue(at(0));
                buf.skipBytes(1);
            }
            return val;
        }

        private static boolean isHexDigit(byte b) {
            return (b >= '0' && b <= '9') || (b >= 'a' && b <= 'f') || (b >= 'A' && b <= 'F');
        }

        private static int hexDigitValue(byte b) {
            if (b >= '0' && b <= '9') return b - '0';
            if (b >= 'a' && b <= 'f') return b - 'a' + 10;
            return b - 'A' + 10;
        }

        /** Read a raw numeric token (sign, digits, optional 0x prefix, decimal, exponent, suffix). */
        String readNumberToken() {
            skipWhitespaceAndComments();
            int start = buf.readerIndex();
            if (readable() > 0 && (at(0) == '-' || at(0) == '+')) {
                buf.skipBytes(1);
            }
            while (readable() > 0) {
                byte b = at(0);
                if ((b >= '0' && b <= '9') || b == '.' || b == 'e' || b == 'E'
                        || b == 'x' || b == 'X' || b == '-' || b == '+'
                        || (b >= 'a' && b <= 'f') || (b >= 'A' && b <= 'F')) {
                    buf.skipBytes(1);
                } else {
                    break;
                }
            }
            // Optional integer suffix (u, l, U, L)
            while (readable() > 0) {
                byte b = at(0);
                if (b == 'u' || b == 'U' || b == 'l' || b == 'L') {
                    buf.skipBytes(1);
                } else {
                    break;
                }
            }
            int end = buf.readerIndex();
            if (end == start) {
                throw new IllegalArgumentException("Expected number at position " + start);
            }
            byte[] tmp = new byte[end - start];
            buf.getBytes(start, tmp);
            return new String(tmp, java.nio.charset.StandardCharsets.US_ASCII);
        }

        int readInt() {
            return (int) parseLongToken(readNumberToken());
        }

        long readLong() {
            return parseLongToken(readNumberToken());
        }

        private static long parseLongToken(String tok) {
            int end = tok.length();
            while (end > 0) {
                char c = tok.charAt(end - 1);
                if (c == 'u' || c == 'U' || c == 'l' || c == 'L') end--; else break;
            }
            tok = tok.substring(0, end);
            boolean negative = false;
            int start = 0;
            if (tok.startsWith("-")) { negative = true; start = 1; }
            else if (tok.startsWith("+")) { start = 1; }
            String body = tok.substring(start);
            long val;
            if (body.startsWith("0x") || body.startsWith("0X")) {
                val = Long.parseUnsignedLong(body.substring(2), 16);
            } else if (body.length() > 1 && body.startsWith("0") && allOctalDigits(body)) {
                val = Long.parseUnsignedLong(body, 8);
            } else {
                val = Long.parseUnsignedLong(body, 10);
            }
            return negative ? -val : val;
        }

        private static boolean allOctalDigits(String s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < '0' || c > '7') return false;
            }
            return true;
        }

        float readFloat() {
            return (float) readDouble();
        }

        double readDouble() {
            skipWhitespaceAndComments();
            int save = buf.readerIndex();
            boolean negative = false;
            if (readable() > 0 && (at(0) == '-' || at(0) == '+')) {
                negative = at(0) == '-';
                buf.skipBytes(1);
            }
            if (readable() > 0) {
                byte b = at(0);
                if (b == 'n' || b == 'N') {
                    if (matchKeyword("nan")) return Double.NaN;
                }
                if (b == 'i' || b == 'I') {
                    if (matchKeyword("infinity") || matchKeyword("inf")) {
                        return negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
                    }
                }
            }
            buf.readerIndex(save);
            String tok = readNumberToken();
            if (tok.endsWith("f") || tok.endsWith("F")) {
                tok = tok.substring(0, tok.length() - 1);
            }
            return Double.parseDouble(tok);
        }

        private boolean matchKeyword(String keyword) {
            if (readable() < keyword.length()) return false;
            for (int i = 0; i < keyword.length(); i++) {
                byte a = at(i);
                char b = keyword.charAt(i);
                if (Character.toLowerCase((char) a) != b) return false;
            }
            // Must not be followed by an identifier char
            if (readable() > keyword.length()) {
                byte c = at(keyword.length());
                if (c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9')) {
                    return false;
                }
            }
            buf.skipBytes(keyword.length());
            return true;
        }

        boolean readBool() {
            skipWhitespaceAndComments();
            if (matchKeyword("true") || matchKeyword("t")) return true;
            if (matchKeyword("false") || matchKeyword("f")) return false;
            String tok = readNumberToken();
            if (tok.equals("1")) return true;
            if (tok.equals("0")) return false;
            throw new IllegalArgumentException("Expected boolean but found '" + tok + "'");
        }

        /** Skip a single value (scalar, sub-message, or array). Used for unknown fields. */
        void skipValue() {
            skipWhitespaceAndComments();
            if (readable() <= 0) return;
            byte b = at(0);
            if (b == ':') {
                buf.skipBytes(1);
                skipWhitespaceAndComments();
                if (readable() <= 0) return;
                b = at(0);
            }
            if (b == '{' || b == '<') {
                char close = consumeMessageOpen();
                while (!atFieldsEnd()) {
                    readIdentifier();
                    skipWhitespaceAndComments();
                    if (readable() > 0 && (at(0) == '{' || at(0) == '<')) {
                        skipValue();
                    } else {
                        if (readable() > 0 && at(0) == ':') buf.skipBytes(1);
                        skipValue();
                    }
                    skipOptionalSeparator();
                }
                expect(close);
            } else if (b == '[') {
                buf.skipBytes(1);
                if (!tryConsume(']')) {
                    do {
                        skipValue();
                    } while (tryConsume(','));
                    expect(']');
                }
            } else if (b == '"' || b == '\'') {
                readBytes();
            } else if (b == '-' || b == '+' || (b >= '0' && b <= '9')) {
                readNumberToken();
            } else if (b == '_' || (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')) {
                readIdentifier();
            } else {
                throw new IllegalArgumentException("Unexpected character '" + (char) b + "' at position " + buf.readerIndex());
            }
        }
    }
}
