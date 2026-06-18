package com.hpcache.bench;

import java.util.concurrent.ThreadLocalRandom;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.Blackhole;

public class ThreadScalingBenchmark extends BenchmarkBase {

    @Benchmark
    @Threads(1)
    public void readHeavy1Thread(Blackhole bh, ThreadState ts) {
        readHeavyOp(bh, ts);
    }

    @Benchmark
    @Threads(2)
    public void readHeavy2Threads(Blackhole bh, ThreadState ts) {
        readHeavyOp(bh, ts);
    }

    @Benchmark
    @Threads(4)
    public void readHeavy4Threads(Blackhole bh, ThreadState ts) {
        readHeavyOp(bh, ts);
    }

    @Benchmark
    @Threads(8)
    public void readHeavy8Threads(Blackhole bh, ThreadState ts) {
        readHeavyOp(bh, ts);
    }

    @Benchmark
    @Threads(16)
    public void readHeavy16Threads(Blackhole bh, ThreadState ts) {
        readHeavyOp(bh, ts);
    }

    private void readHeavyOp(Blackhole bh, ThreadState ts) {
        int r = ThreadLocalRandom.current().nextInt(keyUniverse.length * 100);
        String key = keyUniverse[r / 100];

        long start = System.nanoTime();
        if ((r % 100) < 95) {
            bh.consume(cache.get(key));
        } else {
            cache.put(key, ts.simulator.nextTick());
        }
        latencyHistogram.recordValue(System.nanoTime() - start);
        operationCount.incrementAndGet();
    }
}
