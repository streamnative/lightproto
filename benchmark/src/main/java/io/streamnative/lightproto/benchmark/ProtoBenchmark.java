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
package io.streamnative.lightproto.benchmark;

import com.google.protobuf.CodedOutputStream;
import io.streamnative.lightproto.tests.AddressBook;
import io.streamnative.lightproto.tests.AddressBookProtos;
import io.streamnative.lightproto.tests.Person;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@Warmup(iterations = 3)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Measurement(iterations = 3)
@Fork(value = 1)
public class ProtoBenchmark {

    private final byte[] serialized;

    private AddressBook ab = new AddressBook();

    public ProtoBenchmark() {
        ab = fillLightProto();
        serialized = new byte[ab.getSerializedSize()];
        ab.writeTo(Unpooled.wrappedBuffer(serialized).resetWriterIndex());
        serializeByteBuf = PooledByteBufAllocator.DEFAULT.buffer(serialized.length);
        serializeByteBuf.writeBytes(serialized);
    }

    private AddressBook fillLightProto() {
        Person p1 = ab.addPerson();
        p1.setName("name");
        p1.setEmail("name@example.com");
        p1.setId(5);
        Person.PhoneNumber p1_pn1 = p1.addPhone();
        p1_pn1.setNumber("xxx-zzz-yyyyy");
        p1_pn1.setType(Person.PhoneType.HOME);

        Person.PhoneNumber p1_pn2 = p1.addPhone();
        p1_pn2.setNumber("xxx-zzz-yyyyy");
        p1_pn2.setType(Person.PhoneType.MOBILE);

        Person p2 = ab.addPerson();
        p2.setName("name 2");
        p2.setEmail("name2@example.com");
        p2.setId(6);

        Person.PhoneNumber p2_pn1 = p2.addPhone();
        p2_pn1.setNumber("xxx-zzz-yyyyy");
        p2_pn1.setType(Person.PhoneType.HOME);
        return ab;
    }

    private final ByteBuf buffer = PooledByteBufAllocator.DEFAULT.buffer(1024);
    byte[] data = new byte[1024];
    private final ByteBuf serializeByteBuf;

    private static AddressBookProtos.AddressBook _protobufPrepare() {
        AddressBookProtos.AddressBook.Builder pbab = AddressBookProtos.AddressBook.newBuilder();
        AddressBookProtos.Person.Builder pb_p1 = AddressBookProtos.Person.newBuilder();
        pb_p1.setName("name 1");
        pb_p1.setEmail("name1@example.com");
        pb_p1.setId(5);
        AddressBookProtos.Person.PhoneNumber.Builder pb1_pn1 = AddressBookProtos.Person.PhoneNumber.newBuilder();
        pb1_pn1.setNumber("xxx-zzz-1111");
        pb1_pn1.setType(AddressBookProtos.Person.PhoneType.HOME);

        AddressBookProtos.Person.PhoneNumber.Builder pb1_pn2 = AddressBookProtos.Person.PhoneNumber.newBuilder();
        pb1_pn2.setNumber("xxx-zzz-2222");
        pb1_pn2.setType(AddressBookProtos.Person.PhoneType.MOBILE);

        pb_p1.addPhone(pb1_pn1);
        pb_p1.addPhone(pb1_pn2);

        AddressBookProtos.Person.Builder pb_p2 = AddressBookProtos.Person.newBuilder();
        pb_p2.setName("name 2");
        pb_p2.setEmail("name2@example.com");
        pb_p2.setId(6);

        AddressBookProtos.Person.PhoneNumber.Builder pb2_pn1 = AddressBookProtos.Person.PhoneNumber.newBuilder();
        pb2_pn1.setNumber("xxx-zzz-2222");
        pb2_pn1.setType(AddressBookProtos.Person.PhoneType.HOME);

        pb_p2.addPhone(pb2_pn1);

        pbab.addPerson(pb_p1);
        pbab.addPerson(pb_p2);

        return pbab.build();
    }

    private static final AddressBookProtos.AddressBook filledProtobuf = _protobufPrepare();

    @Benchmark
    public void protobufSerialize(Blackhole bh) throws Exception {
        CodedOutputStream s = CodedOutputStream.newInstance(data);
        filledProtobuf.writeTo(s);
        bh.consume(s);
    }

    @Benchmark
    public void protobufFillAndSerialize(Blackhole bh) throws Exception {
        AddressBookProtos.AddressBook ab = _protobufPrepare();
        CodedOutputStream s = CodedOutputStream.newInstance(data);
        ab.writeTo(s);
        bh.consume(s);
    }

    @Benchmark
    public void lightProtoFillAndSerialize(Blackhole bh) {
        ab.clear();
        ab = fillLightProto();

        ab.writeTo(buffer);

        bh.consume(ab);
        bh.consume(buffer);
        buffer.clear();
    }

    @Benchmark
    public void lightProtoSerialize(Blackhole bh) {
        ab.writeTo(buffer);
        buffer.clear();

        bh.consume(buffer);
        bh.consume(ab);
    }

    @Benchmark
    public void protobufDeserialize(Blackhole bh) throws Exception {
        AddressBookProtos.AddressBook ab = AddressBookProtos.AddressBook.newBuilder().mergeFrom(serialized).build();
        bh.consume(ab);
    }

    @Benchmark
    public void lightProtoDeserialize(Blackhole bh) {
        ab.parseFrom(serializeByteBuf, serializeByteBuf.readableBytes());
        serializeByteBuf.resetReaderIndex();
        bh.consume(ab);
    }

}
