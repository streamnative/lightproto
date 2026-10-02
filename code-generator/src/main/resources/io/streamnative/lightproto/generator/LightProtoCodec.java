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

import io.netty.buffer.AbstractByteBuf;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.util.internal.PlatformDependent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.nio.charset.StandardCharsets;

class LightProtoCodec {

    private static final Class<?> WRAPPED_COMPOSITE_BYTEBUF_CLASS = loadWrappedCompositeByteBufClass();
    private static final boolean HAS_UNSAFE;
    private static final long STRING_VALUE_OFFSET;
    static final long BYTE_ARRAY_BASE_OFFSET;
    // True when JDK compact strings are enabled (default since JDK 9).
    // When disabled via -XX:-CompactStrings, String's internal byte[] uses UTF-16
    // and we must not use the Unsafe string fast paths.
    private static final boolean COMPACT_STRINGS;

    // MethodHandles for Unsafe operations, resolved via reflection to avoid
    // referencing sun.misc.Unsafe as a type (which triggers javac warnings).
    // HotSpot inlines invokeExact on static final MethodHandles.
    private static final MethodHandle MH_GET_OBJECT;
    private static final MethodHandle MH_PUT_OBJECT;
    private static final MethodHandle MH_COPY_MEMORY;
    private static final MethodHandle MH_ALLOCATE_INSTANCE;

    static {
        boolean hasUnsafe = false;
        long offset = -1;
        long arrayBase = -1;
        boolean compactStrings = false;
        MethodHandle mhGetObject = null;
        MethodHandle mhPutObject = null;
        MethodHandle mhCopyMemory = null;
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
            mhGetObject = lookup.unreflect(getObjectMethod).bindTo(unsafe);
            mhPutObject = lookup.unreflect(
                    unsafeClass.getMethod("putObject", Object.class, long.class, Object.class)).bindTo(unsafe);
            mhCopyMemory = lookup.unreflect(
                    unsafeClass.getMethod("copyMemory", Object.class, long.class, Object.class, long.class, long.class))
                    .bindTo(unsafe);
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
        MH_GET_OBJECT = mhGetObject;
        MH_PUT_OBJECT = mhPutObject;
        MH_COPY_MEMORY = mhCopyMemory;
        MH_ALLOCATE_INSTANCE = mhAllocateInstance;
    }

    private static Class<?> loadWrappedCompositeByteBufClass() {
        try {
            // This Netty class is package-private, so it cannot be referenced directly.
            return Class.forName("io.netty.buffer.WrappedCompositeByteBuf", false, ByteBuf.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            // Use checked reads if the wrapper hierarchy cannot be identified.
            return null;
        }
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

    static int readSignedVarInt(ByteBuf b) {
        return decodeZigZag32(readVarInt(b));
    }

    static long readSignedVarInt64(ByteBuf b) {
        return decodeZigZag64(readVarInt64(b));
    }

    static float readFloat(ByteBuf b) {
        return Float.intBitsToFloat(readFixedInt32(b));
    }

    static double readDouble(ByteBuf b) {
        return Double.longBitsToDouble(readFixedInt64(b));
    }

    static int readFixedInt32(ByteBuf b) {
        return b.readIntLE();
    }

    static long readFixedInt64(ByteBuf b) {
        return b.readLongLE();
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
        // Only 64-bit varints use the unchecked path: it skips the per-byte
        // bounds/accessibility checks (+31% on varint64-heavy messages). A
        // truncated varint64 can overrun the message limit by at most 9 bytes;
        // the generated parseFrom() detects that afterwards. The checked
        // fallback lives in its own method so this one stays small enough to
        // always inline (with the chain inline it is 303 bytecodes and C2
        // refuses it at hot call sites).
        // Composite wrappers delegate their indices to another buffer. The inherited
        // readerIndex field used by the unchecked accessor is not authoritative.
        if (buf instanceof AbstractByteBuf && WRAPPED_COMPOSITE_BYTEBUF_CLASS != null
                && !WRAPPED_COMPOSITE_BYTEBUF_CLASS.isInstance(buf)) {
            return io.netty.buffer.LightProtoByteBufAccessTemplate.readVarInt64Unchecked((AbstractByteBuf) buf);
        }
        return readVarInt64Checked(buf);
    }

    private static long readVarInt64Checked(ByteBuf buf) {
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

    // --- Raw write methods for zero-overhead serialization ---
    // Serialization writes through an int cursor into one of two sinks, and every
    // raw writer below is overloaded for both:
    //  - a plain byte[]: heap buffers in place through their backing array; small
    //    messages (and buffers without a single NIO region) composed in a reusable
    //    scratch array and transferred with one bulk writeBytes();
    //  - a direct buffer's NIO view (ByteBuf.internalNioBuffer) for messages above
    //    NIO_WRITE_MIN, written in place: no scratch array and no bulk copy.
    // Neither uses sun.misc.Unsafe in the hot loop (its memory-access methods carry
    // a per-call deprecation check since JDK 24): array stores compile to raw
    // memory accesses, and DirectByteBuffer puts to a bounds check plus a
    // jdk.internal.misc.Unsafe store.

    // Messages strictly larger than this are written through the NIO view when the
    // target is a single-region direct buffer. Below it the view's per-put cost
    // outweighs the scratch copy it saves (~+15% on a 70-byte varint-dense message,
    // versus -20% at 600 bytes and -35% from 6 KB up).
    static final int NIO_WRITE_MIN = 512;

    // Scratch arrays larger than this are not retained on the message instance, so
    // outlier messages don't pin large allocations. Direct-buffer messages above
    // NIO_WRITE_MIN never touch the scratch, so it only grows past that for
    // buffers without a single NIO region.
    static final int SCRATCH_RETAIN_MAX = 1024 * 1024;

    // clear() of a message larger than this (or of unknown size) releases the
    // data references retained by the O(1) clear design, so a reused (pooled or
    // per-connection) instance pins at most this much of the last message's
    // data. The release walk costs O(element count), which is noise for any
    // message this large; below the threshold the walk is skipped entirely.
    static final int CLEAR_RETAIN_MAX = 64 * 1024;

    // A gRPC message of at least this size that spans several transport buffers is parsed in
    // place from them, through a SegmentedByteBuf, instead of copied. A smaller one only spans
    // buffers when it straddles two HTTP/2 DATA frames (16 KiB by default), and wrapping its
    // pieces costs more than the copy saves: 1.5-3x on 100-byte to 4 KiB messages over 4 buffers,
    // where a 64 KiB value read as a byte[] parses in 0.54x the time of the copy.
    static final int SEGMENTED_PARSE_MIN = 16 * 1024;

    /** Returns current if it can hold size bytes, otherwise a larger replacement. */
    static byte[] scratchFor(byte[] current, int size) {
        if (current != null && current.length >= size) {
            return current;
        }
        if (size > SCRATCH_RETAIN_MAX) {
            // The result won't be retained, so growth amortization is pointless:
            // allocate exactly what this outlier message needs.
            return new byte[size];
        }
        // Double to amortize growth, but never past the retain cap: otherwise
        // messages just under the cap would re-allocate an unretainable array
        // on every write instead of settling on a reusable retained one.
        int cap = Math.max(size, current == null ? 64 : current.length * 2);
        return new byte[Math.min(cap, SCRATCH_RETAIN_MAX)];
    }

    static int writeRawByte(byte[] a, int i, int value) {
        a[i] = (byte) value;
        return i + 1;
    }

    static int writeRawVarInt(byte[] a, int i, int n) {
        if (n >= 0) {
            while (true) {
                if ((n & ~0x7F) == 0) {
                    a[i++] = (byte) n;
                    return i;
                }
                a[i++] = (byte) ((n & 0x7F) | 0x80);
                n >>>= 7;
            }
        } else {
            return writeRawVarInt64(a, i, n);
        }
    }

    static int writeRawVarInt64(byte[] a, int i, long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                a[i++] = (byte) value;
                return i;
            }
            a[i++] = (byte) (((int) value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    /**
     * Writes n as an unsigned 32-bit varint, at most 5 bytes, as protobuf encodes
     * uint32 and zigzag-encoded sint32 values: what computeVarUIntSize() counts.
     * writeRawVarInt() is the int32 encoding, which writes a negative n as 10 bytes.
     */
    static int writeRawVarUInt(byte[] a, int i, int n) {
        while (true) {
            if ((n & ~0x7F) == 0) {
                a[i++] = (byte) n;
                return i;
            }
            a[i++] = (byte) ((n & 0x7F) | 0x80);
            n >>>= 7;
        }
    }

    static int writeRawSignedVarInt(byte[] a, int i, int n) {
        return writeRawVarUInt(a, i, encodeZigZag32(n));
    }

    static int writeRawSignedVarInt64(byte[] a, int i, long n) {
        return writeRawVarInt64(a, i, encodeZigZag64(n));
    }

    static int writeRawLittleEndian32(byte[] a, int i, int value) {
        a[i] = (byte) value;
        a[i + 1] = (byte) (value >>> 8);
        a[i + 2] = (byte) (value >>> 16);
        a[i + 3] = (byte) (value >>> 24);
        return i + 4;
    }

    static int writeRawLittleEndian64(byte[] a, int i, long value) {
        writeRawLittleEndian32(a, i, (int) value);
        writeRawLittleEndian32(a, i + 4, (int) (value >>> 32));
        return i + 8;
    }

    static int writeRawFloat(byte[] a, int i, float n) {
        return writeRawLittleEndian32(a, i, Float.floatToRawIntBits(n));
    }

    static int writeRawDouble(byte[] a, int i, double n) {
        return writeRawLittleEndian64(a, i, Double.doubleToRawLongBits(n));
    }

    /**
     * Write a string's UTF-8 encoding (bytesCount bytes, as precomputed at set time)
     * at index i. ASCII strings are copied straight from the String's internal
     * byte[]; other strings go through the JDK encoder. Returns the index after.
     */
    static int writeRawString(byte[] a, int i, String s, int bytesCount) {
        if (s.length() == bytesCount) {
            // ASCII fast path: copy the String's internal LATIN1 byte[] directly
            if (HAS_UNSAFE && COMPACT_STRINGS) {
                try {
                    Object _v = (Object) MH_GET_OBJECT.invokeExact((Object) s, STRING_VALUE_OFFSET);
                    System.arraycopy((byte[]) _v, 0, a, i, bytesCount);
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            } else {
                System.arraycopy(s.getBytes(StandardCharsets.ISO_8859_1), 0, a, i, bytesCount);
            }
        } else {
            System.arraycopy(s.getBytes(StandardCharsets.UTF_8), 0, a, i, bytesCount);
        }
        return i + bytesCount;
    }

    // NIO-view overloads of the writers above. Absolute puts ignore the view's
    // position; the view's byte order is honored explicitly for fixed-width values.

    static int writeRawByte(java.nio.ByteBuffer nb, int i, int value) {
        nb.put(i, (byte) value);
        return i + 1;
    }

    static int writeRawVarInt(java.nio.ByteBuffer nb, int i, int n) {
        if (n >= 0) {
            while (true) {
                if ((n & ~0x7F) == 0) {
                    nb.put(i++, (byte) n);
                    return i;
                }
                nb.put(i++, (byte) ((n & 0x7F) | 0x80));
                n >>>= 7;
            }
        } else {
            return writeRawVarInt64(nb, i, n);
        }
    }

    static int writeRawVarInt64(java.nio.ByteBuffer nb, int i, long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                nb.put(i++, (byte) value);
                return i;
            }
            nb.put(i++, (byte) (((int) value & 0x7F) | 0x80));
            value >>>= 7;
        }
    }

    static int writeRawVarUInt(java.nio.ByteBuffer nb, int i, int n) {
        while (true) {
            if ((n & ~0x7F) == 0) {
                nb.put(i++, (byte) n);
                return i;
            }
            nb.put(i++, (byte) ((n & 0x7F) | 0x80));
            n >>>= 7;
        }
    }

    static int writeRawSignedVarInt(java.nio.ByteBuffer nb, int i, int n) {
        return writeRawVarUInt(nb, i, encodeZigZag32(n));
    }

    static int writeRawSignedVarInt64(java.nio.ByteBuffer nb, int i, long n) {
        return writeRawVarInt64(nb, i, encodeZigZag64(n));
    }

    static int writeRawLittleEndian32(java.nio.ByteBuffer nb, int i, int value) {
        nb.putInt(i, nb.order() == java.nio.ByteOrder.LITTLE_ENDIAN ? value : Integer.reverseBytes(value));
        return i + 4;
    }

    static int writeRawLittleEndian64(java.nio.ByteBuffer nb, int i, long value) {
        nb.putLong(i, nb.order() == java.nio.ByteOrder.LITTLE_ENDIAN ? value : Long.reverseBytes(value));
        return i + 8;
    }

    static int writeRawFloat(java.nio.ByteBuffer nb, int i, float n) {
        return writeRawLittleEndian32(nb, i, Float.floatToRawIntBits(n));
    }

    static int writeRawDouble(java.nio.ByteBuffer nb, int i, double n) {
        return writeRawLittleEndian64(nb, i, Double.doubleToRawLongBits(n));
    }

    static int writeRawString(java.nio.ByteBuffer nb, int i, String s, int bytesCount) {
        if (s.length() == bytesCount) {
            // ASCII fast path: bulk-put the String's internal LATIN1 byte[] directly
            if (HAS_UNSAFE && COMPACT_STRINGS) {
                try {
                    Object _v = (Object) MH_GET_OBJECT.invokeExact((Object) s, STRING_VALUE_OFFSET);
                    nb.put(i, (byte[]) _v, 0, bytesCount);
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            } else {
                nb.put(i, s.getBytes(StandardCharsets.ISO_8859_1), 0, bytesCount);
            }
        } else {
            nb.put(i, s.getBytes(StandardCharsets.UTF_8), 0, bytesCount);
        }
        return i + bytesCount;
    }

    /**
     * Copies len bytes of src starting at srcIdx into the view at absolute index i
     * (through a temporary position/limit window, restored afterwards); returns i + len.
     */
    static int copyRawBytes(ByteBuf src, int srcIdx, java.nio.ByteBuffer nb, int i, int len) {
        int lim = nb.limit();
        nb.limit(i + len).position(i);
        src.getBytes(srcIdx, nb);
        nb.limit(lim);
        return i + len;
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
                    // Not Unsafe.copyMemory: since JDK 24 it pays a check on every call
                    System.arraycopy(b.array(), b.arrayOffset() + index, value, 0, len);
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

    // Plain unfenced 8-byte loads. The former sun.misc.Unsafe.getLong version
    // executed a JDK 24+ acquire-load per call (JEP 498), whose cost grows with
    // the store traffic of whatever ran just before — measured as a large part
    // of string-materialization time right after a parse.
    private static final java.lang.invoke.VarHandle LONG_ARRAY_VIEW =
            MethodHandles.byteArrayViewVarHandle(long[].class, java.nio.ByteOrder.nativeOrder());

    private static boolean _isAscii(byte[] bytes, int len) {
        // Check 8 bytes at a time using long reads — data is in L1 cache from the copy
        int i = 0;
        for (; i + 7 < len; i += 8) {
            if (((long) LONG_ARRAY_VIEW.get(bytes, i) & 0x8080808080808080L) != 0) {
                return false;
            }
        }
        // Check remaining bytes
        for (; i < len; i++) {
            if (bytes[i] < 0) return false;
        }
        return true;
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
        int _writeTo(byte[] a, int i);
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

    /**
     * Whether {@code b} wraps a whole array that is exactly the {@code len}-byte value at its
     * reader index, so that the array can be returned in place of a copy. Only plain
     * {@link UnpooledHeapByteBuf} instances qualify, as created by
     * {@code Unpooled.wrappedBuffer(byte[])}: pooled buffers share and recycle their arrays,
     * and subclasses can recycle theirs by overriding {@code freeArray()}.
     */
    static boolean isWholeArray(ByteBuf b, int len) {
        return b != null && b.getClass() == UnpooledHeapByteBuf.class
                && b.readerIndex() == 0 && b.array().length == len;
    }

    /**
     * A read-only ByteBuf over a sequence of ByteBuffers, to parse a message received in several
     * network buffers without copying it. Unlike a CompositeByteBuf, a read within the segment of the
     * previous read takes one range check and one memory read, without a component buffer's own
     * bounds and reference count checks. The ByteBuffers stay owned by the caller: they must remain
     * valid and unchanged while this buffer is read. Its reference count is fixed, which also spares
     * each read the accessibility check of a reference-counted buffer.
     */
    static final class SegmentedByteBuf extends AbstractByteBuf {
        private final ByteBuffer[] segments;
        // offsets[i] is the index of the first byte of segments[i], offsets[segments.length] the capacity
        private final int[] offsets;
        // The memory address of each direct segment, or 0 to read it through its ByteBuffer
        private final long[] addresses;
        private ByteBuffer current;
        private long currentAddress;
        private int currentStart;
        private int currentEnd;

        /** Wraps the remaining bytes of the first {@code count} buffers, taking over their positions. */
        SegmentedByteBuf(ByteBuffer[] buffers, int count) {
            super(0);
            segments = new ByteBuffer[count];
            offsets = new int[count + 1];
            addresses = new long[count];
            boolean unsafe = PlatformDependent.hasUnsafe();
            for (int i = 0; i < count; i++) {
                ByteBuffer b = buffers[i];
                // Each segment is read from index 0 to its limit
                segments[i] = b.position() == 0 ? b : b.slice();
                offsets[i + 1] = offsets[i] + b.remaining();
                addresses[i] = unsafe && b.isDirect() ? PlatformDependent.directBufferAddress(segments[i]) : 0;
            }
            maxCapacity(offsets[count]);
            setIndex(0, offsets[count]);
            if (offsets[count] > 0) {
                select(0);
            }
        }

        /** Makes the segment holding {@code index} the current one. */
        private void select(int index) {
            if (index < 0 || index >= capacity()) {
                // Only the unchecked varint64 reads go past the end, on truncated input
                throw new IndexOutOfBoundsException("index: " + index + " (expected: range(0, " + capacity() + "))");
            }
            // The last segment starting at or before index, which skips empty segments
            int low = 0;
            int high = segments.length - 1;
            while (low < high) {
                int mid = (low + high + 1) >>> 1;
                if (offsets[mid] <= index) {
                    low = mid;
                } else {
                    high = mid - 1;
                }
            }
            current = segments[low];
            currentAddress = addresses[low];
            currentStart = offsets[low];
            currentEnd = offsets[low + 1];
        }

        /** Returns a view of the bytes from {@code index} to the end of their segment, at most {@code length}. */
        private ByteBuffer view(int index, int length) {
            if (index < currentStart || index >= currentEnd) {
                select(index);
            }
            ByteBuffer view = current.duplicate();
            int position = index - currentStart;
            view.limit(position + Math.min(length, currentEnd - index)).position(position);
            return view;
        }

        @Override
        protected byte _getByte(int index) {
            if (index < currentStart || index >= currentEnd) {
                select(index);
            }
            int i = index - currentStart;
            return currentAddress != 0 ? PlatformDependent.getByte(currentAddress + i) : current.get(i);
        }

        @Override
        protected short _getShort(int index) {
            return (short) (_getByte(index) << 8 | _getByte(index + 1) & 0xff);
        }

        @Override
        protected short _getShortLE(int index) {
            return (short) (_getByte(index) & 0xff | _getByte(index + 1) << 8);
        }

        @Override
        protected int _getUnsignedMedium(int index) {
            return (_getByte(index) & 0xff) << 16 | (_getShort(index + 1) & 0xffff);
        }

        @Override
        protected int _getUnsignedMediumLE(int index) {
            return _getShortLE(index) & 0xffff | (_getByte(index + 2) & 0xff) << 16;
        }

        @Override
        protected int _getInt(int index) {
            return _getShort(index) << 16 | _getShort(index + 2) & 0xffff;
        }

        @Override
        protected int _getIntLE(int index) {
            return _getShortLE(index) & 0xffff | _getShortLE(index + 2) << 16;
        }

        @Override
        protected long _getLong(int index) {
            return (long) _getInt(index) << 32 | _getInt(index + 4) & 0xffffffffL;
        }

        @Override
        protected long _getLongLE(int index) {
            return _getIntLE(index) & 0xffffffffL | (long) _getIntLE(index + 4) << 32;
        }

        @Override
        public ByteBuf getBytes(int index, byte[] dst, int dstIndex, int length) {
            checkDstIndex(index, length, dstIndex, dst.length);
            while (length > 0) {
                if (index < currentStart || index >= currentEnd) {
                    select(index);
                }
                int n = Math.min(length, currentEnd - index);
                if (currentAddress != 0) {
                    PlatformDependent.copyMemory(currentAddress + index - currentStart, dst, dstIndex, n);
                } else {
                    current.position(index - currentStart);
                    current.get(dst, dstIndex, n);
                }
                index += n;
                dstIndex += n;
                length -= n;
            }
            return this;
        }

        @Override
        public ByteBuf getBytes(int index, ByteBuf dst, int dstIndex, int length) {
            checkDstIndex(index, length, dstIndex, dst.capacity());
            while (length > 0) {
                ByteBuffer view = view(index, length);
                int n = view.remaining();
                dst.setBytes(dstIndex, view);
                index += n;
                dstIndex += n;
                length -= n;
            }
            return this;
        }

        @Override
        public ByteBuf getBytes(int index, ByteBuffer dst) {
            int length = dst.remaining();
            checkIndex(index, length);
            while (length > 0) {
                ByteBuffer view = view(index, length);
                int n = view.remaining();
                dst.put(view);
                index += n;
                length -= n;
            }
            return this;
        }

        @Override
        public ByteBuf getBytes(int index, OutputStream out, int length) throws IOException {
            byte[] bytes = new byte[length];
            getBytes(index, bytes);
            out.write(bytes);
            return this;
        }

        @Override
        public int getBytes(int index, GatheringByteChannel out, int length) throws IOException {
            return (int) Math.min(out.write(nioBuffers(index, length)), Integer.MAX_VALUE);
        }

        @Override
        public int getBytes(int index, FileChannel out, long position, int length) throws IOException {
            int written = 0;
            for (ByteBuffer view : nioBuffers(index, length)) {
                written += out.write(view, position + written);
            }
            return written;
        }

        @Override
        public ByteBuf copy(int index, int length) {
            checkIndex(index, length);
            return alloc().heapBuffer(length).writeBytes(this, index, length);
        }

        @Override
        public int nioBufferCount() {
            return segments.length;
        }

        @Override
        public ByteBuffer nioBuffer(int index, int length) {
            ByteBuffer[] views = nioBuffers(index, length);
            if (views.length == 1) {
                return views[0];
            }
            ByteBuffer merged = ByteBuffer.allocate(length);
            for (ByteBuffer view : views) {
                merged.put(view);
            }
            merged.flip();
            return merged;
        }

        @Override
        public ByteBuffer internalNioBuffer(int index, int length) {
            ByteBuffer[] views = nioBuffers(index, length);
            if (views.length != 1) {
                throw new UnsupportedOperationException();
            }
            return views[0];
        }

        @Override
        public ByteBuffer[] nioBuffers(int index, int length) {
            checkIndex(index, length);
            if (length == 0) {
                return new ByteBuffer[] {ByteBuffer.allocate(0)};
            }
            java.util.List<ByteBuffer> views = new java.util.ArrayList<>();
            while (length > 0) {
                ByteBuffer view = view(index, length);
                views.add(view.slice());
                index += view.remaining();
                length -= view.remaining();
            }
            return views.toArray(new ByteBuffer[0]);
        }

        @Override
        public int capacity() {
            return offsets[segments.length];
        }

        @Override
        public ByteBuf capacity(int newCapacity) {
            throw new ReadOnlyBufferException();
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public boolean isDirect() {
            for (ByteBuffer segment : segments) {
                if (!segment.isDirect()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ByteBufAllocator alloc() {
            return UnpooledByteBufAllocator.DEFAULT;
        }

        @Override
        @SuppressWarnings("deprecation")
        public ByteOrder order() {
            return ByteOrder.BIG_ENDIAN;
        }

        @Override
        public ByteBuf unwrap() {
            return null;
        }

        @Override
        public boolean hasArray() {
            return false;
        }

        @Override
        public byte[] array() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int arrayOffset() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasMemoryAddress() {
            return false;
        }

        @Override
        public long memoryAddress() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int refCnt() {
            return 1;
        }

        @Override
        public ByteBuf retain() {
            return this;
        }

        @Override
        public ByteBuf retain(int increment) {
            return this;
        }

        @Override
        public ByteBuf touch() {
            return this;
        }

        @Override
        public ByteBuf touch(Object hint) {
            return this;
        }

        @Override
        public boolean release() {
            return false;
        }

        @Override
        public boolean release(int decrement) {
            return false;
        }

        @Override
        protected void _setByte(int index, int value) {
            throw new ReadOnlyBufferException();
        }

        @Override
        protected void _setShort(int index, int value) {
            throw new ReadOnlyBufferException();
        }

        @Override
        protected void _setShortLE(int index, int value) {
            throw new ReadOnlyBufferException();
        }

        @Override
        protected void _setMedium(int index, int value) {
            throw new ReadOnlyBufferException();
        }

        @Override
        protected void _setMediumLE(int index, int value) {
            throw new ReadOnlyBufferException();
        }

        @Override
        protected void _setInt(int index, int value) {
            throw new ReadOnlyBufferException();
        }

        @Override
        protected void _setIntLE(int index, int value) {
            throw new ReadOnlyBufferException();
        }

        @Override
        protected void _setLong(int index, long value) {
            throw new ReadOnlyBufferException();
        }

        @Override
        protected void _setLongLE(int index, long value) {
            throw new ReadOnlyBufferException();
        }

        @Override
        public ByteBuf setBytes(int index, ByteBuf src, int srcIndex, int length) {
            throw new ReadOnlyBufferException();
        }

        @Override
        public ByteBuf setBytes(int index, byte[] src, int srcIndex, int length) {
            throw new ReadOnlyBufferException();
        }

        @Override
        public ByteBuf setBytes(int index, ByteBuffer src) {
            throw new ReadOnlyBufferException();
        }

        @Override
        public int setBytes(int index, InputStream in, int length) {
            throw new ReadOnlyBufferException();
        }

        @Override
        public int setBytes(int index, ScatteringByteChannel in, int length) {
            throw new ReadOnlyBufferException();
        }

        @Override
        public int setBytes(int index, FileChannel in, long position, int length) {
            throw new ReadOnlyBufferException();
        }
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
        // Characters that need no escape are written in runs, one UTF-8 encode per
        // run. A run never splits a surrogate pair: only ASCII characters are escaped.
        int start = 0;
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c != '"' && c != '\\') {
                continue;
            }
            if (i > start) {
                ByteBufUtil.writeUtf8(b, s, start, i);
            }
            start = i + 1;
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
                    b.writeByte('\\');
                    b.writeByte('u');
                    b.writeByte(HEX_CHARS[(c >> 12) & 0xF]);
                    b.writeByte(HEX_CHARS[(c >> 8) & 0xF]);
                    b.writeByte(HEX_CHARS[(c >> 4) & 0xF]);
                    b.writeByte(HEX_CHARS[c & 0xF]);
            }
        }
        if (start < len) {
            ByteBufUtil.writeUtf8(b, s, start, len);
        }
        b.writeByte('"');
    }

    static void writeJsonBase64(ByteBuf b, ByteBuf data, int offset, int len) {
        byte[] raw = new byte[len];
        data.getBytes(offset, raw);
        b.writeByte('"');
        b.writeBytes(java.util.Base64.getEncoder().encode(raw));
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
