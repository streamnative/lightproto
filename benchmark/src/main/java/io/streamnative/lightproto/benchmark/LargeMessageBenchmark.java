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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.streamnative.lightproto.tests.B;
import io.streamnative.lightproto.tests.Repeated;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.common.api.proto.BaseCommand;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Serialization of messages well above the small-message hot path, into pooled
 * direct buffers (the Pulsar case): the topic-list shape at several sizes and a
 * large bytes payload. Sizes straddle the candidate scratch/chunk thresholds.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 3, time = 2)
@Fork(value = 1)
public class LargeMessageBenchmark {

    private static BaseCommand topicList(int topics) {
        List<String> list = new ArrayList<>(topics);
        String base = "persistent://public/default/" + "t".repeat(520) + "-";
        for (int i = 0; i < topics; i++) {
            list.add(base + i);
        }
        BaseCommand cmd = new BaseCommand().setType(BaseCommand.Type.GET_TOPICS_OF_NAMESPACE_RESPONSE);
        cmd.setGetTopicsOfNamespaceResponse().setRequestId(42).addAllTopics(list);
        cmd.getSerializedSize();
        return cmd;
    }

    private static ByteBuf directFor(int size) {
        return PooledByteBufAllocator.DEFAULT.directBuffer(size);
    }

    /** Varint-dense shape (no bulk data): NIO's adversarial case. */
    private static Repeated denseVarints(int count) {
        Repeated r = new Repeated();
        for (int i = 0; i < count; i++) {
            r.addXInt64(i * 1000003L + 17);
        }
        r.getSerializedSize();
        return r;
    }

    // Sub-4 KB points to locate the array-scratch / NIO crossover
    private final BaseCommand topics600B = topicList(1);
    private final BaseCommand topics1KB = topicList(2);
    private final BaseCommand topics2KB = topicList(4);
    private final BaseCommand topics3KB = topicList(6);
    private final Repeated dense2KB = denseVarints(300);
    private final Repeated dense8KB = denseVarints(1200);
    private final ByteBuf buf600B = directFor(topics600B.getSerializedSize());
    private final ByteBuf buf1KB = directFor(topics1KB.getSerializedSize());
    private final ByteBuf buf2KB = directFor(topics2KB.getSerializedSize());
    private final ByteBuf buf3KB = directFor(topics3KB.getSerializedSize());
    private final ByteBuf bufDense2KB = directFor(dense2KB.getSerializedSize());
    private final ByteBuf bufDense8KB = directFor(dense8KB.getSerializedSize());

    private final BaseCommand topics6KB = topicList(11);      // ~6 KB: just above a 4 KB chunk
    private final BaseCommand topics16KB = topicList(28);     // ~16 KB
    private final BaseCommand topics100KB = topicList(180);   // ~100 KB
    private final BaseCommand topics4MB = topicList(8192);    // ~4.6 MB: the Pulsar proxy test shape
    private final B bytes2MB;

    private final ByteBuf buf6KB = directFor(topics6KB.getSerializedSize());
    private final ByteBuf buf16KB = directFor(topics16KB.getSerializedSize());
    private final ByteBuf buf100KB = directFor(topics100KB.getSerializedSize());
    private final ByteBuf buf4MB = directFor(topics4MB.getSerializedSize());
    private final ByteBuf bufBytes2MB;

    public LargeMessageBenchmark() {
        byte[] payload = new byte[2 * 1024 * 1024];
        new Random(7).nextBytes(payload);
        bytes2MB = new B().setPayload(payload);
        bytes2MB.getSerializedSize();
        bufBytes2MB = directFor(bytes2MB.getSerializedSize());
    }

    @Benchmark
    public void topicList6KB(Blackhole bh) {
        buf6KB.clear();
        bh.consume(topics6KB.writeTo(buf6KB));
    }

    @Benchmark
    public void topicList16KB(Blackhole bh) {
        buf16KB.clear();
        bh.consume(topics16KB.writeTo(buf16KB));
    }

    @Benchmark
    public void topicList100KB(Blackhole bh) {
        buf100KB.clear();
        bh.consume(topics100KB.writeTo(buf100KB));
    }

    @Benchmark
    public void topicList4MB(Blackhole bh) {
        buf4MB.clear();
        bh.consume(topics4MB.writeTo(buf4MB));
    }

    @Benchmark
    public void bytesPayload2MB(Blackhole bh) {
        bufBytes2MB.clear();
        bh.consume(bytes2MB.writeTo(bufBytes2MB));
    }

    @Benchmark
    public void topicList600B(Blackhole bh) {
        buf600B.clear();
        bh.consume(topics600B.writeTo(buf600B));
    }

    @Benchmark
    public void topicList1KB(Blackhole bh) {
        buf1KB.clear();
        bh.consume(topics1KB.writeTo(buf1KB));
    }

    @Benchmark
    public void topicList2KB(Blackhole bh) {
        buf2KB.clear();
        bh.consume(topics2KB.writeTo(buf2KB));
    }

    @Benchmark
    public void topicList3KB(Blackhole bh) {
        buf3KB.clear();
        bh.consume(topics3KB.writeTo(buf3KB));
    }

    @Benchmark
    public void denseVarints2KB(Blackhole bh) {
        bufDense2KB.clear();
        bh.consume(dense2KB.writeTo(bufDense2KB));
    }

    @Benchmark
    public void denseVarints8KB(Blackhole bh) {
        bufDense8KB.clear();
        bh.consume(dense8KB.writeTo(bufDense8KB));
    }
}
