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

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.TextFormat;
import com.google.protobuf.UnknownFieldSet;
import com.google.protobuf.WireFormat;
import com.google.protobuf.util.JsonFormat;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Enums of proto3 files are open: a field keeps a number its enum doesn't define (like that of a
 * value added by a newer version of the enum), returns {@code UNRECOGNIZED} for it from the
 * getter and the number from {@code get<Field>Value()}, and writes the number back, as in
 * protobuf-java. Enum fields of proto2 files stay closed.
 */
public class OpenEnumTest {

    /**
     * Numbers OpenEnum doesn't define in every kind of field, with some it does. The map keys are
     * in order, as protobuf's TextFormat printer writes them.
     */
    private static OpenEnumsProtos.OpenEnumMessage pbWithUnknownValues() {
        return OpenEnumsProtos.OpenEnumMessage.newBuilder()
                .setImplicitEnumValue(7)
                .setOptionalEnumValue(8)
                .setOneofEnumValue(9)
                .addPackedEnumsValue(1)
                .addPackedEnumsValue(10)
                .addPackedEnumsValue(-5)
                .addPackedEnumsValue(2)
                .addUnpackedEnumsValue(11)
                .addUnpackedEnumsValue(-1)
                .addUnpackedEnumsValue(0)
                .putEnumMapValue("known", 1)
                .putEnumMapValue("negative", -6)
                .putEnumMapValue("unknown", 12)
                .setNestedEnumValue(13)
                .build();
    }

    private static OpenEnumMessage parse(byte[] wire) {
        OpenEnumMessage m = new OpenEnumMessage();
        m.parseFrom(wire);
        return m;
    }

    private static List<OpenEnum> packedEnums(OpenEnumMessage m) {
        List<OpenEnum> l = new ArrayList<>();
        for (int i = 0; i < m.getPackedEnumsCount(); i++) {
            l.add(m.getPackedEnumAt(i));
        }
        return l;
    }

    private static List<Integer> packedEnumValues(OpenEnumMessage m) {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < m.getPackedEnumsCount(); i++) {
            l.add(m.getPackedEnumValueAt(i));
        }
        return l;
    }

    private static List<OpenEnum> unpackedEnums(OpenEnumMessage m) {
        List<OpenEnum> l = new ArrayList<>();
        for (int i = 0; i < m.getUnpackedEnumsCount(); i++) {
            l.add(m.getUnpackedEnumAt(i));
        }
        return l;
    }

    private static List<Integer> unpackedEnumValues(OpenEnumMessage m) {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < m.getUnpackedEnumsCount(); i++) {
            l.add(m.getUnpackedEnumValueAt(i));
        }
        return l;
    }

    @Test
    public void testUnrecognizedConstant() {
        // Appended after the values, so their ordinals don't change
        OpenEnum[] values = OpenEnum.values();
        assertEquals(OpenEnum.UNRECOGNIZED, values[values.length - 1]);
        assertEquals(OpenEnumMessage.NestedEnum.UNRECOGNIZED, OpenEnumMessage.NestedEnum.valueOf("UNRECOGNIZED"));

        // It has no number, as in protobuf-java
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, OpenEnum.UNRECOGNIZED::getValue);
        assertEquals("Can't get the number of an unknown enum value.", e.getMessage());

        // valueOf(int) still returns null for a number the enum doesn't define, and -1 is a value
        assertNull(OpenEnum.valueOf(7));
        assertEquals(OpenEnum.OPEN_MINUS_ONE, OpenEnum.valueOf(-1));
        assertEquals(-1, OpenEnum.OPEN_MINUS_ONE.getValue());
    }

    @Test
    public void testValueOf() throws Exception {
        // Open enums look their numbers up in an array, unless they are too sparse
        assertNotNull(GappedOpenEnum.class.getDeclaredField("_BY_NUMBER"));
        assertThrows(NoSuchFieldException.class, () -> SparseOpenEnum.class.getDeclaredField("_BY_NUMBER"));

        assertValueOf(OpenEnum.values(), OpenEnum::valueOf, OpenEnum::getValue);
        assertValueOf(OpenEnumMessage.NestedEnum.values(), OpenEnumMessage.NestedEnum::valueOf,
                OpenEnumMessage.NestedEnum::getValue);
        assertValueOf(GappedOpenEnum.values(), GappedOpenEnum::valueOf, GappedOpenEnum::getValue);
        assertValueOf(SparseOpenEnum.values(), SparseOpenEnum::valueOf, SparseOpenEnum::getValue);
    }

    /** Checks valueOf() around and between the numbers of the enum, and at the ends of the int range. */
    private static <E extends Enum<E>> void assertValueOf(E[] constants, IntFunction<E> valueOf,
                                                          ToIntFunction<E> number) {
        Map<Integer, E> expected = new HashMap<>();
        for (E e : constants) {
            if (!e.name().equals("UNRECOGNIZED")) {
                expected.put(number.applyAsInt(e), e);
            }
        }
        int min = Collections.min(expected.keySet());
        int max = Collections.max(expected.keySet());
        for (int n = min - 3; n <= max + 3; n++) {
            assertEquals(expected.get(n), valueOf.apply(n), "valueOf(" + n + ")");
        }
        for (int n : new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE + 1, Integer.MAX_VALUE}) {
            assertNull(valueOf.apply(n), "valueOf(" + n + ")");
        }
    }

    @Test
    public void testUnknownValuesRoundTrip() throws Exception {
        OpenEnumsProtos.OpenEnumMessage pb = pbWithUnknownValues();
        byte[] wire = pb.toByteArray();
        OpenEnumMessage lp = parse(wire);

        assertEquals(OpenEnum.UNRECOGNIZED, lp.getImplicitEnum());
        assertEquals(7, lp.getImplicitEnumValue());

        assertTrue(lp.hasOptionalEnum());
        assertEquals(OpenEnum.UNRECOGNIZED, lp.getOptionalEnum());
        assertEquals(8, lp.getOptionalEnumValue());

        assertEquals(OpenEnumMessage.ChoiceCase.ONEOF_ENUM, lp.getChoiceCase());
        assertTrue(lp.hasOneofEnum());
        assertEquals(OpenEnum.UNRECOGNIZED, lp.getOneofEnum());
        assertEquals(9, lp.getOneofEnumValue());

        // Each element keeps its place in the list
        assertEquals(List.of(OpenEnum.OPEN_ONE, OpenEnum.UNRECOGNIZED, OpenEnum.UNRECOGNIZED, OpenEnum.OPEN_TWO),
                packedEnums(lp));
        assertEquals(List.of(1, 10, -5, 2), packedEnumValues(lp));
        assertEquals(List.of(OpenEnum.UNRECOGNIZED, OpenEnum.OPEN_MINUS_ONE, OpenEnum.OPEN_ZERO), unpackedEnums(lp));
        assertEquals(List.of(11, -1, 0), unpackedEnumValues(lp));

        // No entry is dropped
        assertEquals(3, lp.getEnumMapCount());
        assertEquals(OpenEnum.OPEN_ONE, lp.getEnumMap("known"));
        assertEquals(OpenEnum.UNRECOGNIZED, lp.getEnumMap("unknown"));
        assertEquals(12, lp.getEnumMapValue("unknown"));
        assertEquals(-6, lp.getEnumMapValue("negative"));
        Map<String, OpenEnum> entries = new LinkedHashMap<>();
        lp.forEachEnumMap(entries::put);
        assertEquals(Map.of("known", OpenEnum.OPEN_ONE, "unknown", OpenEnum.UNRECOGNIZED,
                "negative", OpenEnum.UNRECOGNIZED), entries);

        assertEquals(OpenEnumMessage.NestedEnum.UNRECOGNIZED, lp.getNestedEnum());
        assertEquals(13, lp.getNestedEnumValue());

        // Written back as protobuf-java writes them
        assertEquals(wire.length, lp.getSerializedSize());
        assertArrayEquals(wire, lp.toByteArray());
        assertEquals(pb, OpenEnumsProtos.OpenEnumMessage.parseFrom(lp.toByteArray()));
    }

    @Test
    public void testUnknownValuesThroughDirectBuffers() throws Exception {
        // Large enough for writeTo() to write the direct buffer in place, through its NIO view
        OpenEnumsProtos.OpenEnumMessage.Builder b = OpenEnumsProtos.OpenEnumMessage.newBuilder();
        for (int i = 0; i < 300; i++) {
            b.addPackedEnumsValue(i % 5 == 0 ? 1000 + i : i % 3);
            b.addUnpackedEnumsValue(-i);
        }
        byte[] wire = b.setImplicitEnumValue(-7).build().toByteArray();

        ByteBuf in = Unpooled.directBuffer(wire.length);
        ByteBuf out = Unpooled.directBuffer(wire.length);
        try {
            in.writeBytes(wire);
            OpenEnumMessage lp = new OpenEnumMessage();
            lp.parseFrom(in, in.readableBytes());
            assertEquals(-7, lp.getImplicitEnumValue());
            assertEquals(1005, lp.getPackedEnumValueAt(5));
            assertEquals(OpenEnum.UNRECOGNIZED, lp.getUnpackedEnumAt(299));

            assertEquals(wire.length, lp.writeTo(out));
            assertEquals(Unpooled.wrappedBuffer(wire), out);
        } finally {
            in.release();
            out.release();
        }
    }

    @Test
    public void testUnknownValuesInNestedMessages() throws Exception {
        OpenEnumsProtos.OpenEnumHolder pb = OpenEnumsProtos.OpenEnumHolder.newBuilder()
                .setMessage(OpenEnumsProtos.OpenEnumMessage.newBuilder().setImplicitEnumValue(7))
                .addMessages(OpenEnumsProtos.OpenEnumMessage.newBuilder().addPackedEnumsValue(8))
                .build();
        byte[] wire = pb.toByteArray();

        OpenEnumHolder lp = new OpenEnumHolder();
        lp.parseFrom(wire);
        assertEquals(7, lp.getMessage().getImplicitEnumValue());
        assertEquals(OpenEnum.UNRECOGNIZED, lp.getMessageAt(0).getPackedEnumAt(0));
        // Nothing was dropped, so parsing keeps the wire size as the size of the message
        assertTrue(lp._isSizeCached());
        assertArrayEquals(wire, lp.toByteArray());

        OpenEnumHolder copy = new OpenEnumHolder().copyFrom(lp);
        assertEquals(lp, copy);
        assertArrayEquals(wire, copy.toByteArray());
    }

    @Test
    public void testKnownValues() throws Exception {
        OpenEnumMessage lp = new OpenEnumMessage()
                .setImplicitEnum(OpenEnum.OPEN_MINUS_ONE)
                .setOptionalEnum(OpenEnum.OPEN_ZERO)
                .setOneofEnum(OpenEnum.OPEN_TWO)
                .setNestedEnum(OpenEnumMessage.NestedEnum.NESTED_ONE);
        lp.addPackedEnum(OpenEnum.OPEN_ONE);
        lp.addPackedEnum(OpenEnum.OPEN_MINUS_ONE);
        lp.addUnpackedEnum(OpenEnum.OPEN_TWO);
        lp.putEnumMap("k", OpenEnum.OPEN_ONE);

        OpenEnumsProtos.OpenEnumMessage pb = OpenEnumsProtos.OpenEnumMessage.newBuilder()
                .setImplicitEnum(OpenEnumsProtos.OpenEnum.OPEN_MINUS_ONE)
                .setOptionalEnum(OpenEnumsProtos.OpenEnum.OPEN_ZERO)
                .setOneofEnum(OpenEnumsProtos.OpenEnum.OPEN_TWO)
                .setNestedEnum(OpenEnumsProtos.OpenEnumMessage.NestedEnum.NESTED_ONE)
                .addPackedEnums(OpenEnumsProtos.OpenEnum.OPEN_ONE)
                .addPackedEnums(OpenEnumsProtos.OpenEnum.OPEN_MINUS_ONE)
                .addUnpackedEnums(OpenEnumsProtos.OpenEnum.OPEN_TWO)
                .putEnumMap("k", OpenEnumsProtos.OpenEnum.OPEN_ONE)
                .build();
        assertArrayEquals(pb.toByteArray(), lp.toByteArray());

        OpenEnumMessage parsed = parse(pb.toByteArray());
        // -1 is a value of the enum: UNRECOGNIZED only stands for numbers it doesn't define
        assertEquals(OpenEnum.OPEN_MINUS_ONE, parsed.getImplicitEnum());
        assertEquals(-1, parsed.getImplicitEnumValue());
        assertTrue(parsed.hasOptionalEnum());
        assertEquals(OpenEnum.OPEN_ZERO, parsed.getOptionalEnum());
        assertEquals(OpenEnum.OPEN_TWO, parsed.getOneofEnum());
        assertEquals(OpenEnumMessage.NestedEnum.NESTED_ONE, parsed.getNestedEnum());
        assertEquals(List.of(OpenEnum.OPEN_ONE, OpenEnum.OPEN_MINUS_ONE), packedEnums(parsed));
        assertEquals(List.of(OpenEnum.OPEN_TWO), unpackedEnums(parsed));
        assertEquals(OpenEnum.OPEN_ONE, parsed.getEnumMap("k"));
        assertEquals(lp, parsed);
        assertEquals(lp.hashCode(), parsed.hashCode());
    }

    @Test
    public void testDefaults() {
        OpenEnumMessage lp = new OpenEnumMessage();
        assertEquals(OpenEnum.OPEN_ZERO, lp.getImplicitEnum());
        assertEquals(0, lp.getImplicitEnumValue());
        assertFalse(lp.hasOptionalEnum());
        assertEquals(OpenEnum.OPEN_ZERO, lp.getOptionalEnum());
        assertEquals(0, lp.getOptionalEnumValue());
        assertFalse(lp.hasOneofEnum());
        assertEquals(OpenEnum.OPEN_ZERO, lp.getOneofEnum());
        assertEquals(0, lp.getOneofEnumValue());
        assertEquals(OpenEnumMessage.NestedEnum.NESTED_ZERO, lp.getNestedEnum());

        // The oneof switches to another field
        OpenEnumMessage parsed = parse(pbWithUnknownValues().toByteArray());
        parsed.setOneofString("s");
        assertEquals(0, parsed.getOneofEnumValue());
        assertEquals(OpenEnum.OPEN_ZERO, parsed.getOneofEnum());
    }

    @Test
    public void testSettersRejectUnrecognized() {
        OpenEnumMessage lp = new OpenEnumMessage();
        assertThrows(IllegalArgumentException.class, () -> lp.setImplicitEnum(OpenEnum.UNRECOGNIZED));
        assertThrows(IllegalArgumentException.class, () -> lp.setOptionalEnum(OpenEnum.UNRECOGNIZED));
        assertThrows(IllegalArgumentException.class, () -> lp.setOneofEnum(OpenEnum.UNRECOGNIZED));
        assertThrows(IllegalArgumentException.class, () -> lp.addPackedEnum(OpenEnum.UNRECOGNIZED));
        assertThrows(IllegalArgumentException.class, () -> lp.addUnpackedEnum(OpenEnum.UNRECOGNIZED));
        assertThrows(IllegalArgumentException.class, () -> lp.putEnumMap("k", OpenEnum.UNRECOGNIZED));
        assertThrows(IllegalArgumentException.class,
                () -> lp.setNestedEnum(OpenEnumMessage.NestedEnum.UNRECOGNIZED));

        // Before changing anything
        assertFalse(lp.hasOptionalEnum());
        assertEquals(OpenEnumMessage.ChoiceCase.NOT_SET, lp.getChoiceCase());
        assertEquals(0, lp.getPackedEnumsCount());
        assertEquals(0, lp.getUnpackedEnumsCount());
        assertEquals(0, lp.getEnumMapCount());
        assertEquals(0, lp.getSerializedSize());
        assertEquals(new OpenEnumMessage(), lp);
    }

    @Test
    public void testEqualsAndHashCodeCompareNumbers() {
        // Two numbers the enum doesn't define both read as UNRECOGNIZED, but they differ
        List<IntFunction<OpenEnumsProtos.OpenEnumMessage>> fields = List.of(
                n -> OpenEnumsProtos.OpenEnumMessage.newBuilder().setImplicitEnumValue(n).build(),
                n -> OpenEnumsProtos.OpenEnumMessage.newBuilder().setOptionalEnumValue(n).build(),
                n -> OpenEnumsProtos.OpenEnumMessage.newBuilder().setOneofEnumValue(n).build(),
                n -> OpenEnumsProtos.OpenEnumMessage.newBuilder().addPackedEnumsValue(n).build(),
                n -> OpenEnumsProtos.OpenEnumMessage.newBuilder().addUnpackedEnumsValue(n).build(),
                n -> OpenEnumsProtos.OpenEnumMessage.newBuilder().putEnumMapValue("k", n).build(),
                n -> OpenEnumsProtos.OpenEnumMessage.newBuilder().setNestedEnumValue(n).build());
        for (IntFunction<OpenEnumsProtos.OpenEnumMessage> field : fields) {
            OpenEnumMessage a = parse(field.apply(7).toByteArray());
            OpenEnumMessage b = parse(field.apply(7).toByteArray());
            OpenEnumMessage c = parse(field.apply(8).toByteArray());
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, c);
        }
    }

    @Test
    public void testCopyFrom() {
        byte[] wire = pbWithUnknownValues().toByteArray();
        OpenEnumMessage parsed = parse(wire);
        OpenEnumMessage copy = new OpenEnumMessage().copyFrom(parsed);
        assertEquals(parsed, copy);
        assertEquals(7, copy.getImplicitEnumValue());
        assertEquals(OpenEnum.UNRECOGNIZED, copy.getEnumMap("unknown"));
        assertArrayEquals(wire, copy.toByteArray());
    }

    @Test
    public void testClearAndReuse() {
        OpenEnumMessage lp = parse(pbWithUnknownValues().toByteArray());
        lp.clear();
        assertEquals(OpenEnum.OPEN_ZERO, lp.getImplicitEnum());
        assertEquals(0, lp.getImplicitEnumValue());
        assertFalse(lp.hasOptionalEnum());
        assertEquals(0, lp.getOptionalEnumValue());
        assertEquals(OpenEnumMessage.ChoiceCase.NOT_SET, lp.getChoiceCase());
        assertEquals(0, lp.getOneofEnumValue());
        assertEquals(0, lp.getPackedEnumsCount());
        assertEquals(0, lp.getEnumMapCount());
        assertEquals(0, lp.getNestedEnumValue());
        assertEquals(0, lp.getSerializedSize());
        assertEquals(new OpenEnumMessage(), lp);

        // Reused for a message without the enum fields: no number of the previous one is left
        lp.parseFrom(pbWithUnknownValues().toByteArray());
        lp.parseFrom(OpenEnumsProtos.OpenEnumMessage.newBuilder().setOneofString("s").build().toByteArray());
        assertEquals(0, lp.getImplicitEnumValue());
        assertEquals(0, lp.getOptionalEnumValue());
        assertEquals(0, lp.getOneofEnumValue());
        assertEquals(0, lp.getNestedEnumValue());
        assertEquals("s", lp.getOneofString());

        lp.parseFrom(pbWithUnknownValues().toByteArray());
        lp.clearImplicitEnum();
        lp.clearOptionalEnum();
        assertEquals(0, lp.getImplicitEnumValue());
        assertFalse(lp.hasOptionalEnum());
        assertEquals(0, lp.getOptionalEnumValue());
    }

    @Test
    public void testJsonWritesUnknownNumbers() throws Exception {
        OpenEnumsProtos.OpenEnumMessage pb = pbWithUnknownValues();
        String json = parse(pb.toByteArray()).toJson();

        // As protobuf's JSON printer does: a name, or the number the enum doesn't define
        assertTrue(json.contains("\"implicitEnum\":7"), json);
        assertTrue(json.contains("\"packedEnums\":[\"OPEN_ONE\",10,-5,\"OPEN_TWO\"]"), json);
        assertTrue(json.contains("\"enumMap\":{\"known\":\"OPEN_ONE\",\"negative\":-6,\"unknown\":12}"), json);

        OpenEnumsProtos.OpenEnumMessage.Builder b = OpenEnumsProtos.OpenEnumMessage.newBuilder();
        JsonFormat.parser().merge(json, b);
        assertEquals(pb, b.build());
    }

    @Test
    public void testJsonReadsNumbers() throws Exception {
        OpenEnumsProtos.OpenEnumMessage pb = pbWithUnknownValues();
        OpenEnumMessage fromJson = new OpenEnumMessage();
        fromJson.parseFromJson(JsonFormat.printer().print(pb));
        assertEquals(parse(pb.toByteArray()), fromJson);
        assertArrayEquals(pb.toByteArray(), fromJson.toByteArray());

        OpenEnumMessage again = new OpenEnumMessage();
        again.parseFromJson(fromJson.toJson());
        assertEquals(fromJson, again);

        // Names, and numbers that the enum defines
        OpenEnumMessage named = new OpenEnumMessage();
        named.parseFromJson("{\"implicitEnum\":\"OPEN_TWO\",\"optionalEnum\":1,\"enumMap\":{\"k\":\"OPEN_MINUS_ONE\"}}");
        assertEquals(OpenEnum.OPEN_TWO, named.getImplicitEnum());
        assertEquals(OpenEnum.OPEN_ONE, named.getOptionalEnum());
        assertEquals(OpenEnum.OPEN_MINUS_ONE, named.getEnumMap("k"));

        // UNRECOGNIZED is not a name of a value
        assertThrows(IllegalArgumentException.class,
                () -> new OpenEnumMessage().parseFromJson("{\"implicitEnum\":\"UNRECOGNIZED\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> new OpenEnumMessage().parseFromJson("{\"packedEnums\":[\"UNRECOGNIZED\"]}"));
    }

    @Test
    public void testTextFormatWritesUnknownNumbers() throws Exception {
        OpenEnumsProtos.OpenEnumMessage pb = pbWithUnknownValues();
        String text = parse(pb.toByteArray()).toTextFormat();

        // As protobuf's TextFormat printer does
        assertEquals(TextFormat.printer().printToString(pb), text);
        assertTrue(text.contains("implicit_enum: 7\n"), text);
        assertTrue(text.contains("packed_enums: -5\n"), text);

        OpenEnumsProtos.OpenEnumMessage.Builder b = OpenEnumsProtos.OpenEnumMessage.newBuilder();
        TextFormat.merge(text, b);
        assertEquals(pb, b.build());
    }

    @Test
    public void testTextFormatReadsNumbers() {
        OpenEnumsProtos.OpenEnumMessage pb = pbWithUnknownValues();
        OpenEnumMessage fromText = new OpenEnumMessage();
        fromText.parseFromTextFormat(TextFormat.printer().printToString(pb));
        assertEquals(parse(pb.toByteArray()), fromText);
        assertArrayEquals(pb.toByteArray(), fromText.toByteArray());

        OpenEnumMessage list = new OpenEnumMessage();
        list.parseFromTextFormat("implicit_enum: OPEN_TWO\n"
                + "packed_enums: [OPEN_ONE, 7, -5]\n"
                + "enum_map { key: \"k\" value: 9 }\n");
        assertEquals(OpenEnum.OPEN_TWO, list.getImplicitEnum());
        assertEquals(List.of(1, 7, -5), packedEnumValues(list));
        assertEquals(9, list.getEnumMapValue("k"));

        assertThrows(IllegalArgumentException.class,
                () -> new OpenEnumMessage().parseFromTextFormat("implicit_enum: UNRECOGNIZED"));
    }

    /**
     * A proto2 message using a proto3 enum: protobuf-java treats its fields as closed, as all the
     * enum fields of proto2 files are, and so does LightProto.
     */
    @Test
    public void testProto2FieldsOfAnOpenEnumStayClosed() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        out.writeEnum(1, 7);
        out.writeEnum(2, 1);
        out.writeEnum(2, 7);
        out.writeEnum(2, 2);
        out.writeTag(3, WireFormat.WIRETYPE_LENGTH_DELIMITED);
        out.writeUInt32NoTag(3);
        out.writeEnumNoTag(1);
        out.writeEnumNoTag(7);
        out.writeEnumNoTag(2);
        writeMapEntry(out, 4, "a", 1);
        writeMapEntry(out, 4, "b", 7);
        out.flush();
        byte[] wire = bytes.toByteArray();

        Proto2OpenEnumProtos.Proto2OpenEnumMessage pb = Proto2OpenEnumProtos.Proto2OpenEnumMessage.parseFrom(wire);
        assertFalse(pb.hasOptionalEnum());
        assertEquals(List.of(OpenEnumsProtos.OpenEnum.OPEN_ONE, OpenEnumsProtos.OpenEnum.OPEN_TWO),
                pb.getUnpackedEnumsList());
        assertEquals(List.of(OpenEnumsProtos.OpenEnum.OPEN_ONE, OpenEnumsProtos.OpenEnum.OPEN_TWO),
                pb.getPackedEnumsList());
        assertEquals(Map.of("a", OpenEnumsProtos.OpenEnum.OPEN_ONE), pb.getEnumMapMap());

        Proto2OpenEnumMessage lp = new Proto2OpenEnumMessage();
        lp.parseFrom(wire);
        assertFalse(lp.hasOptionalEnum());
        assertEquals(OpenEnum.OPEN_ZERO, lp.getOptionalEnum());
        assertEquals(2, lp.getUnpackedEnumsCount());
        assertEquals(OpenEnum.OPEN_ONE, lp.getUnpackedEnumAt(0));
        assertEquals(OpenEnum.OPEN_TWO, lp.getUnpackedEnumAt(1));
        assertEquals(2, lp.getPackedEnumsCount());
        assertEquals(OpenEnum.OPEN_ONE, lp.getPackedEnumAt(0));
        assertEquals(OpenEnum.OPEN_TWO, lp.getPackedEnumAt(1));
        assertEquals(1, lp.getEnumMapCount());
        assertEquals(OpenEnum.OPEN_ONE, lp.getEnumMap("a"));

        // Written without the dropped numbers, as protobuf-java writes it without its unknown fields
        byte[] expected = pb.toBuilder().setUnknownFields(UnknownFieldSet.getDefaultInstance()).build().toByteArray();
        assertArrayEquals(expected, lp.toByteArray());

        // Closed fields have no accessor for the number
        assertThrows(NoSuchMethodException.class, () -> Proto2OpenEnumMessage.class.getMethod("getOptionalEnumValue"));
    }

    @Test
    public void testProto2EnumsHaveNoUnrecognizedConstant() {
        assertTrue(Arrays.stream(E1.values()).noneMatch(v -> v.name().equals("UNRECOGNIZED")));
        assertTrue(Arrays.stream(MapEnumValue.values()).noneMatch(v -> v.name().equals("UNRECOGNIZED")));
        assertTrue(Arrays.stream(Person.PhoneType.values()).noneMatch(v -> v.name().equals("UNRECOGNIZED")));
    }

    private static void writeMapEntry(CodedOutputStream out, int field, String key, int value) throws Exception {
        out.writeTag(field, WireFormat.WIRETYPE_LENGTH_DELIMITED);
        out.writeUInt32NoTag(CodedOutputStream.computeStringSize(1, key) + CodedOutputStream.computeEnumSize(2, value));
        out.writeString(1, key);
        out.writeEnum(2, value);
    }
}
