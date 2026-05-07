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

import com.google.protobuf.TextFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that the generated TextFormat (de)serialization is wire-compatible with
 * {@link com.google.protobuf.TextFormat} from protobuf-java.
 */
public class TextFormatTest {

    @Test
    public void numbersRoundTripThroughProtobufTextFormat() throws Exception {
        Numbers lp = new Numbers()
                .setXInt32(42)
                .setXInt64(123456789L)
                .setXUint32(100)
                .setXUint64(200L)
                .setXSint32(-50)
                .setXSint64(-100L)
                .setXFixed32(1000)
                .setXFixed64(2000L)
                .setXSfixed32(-1000)
                .setXSfixed64(-2000L)
                .setXFloat(3.14f)
                .setXDouble(2.71828)
                .setXBool(true)
                .setEnum1(Enum1.X1_1)
                .setEnum2(Numbers.Enum2.X2_2);

        String text = lp.toTextFormat();

        // protobuf-java parses what LightProto wrote
        NumbersOuterClass.Numbers.Builder b = NumbersOuterClass.Numbers.newBuilder();
        TextFormat.merge(text, b);
        NumbersOuterClass.Numbers pb = b.build();

        assertEquals(42, pb.getXInt32());
        assertEquals(123456789L, pb.getXInt64());
        assertEquals(100, pb.getXUint32());
        assertEquals(200L, pb.getXUint64());
        assertEquals(-50, pb.getXSint32());
        assertEquals(-100L, pb.getXSint64());
        assertEquals(1000, pb.getXFixed32());
        assertEquals(2000L, pb.getXFixed64());
        assertEquals(-1000, pb.getXSfixed32());
        assertEquals(-2000L, pb.getXSfixed64());
        assertEquals(3.14f, pb.getXFloat());
        assertEquals(2.71828, pb.getXDouble());
        assertTrue(pb.getXBool());
        assertEquals(NumbersOuterClass.Enum1.X1_1, pb.getEnum1());
        assertEquals(NumbersOuterClass.Numbers.Enum2.X2_2, pb.getEnum2());

        // LightProto parses what protobuf-java wrote
        String pbText = TextFormat.printer().printToString(pb);
        Numbers parsed = new Numbers();
        parsed.parseFromTextFormat(pbText);
        assertEquals(lp, parsed);
    }

    @Test
    public void stringRoundTripWithEscapes() throws Exception {
        S lp = new S();
        lp.setId("hello \"world\"\nnew\tline\\slash");
        lp.addName("alice");
        lp.addName("bob");

        String text = lp.toTextFormat();

        Strings.S.Builder b = Strings.S.newBuilder();
        TextFormat.merge(text, b);
        Strings.S pb = b.build();

        assertEquals("hello \"world\"\nnew\tline\\slash", pb.getId());
        assertEquals(2, pb.getNamesCount());
        assertEquals("alice", pb.getNames(0));
        assertEquals("bob", pb.getNames(1));

        // Reverse direction
        String pbText = TextFormat.printer().printToString(pb);
        S parsed = new S();
        parsed.parseFromTextFormat(pbText);
        assertEquals(lp, parsed);
    }

    @Test
    public void utf8StringRoundTrip() throws Exception {
        S lp = new S();
        lp.setId("café — naïve 日本語");

        String text = lp.toTextFormat();

        Strings.S.Builder b = Strings.S.newBuilder();
        TextFormat.merge(text, b);
        assertEquals("café — naïve 日本語", b.build().getId());

        S parsed = new S();
        parsed.parseFromTextFormat(TextFormat.printer().printToString(b.build()));
        assertEquals("café — naïve 日本語", parsed.getId());
    }

    @Test
    public void nestedMessagesAndRepeated() throws Exception {
        M lp = new M();
        lp.setX().setA("value-a").setB("value-b");
        lp.addItem().setK("key1").setV("val1");
        lp.addItem().setK("key2").setV("val2").setXx().setN(42);

        String text = lp.toTextFormat();

        Messages.M.Builder b = Messages.M.newBuilder();
        TextFormat.merge(text, b);
        Messages.M pb = b.build();

        assertEquals("value-a", pb.getX().getA());
        assertEquals("value-b", pb.getX().getB());
        assertEquals(2, pb.getItemsCount());
        assertEquals("key1", pb.getItems(0).getK());
        assertEquals("val1", pb.getItems(0).getV());
        assertEquals("key2", pb.getItems(1).getK());
        assertEquals("val2", pb.getItems(1).getV());
        assertEquals(42, pb.getItems(1).getXx().getN());

        // Reverse direction
        M parsed = new M();
        parsed.parseFromTextFormat(TextFormat.printer().printToString(pb));
        assertEquals(lp, parsed);
    }

    @Test
    public void mapsRoundTrip() throws Exception {
        MapMessage lp = new MapMessage();
        lp.putStringToInt("a", 1);
        lp.putStringToInt("b", 2);
        lp.putIntToString(10, "ten");
        lp.putIntToString(20, "twenty");
        lp.putStringToDouble("pi", 3.14);
        lp.setName("test-map");

        String text = lp.toTextFormat();

        MapsProtos.MapMessage.Builder b = MapsProtos.MapMessage.newBuilder();
        TextFormat.merge(text, b);
        MapsProtos.MapMessage pb = b.build();

        assertEquals(1, pb.getStringToIntOrThrow("a"));
        assertEquals(2, pb.getStringToIntOrThrow("b"));
        assertEquals("ten", pb.getIntToStringOrThrow(10));
        assertEquals("twenty", pb.getIntToStringOrThrow(20));
        assertEquals(3.14, pb.getStringToDoubleOrThrow("pi"), 0.001);
        assertEquals("test-map", pb.getName());

        MapMessage parsed = new MapMessage();
        parsed.parseFromTextFormat(TextFormat.printer().printToString(pb));
        assertEquals(lp, parsed);
    }

    @Test
    public void bytesRoundTrip() throws Exception {
        B lp = new B();
        lp.setPayload(new byte[]{0, 1, 2, (byte) 0xFF, (byte) 0x80, 'a', 'b', '\n', '\\'});

        String text = lp.toTextFormat();

        Bytes.B.Builder b = Bytes.B.newBuilder();
        TextFormat.merge(text, b);
        assertArrayEquals(new byte[]{0, 1, 2, (byte) 0xFF, (byte) 0x80, 'a', 'b', '\n', '\\'},
                b.build().getPayload().toByteArray());

        B parsed = new B();
        parsed.parseFromTextFormat(TextFormat.printer().printToString(b.build()));
        assertArrayEquals(lp.getPayload(), parsed.getPayload());
    }

    @Test
    public void emptyMessageProducesEmptyText() throws Exception {
        Numbers n = new Numbers();
        assertEquals("", n.toTextFormat());

        Numbers parsed = new Numbers();
        parsed.parseFromTextFormat("");
        assertEquals(n, parsed);
    }

    @Test
    public void specialFloatValues() throws Exception {
        Numbers lp = new Numbers()
                .setXFloat(Float.NaN)
                .setXDouble(Double.POSITIVE_INFINITY);

        String text = lp.toTextFormat();
        assertTrue(text.contains("nan"));
        assertTrue(text.contains("inf"));

        NumbersOuterClass.Numbers.Builder b = NumbersOuterClass.Numbers.newBuilder();
        TextFormat.merge(text, b);
        assertTrue(Float.isNaN(b.build().getXFloat()));
        assertEquals(Double.POSITIVE_INFINITY, b.build().getXDouble());

        // Reverse
        Numbers parsed = new Numbers();
        parsed.parseFromTextFormat(TextFormat.printer().printToString(b.build()));
        assertTrue(Float.isNaN(parsed.getXFloat()));
        assertEquals(Double.POSITIVE_INFINITY, parsed.getXDouble());
    }

    @Test
    public void parserAcceptsAngleBracketSubMessages() throws Exception {
        // protobuf TextFormat allows '<...>' as an alternative to '{...}' for sub-messages
        String text = "x < a: \"hi\" b: \"there\" >";

        M parsed = new M();
        parsed.parseFromTextFormat(text);

        assertEquals("hi", parsed.getX().getA());
        assertEquals("there", parsed.getX().getB());
    }

    @Test
    public void parserAcceptsCommentsAndArraySyntax() throws Exception {
        // Comments with '#', and '[1,2,3]' array syntax for repeated values
        String text =
                "# leading comment\n" +
                "x_int32: 1\n" +
                "# inline comment about the next field\n" +
                "x_int64: 2\n";

        Numbers parsed = new Numbers();
        parsed.parseFromTextFormat(text);
        assertEquals(1, parsed.getXInt32());
        assertEquals(2L, parsed.getXInt64());
    }

    @Test
    public void parserSkipsUnknownFields() throws Exception {
        String text =
                "id: \"keep\"\n" +
                "unknown_field: 42\n" +
                "another_unknown { nested: \"x\" repeated: 1 repeated: 2 }\n" +
                "names: \"a\"\n";

        S parsed = new S();
        parsed.parseFromTextFormat(text);
        assertEquals("keep", parsed.getId());
        assertEquals(1, parsed.getNamesCount());
        assertEquals("a", parsed.getNameAt(0));
    }

    @Test
    public void crossPackageMessageRoundTrip() throws Exception {
        // Importer.Container has a field of type imported.SharedItem from another package —
        // exercises the cross-package ByteBuf cursor handoff.
        io.streamnative.lightproto.tests.importer.Container c =
                new io.streamnative.lightproto.tests.importer.Container();
        c.setLabel("outer");
        c.setItem().setName("inner-name").setValue(42);

        String text = c.toTextFormat();

        io.streamnative.lightproto.tests.importer.Container parsed =
                new io.streamnative.lightproto.tests.importer.Container();
        parsed.parseFromTextFormat(text);

        assertEquals("outer", parsed.getLabel());
        assertEquals("inner-name", parsed.getItem().getName());
        assertEquals(42, parsed.getItem().getValue());
    }

    @Test
    public void enumValuesAreUnquoted() throws Exception {
        Numbers lp = new Numbers().setEnum1(Enum1.X1_2);
        String text = lp.toTextFormat();
        // Unquoted in TextFormat (unlike JSON which uses quoted strings)
        assertTrue(text.contains("enum1: X1_2"), "Expected 'enum1: X1_2' in:\n" + text);
        assertFalse(text.contains("\"X1_2\""), "Enum should not be quoted");
    }

    @Test
    public void int64sAreUnquoted() throws Exception {
        // Unlike JSON which quotes int64 as strings, TextFormat leaves them unquoted.
        Numbers lp = new Numbers().setXInt64(123456789L);
        String text = lp.toTextFormat();
        assertTrue(text.contains("x_int64: 123456789"), "Expected unquoted int64 in:\n" + text);
        assertFalse(text.contains("\"123456789\""), "int64 should not be quoted");
    }

    @Test
    public void fieldNamesUseSnakeCase() throws Exception {
        Numbers lp = new Numbers().setXInt32(1);
        String text = lp.toTextFormat();
        assertTrue(text.contains("x_int32:"), "TextFormat should use proto snake_case names");
        assertFalse(text.contains("xInt32:"), "TextFormat should not use camelCase");
    }
}
