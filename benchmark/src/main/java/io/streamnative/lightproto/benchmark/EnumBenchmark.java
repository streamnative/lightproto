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
import io.streamnative.lightproto.tests.BenchmarkResult;
import io.streamnative.lightproto.tests.BenchmarkResults;
import io.streamnative.lightproto.tests.BenchmarkStatus;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Parses and serializes proto3 enum fields with values the enum defines: a batch of 100
 * results with a status each, and the 100 statuses alone in a packed list.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class EnumBenchmark {

    private static final int COUNT = 100;

    // Proto3 doesn't serialize BENCHMARK_OK (0), so only the others are on the wire
    private static final BenchmarkStatus[] STATUSES = {
            BenchmarkStatus.BENCHMARK_NOT_FOUND,
            BenchmarkStatus.BENCHMARK_BAD_VERSION,
            BenchmarkStatus.BENCHMARK_NO_SESSION,
    };

    private final BenchmarkResults results = new BenchmarkResults();
    private final BenchmarkResults statuses = new BenchmarkResults();
    private ByteBuf resultsWire;
    private ByteBuf statusesWire;
    private final ByteBuf out = PooledByteBufAllocator.DEFAULT.directBuffer(4096);

    @Setup
    public void setup() {
        fillResults(results);
        resultsWire = PooledByteBufAllocator.DEFAULT.directBuffer(results.getSerializedSize());
        results.writeTo(resultsWire);

        fillStatuses(statuses);
        statusesWire = PooledByteBufAllocator.DEFAULT.directBuffer(statuses.getSerializedSize());
        statuses.writeTo(statusesWire);
    }

    private static void fillResults(BenchmarkResults r) {
        for (int i = 0; i < COUNT; i++) {
            BenchmarkResult result = r.addResult();
            result.setStatus(STATUSES[i % STATUSES.length]);
            result.setVersion(1000 + i);
        }
    }

    private static void fillStatuses(BenchmarkResults r) {
        for (int i = 0; i < COUNT; i++) {
            r.addStatusCode(STATUSES[i % STATUSES.length]);
        }
    }

    @Benchmark
    public void parseResults(Blackhole bh) {
        results.parseFrom(resultsWire, resultsWire.readableBytes());
        resultsWire.resetReaderIndex();
        bh.consume(results);
    }

    @Benchmark
    public void parseResultsAndGet(Blackhole bh) {
        results.parseFrom(resultsWire, resultsWire.readableBytes());
        resultsWire.resetReaderIndex();
        for (int i = 0; i < results.getResultsCount(); i++) {
            bh.consume(results.getResultAt(i).getStatus());
        }
    }

    @Benchmark
    public void fillAndSerializeResults(Blackhole bh) {
        results.clear();
        fillResults(results);
        out.clear();
        results.writeTo(out);
        bh.consume(out);
    }

    @Benchmark
    public void parseStatuses(Blackhole bh) {
        statuses.parseFrom(statusesWire, statusesWire.readableBytes());
        statusesWire.resetReaderIndex();
        bh.consume(statuses);
    }

    @Benchmark
    public void parseStatusesAndGet(Blackhole bh) {
        statuses.parseFrom(statusesWire, statusesWire.readableBytes());
        statusesWire.resetReaderIndex();
        for (int i = 0; i < statuses.getStatusCodesCount(); i++) {
            bh.consume(statuses.getStatusCodeAt(i));
        }
    }

    @Benchmark
    public void fillAndSerializeStatuses(Blackhole bh) {
        statuses.clear();
        fillStatuses(statuses);
        out.clear();
        statuses.writeTo(out);
        bh.consume(out);
    }
}
