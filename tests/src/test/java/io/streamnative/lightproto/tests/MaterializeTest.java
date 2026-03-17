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
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class MaterializeTest {

    private byte[] b1 = new byte[4096];
    private ByteBuf bb1 = Unpooled.wrappedBuffer(b1);

    @BeforeEach
    public void setup() {
        bb1.clear();
        Arrays.fill(b1, (byte) 0);
    }

    @Test
    public void testMaterializeReleasesBufferReference() {
        M original = new M();
        original.setX().setA("hello").setB("world");
        original.addItem().setK("k1").setV("v1");
        original.writeTo(bb1);

        M parsed = new M();
        parsed.parseFrom(bb1, bb1.readableBytes());

        // After parseFrom, the parsed message retains a reference to the buffer
        // Calling materialize() should resolve all lazy fields and release the buffer reference
        parsed.materialize();

        assertEquals("hello", parsed.getX().getA());
        assertEquals("world", parsed.getX().getB());
        assertEquals(1, parsed.getItemsCount());
        assertEquals("k1", parsed.getItemAt(0).getK());
        assertEquals("v1", parsed.getItemAt(0).getV());
    }

    @Test
    public void testMaterializeWithNestedMessages() {
        M original = new M();
        original.setX().setA("nested").setB("msg");
        original.addItem().setK("k1").setV("v1").setXx().setN(42);
        original.addItem().setK("k2").setV("v2");
        original.writeTo(bb1);

        M parsed = new M();
        parsed.parseFrom(bb1, bb1.readableBytes());
        parsed.materialize();

        assertEquals("nested", parsed.getX().getA());
        assertEquals("msg", parsed.getX().getB());
        assertEquals(2, parsed.getItemsCount());
        assertEquals("k1", parsed.getItemAt(0).getK());
        assertEquals("v1", parsed.getItemAt(0).getV());
        assertEquals(42, parsed.getItemAt(0).getXx().getN());
        assertEquals("k2", parsed.getItemAt(1).getK());
        assertEquals("v2", parsed.getItemAt(1).getV());
    }

    @Test
    public void testMaterializeAllowsBufferReuse() {
        M original = new M();
        original.setX().setA("first").setB("message");
        original.writeTo(bb1);

        M parsed = new M();
        parsed.parseFrom(bb1, bb1.readableBytes());
        parsed.materialize();

        // Overwrite the buffer with different data
        bb1.clear();
        Arrays.fill(b1, (byte) 0);
        M other = new M();
        other.setX().setA("second").setB("different");
        other.writeTo(bb1);

        // The materialized message should still have the original values
        assertEquals("first", parsed.getX().getA());
        assertEquals("message", parsed.getX().getB());
    }

    @Test
    public void testMaterializeIsIdempotent() {
        M original = new M();
        original.setX().setA("idem").setB("potent");
        original.addItem().setK("k").setV("v");
        original.writeTo(bb1);

        M parsed = new M();
        parsed.parseFrom(bb1, bb1.readableBytes());

        // Calling materialize multiple times should be safe
        parsed.materialize();
        parsed.materialize();

        assertEquals("idem", parsed.getX().getA());
        assertEquals("potent", parsed.getX().getB());
        assertEquals("k", parsed.getItemAt(0).getK());
        assertEquals("v", parsed.getItemAt(0).getV());
    }
}
