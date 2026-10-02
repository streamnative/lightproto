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

import com.google.protobuf.ByteString;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Serializing and parsing both cache the serialized size, so every per-field
 * clearX() must drop it: otherwise writeTo() keeps writing the size of the
 * message from before the clear, followed by trailing bytes that are not part of it.
 */
public class ClearFieldTest {

    @Test
    public void testClearNumberField() {
        byte[] wire = NumbersOuterClass.Numbers.newBuilder()
                .setXInt32(1).setXInt64(1L << 40).setXDouble(2.5)
                .build().toByteArray();
        byte[] expected = NumbersOuterClass.Numbers.newBuilder()
                .setXInt32(1).setXDouble(2.5)
                .build().toByteArray();

        assertClearedField(Numbers::new,
                lp -> lp.setXInt32(1).setXInt64(1L << 40).setXDouble(2.5),
                Numbers::clearXInt64, wire, expected);
    }

    @Test
    public void testClearStringField() {
        byte[] wire = Strings.S.newBuilder()
                .setId("a-fairly-long-id").addNames("n1").addNames("n2")
                .build().toByteArray();
        byte[] expected = Strings.S.newBuilder()
                .addNames("n1").addNames("n2")
                .build().toByteArray();

        assertClearedField(S::new, lp -> {
            lp.setId("a-fairly-long-id");
            lp.addName("n1");
            lp.addName("n2");
        }, S::clearId, wire, expected);
    }

    @Test
    public void testClearBytesField() {
        byte[] payload = new byte[32];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i + 1);
        }
        byte[] wire = Bytes.B.newBuilder()
                .setPayload(ByteString.copyFrom(payload)).addExtraItems(ByteString.copyFrom(new byte[]{9}))
                .build().toByteArray();
        byte[] expected = Bytes.B.newBuilder()
                .addExtraItems(ByteString.copyFrom(new byte[]{9}))
                .build().toByteArray();

        assertClearedField(B::new, lp -> {
            lp.setPayload(payload);
            lp.addExtraItem(new byte[]{9});
        }, B::clearPayload, wire, expected);
    }

    @Test
    public void testClearMessageField() {
        byte[] wire = Messages.M.newBuilder()
                .setX(Messages.X.newBuilder().setA("a-fairly-long-value").setB("b"))
                .addItems(Messages.M.KV.newBuilder().setK("k").setV("v"))
                .build().toByteArray();
        byte[] expected = Messages.M.newBuilder()
                .addItems(Messages.M.KV.newBuilder().setK("k").setV("v"))
                .build().toByteArray();

        assertClearedField(M::new, lp -> {
            lp.setX().setA("a-fairly-long-value").setB("b");
            lp.addItem().setK("k").setV("v");
        }, M::clearX, wire, expected);
    }

    @Test
    public void testClearRepeatedField() {
        byte[] wire = Messages.M.newBuilder()
                .setX(Messages.X.newBuilder().setA("a"))
                .addItems(Messages.M.KV.newBuilder().setK("k1").setV("v1"))
                .addItems(Messages.M.KV.newBuilder().setK("k2").setV("v2"))
                .build().toByteArray();
        byte[] expected = Messages.M.newBuilder()
                .setX(Messages.X.newBuilder().setA("a"))
                .build().toByteArray();

        assertClearedField(M::new, lp -> {
            lp.setX().setA("a");
            lp.addItem().setK("k1").setV("v1");
            lp.addItem().setK("k2").setV("v2");
        }, M::clearItems, wire, expected);
    }

    @Test
    public void testClearMapField() {
        byte[] wire = MapsProtos.MapMessage.newBuilder()
                .putStringToInt("a", 1).putStringToInt("b", 2).setName("m")
                .build().toByteArray();
        byte[] expected = MapsProtos.MapMessage.newBuilder()
                .setName("m")
                .build().toByteArray();

        assertClearedField(MapMessage::new, lp -> {
            lp.putStringToInt("a", 1);
            lp.putStringToInt("b", 2);
            lp.setName("m");
        }, MapMessage::clearStringToInt, wire, expected);
    }

    @Test
    public void testClearOneofField() {
        byte[] wire = OneofProtos.OneofMsg.newBuilder()
                .setName("n").setOneofString("a-fairly-long-value").setAfterField(7)
                .build().toByteArray();
        byte[] expected = OneofProtos.OneofMsg.newBuilder()
                .setName("n").setAfterField(7)
                .build().toByteArray();

        assertClearedField(OneofMsg::new,
                lp -> lp.setName("n").setOneofString("a-fairly-long-value").setAfterField(7),
                OneofMsg::clearOneofString, wire, expected);
    }

    /**
     * Clears a field once after serializing the message built by {@code populate},
     * and once after parsing {@code wire}, then checks the result against
     * {@code expected}, which protobuf-java built without that field.
     */
    private static <T extends LightProtoCodec.LightProtoMessage> void assertClearedField(
            Supplier<T> factory, Consumer<T> populate, Consumer<T> clear, byte[] wire, byte[] expected) {
        assertAll(
                () -> {
                    T serialized = factory.get();
                    populate.accept(serialized);
                    assertSerializedAs(wire, serialized);
                    clear.accept(serialized);
                    assertSerializedAs(expected, serialized);
                },
                () -> {
                    T parsed = factory.get();
                    parsed.parseFrom(wire);
                    clear.accept(parsed);
                    assertSerializedAs(expected, parsed);
                });
    }

    private static void assertSerializedAs(byte[] expected, LightProtoCodec.LightProtoMessage lp) {
        assertEquals(expected.length, lp.getSerializedSize());
        ByteBuf out = Unpooled.buffer();
        assertEquals(expected.length, lp.writeTo(out));
        assertArrayEquals(expected, ByteBufUtil.getBytes(out));
    }
}
