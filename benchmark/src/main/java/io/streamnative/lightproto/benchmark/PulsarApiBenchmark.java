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
import io.netty.buffer.ByteBuf;
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
public class PulsarApiBenchmark {

    // --- MessageMetadata ---

    private static final long PUBLISH_TIME = System.currentTimeMillis();

    private final org.apache.pulsar.common.api.proto.MessageMetadata lpMessageMetadata =
            new org.apache.pulsar.common.api.proto.MessageMetadata();

    private final byte[] mdSerialized;
    private final ByteBuf mdSerializeByteBuf;

    // --- BaseCommand + CommandSend ---

    private final org.apache.pulsar.common.api.proto.BaseCommand lpBaseCommand =
            new org.apache.pulsar.common.api.proto.BaseCommand();

    private final byte[] cmdSerialized;
    private final ByteBuf cmdSerializeByteBuf;

    // --- Reusable write buffers ---

    private final ByteBuf buffer = PooledByteBufAllocator.DEFAULT.buffer(1024);
    private final byte[] data = new byte[1024];

    // --- Protobuf pre-serialized bytes (for deserialization benchmarks) ---

    private static final byte[] pbMdSerialized = buildProtobufMessageMetadata().toByteArray();
    private static final byte[] pbCmdSerialized = buildProtobufBaseCommand().toByteArray();

    public PulsarApiBenchmark() {
        // Fill lightproto MessageMetadata
        fillLightProtoMessageMetadata(lpMessageMetadata);
        mdSerialized = lpMessageMetadata.toByteArray();
        mdSerializeByteBuf = PooledByteBufAllocator.DEFAULT.buffer(mdSerialized.length);
        mdSerializeByteBuf.writeBytes(mdSerialized);

        // Fill lightproto BaseCommand
        fillLightProtoBaseCommand(lpBaseCommand);
        cmdSerialized = lpBaseCommand.toByteArray();
        cmdSerializeByteBuf = PooledByteBufAllocator.DEFAULT.buffer(cmdSerialized.length);
        cmdSerializeByteBuf.writeBytes(cmdSerialized);
    }

    // --- LightProto fill methods ---

    private static void fillLightProtoMessageMetadata(
            org.apache.pulsar.common.api.proto.MessageMetadata md) {
        md.setProducerName("producer-1")
                .setSequenceId(12345)
                .setPublishTime(PUBLISH_TIME)
                .setCompression(org.apache.pulsar.common.api.proto.CompressionType.LZ4)
                .setNumMessagesInBatch(10)
                .setEventTime(PUBLISH_TIME)
                .setPartitionKey("partition-key-1");

        md.addProperty().setKey("key1").setValue("value1");
        md.addProperty().setKey("key2").setValue("value2");
        md.addProperty().setKey("key3").setValue("value3");
    }

    private static void fillLightProtoBaseCommand(
            org.apache.pulsar.common.api.proto.BaseCommand cmd) {
        cmd.setType(org.apache.pulsar.common.api.proto.BaseCommand.Type.SEND);
        cmd.setSend()
                .setProducerId(1)
                .setSequenceId(12345)
                .setNumMessages(10)
                .setHighestSequenceId(12355)
                .setIsChunk(false);
    }

    // --- Google Protobuf build methods ---

    private static org.apache.pulsar.common.api.proto.PulsarApi.MessageMetadata buildProtobufMessageMetadata() {
        return org.apache.pulsar.common.api.proto.PulsarApi.MessageMetadata.newBuilder()
                .setProducerName("producer-1")
                .setSequenceId(12345)
                .setPublishTime(PUBLISH_TIME)
                .setCompression(org.apache.pulsar.common.api.proto.PulsarApi.CompressionType.LZ4)
                .setNumMessagesInBatch(10)
                .setEventTime(PUBLISH_TIME)
                .setPartitionKey("partition-key-1")
                .addProperties(org.apache.pulsar.common.api.proto.PulsarApi.KeyValue.newBuilder()
                        .setKey("key1").setValue("value1"))
                .addProperties(org.apache.pulsar.common.api.proto.PulsarApi.KeyValue.newBuilder()
                        .setKey("key2").setValue("value2"))
                .addProperties(org.apache.pulsar.common.api.proto.PulsarApi.KeyValue.newBuilder()
                        .setKey("key3").setValue("value3"))
                .build();
    }

    private static org.apache.pulsar.common.api.proto.PulsarApi.BaseCommand buildProtobufBaseCommand() {
        return org.apache.pulsar.common.api.proto.PulsarApi.BaseCommand.newBuilder()
                .setType(org.apache.pulsar.common.api.proto.PulsarApi.BaseCommand.Type.SEND)
                .setSend(org.apache.pulsar.common.api.proto.PulsarApi.CommandSend.newBuilder()
                        .setProducerId(1)
                        .setSequenceId(12345)
                        .setNumMessages(10)
                        .setHighestSequenceId(12355)
                        .setIsChunk(false))
                .build();
    }

    // ========== MessageMetadata Benchmarks ==========

    @Benchmark
    public void protobufSerializeMessageMetadata(Blackhole bh) throws Exception {
        org.apache.pulsar.common.api.proto.PulsarApi.MessageMetadata md = buildProtobufMessageMetadata();
        CodedOutputStream s = CodedOutputStream.newInstance(data);
        md.writeTo(s);
        bh.consume(s);
    }

    @Benchmark
    public void lightProtoSerializeMessageMetadata(Blackhole bh) {
        lpMessageMetadata.clear();
        fillLightProtoMessageMetadata(lpMessageMetadata);
        lpMessageMetadata.writeTo(buffer);
        buffer.clear();
        bh.consume(buffer);
    }

    @Benchmark
    public void protobufDeserializeMessageMetadata(Blackhole bh) throws Exception {
        org.apache.pulsar.common.api.proto.PulsarApi.MessageMetadata md =
                org.apache.pulsar.common.api.proto.PulsarApi.MessageMetadata.newBuilder()
                        .mergeFrom(pbMdSerialized).build();
        bh.consume(md);
    }

    @Benchmark
    public void lightProtoDeserializeMessageMetadata(Blackhole bh) {
        lpMessageMetadata.parseFrom(mdSerializeByteBuf, mdSerializeByteBuf.readableBytes());
        mdSerializeByteBuf.resetReaderIndex();
        bh.consume(lpMessageMetadata);
    }

    // ========== BaseCommand + CommandSend Benchmarks ==========

    @Benchmark
    public void protobufSerializeBaseCommand(Blackhole bh) throws Exception {
        org.apache.pulsar.common.api.proto.PulsarApi.BaseCommand cmd = buildProtobufBaseCommand();
        CodedOutputStream s = CodedOutputStream.newInstance(data);
        cmd.writeTo(s);
        bh.consume(s);
    }

    @Benchmark
    public void lightProtoSerializeBaseCommand(Blackhole bh) {
        lpBaseCommand.clear();
        fillLightProtoBaseCommand(lpBaseCommand);
        lpBaseCommand.writeTo(buffer);
        buffer.clear();
        bh.consume(buffer);
    }

    @Benchmark
    public void protobufDeserializeBaseCommand(Blackhole bh) throws Exception {
        org.apache.pulsar.common.api.proto.PulsarApi.BaseCommand cmd =
                org.apache.pulsar.common.api.proto.PulsarApi.BaseCommand.newBuilder()
                        .mergeFrom(pbCmdSerialized).build();
        bh.consume(cmd);
    }

    @Benchmark
    public void lightProtoDeserializeBaseCommand(Blackhole bh) {
        lpBaseCommand.parseFrom(cmdSerializeByteBuf, cmdSerializeByteBuf.readableBytes());
        cmdSerializeByteBuf.resetReaderIndex();
        bh.consume(lpBaseCommand);
    }
}
