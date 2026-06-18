package com.hpcache.bench;

import java.util.concurrent.ThreadLocalRandom;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.infra.Blackhole;

import com.hpcache.sim.MarketDataSimulator;

public class ZipfianBenchmark extends BenchmarkBase {

    @Benchmark
    public void zipfian(Blackhole bh, ThreadState ts) {
        String key = selector.select();
        MarketDataSimulator simulator = ts.simulator;

        long start = System.nanoTime();
        if (ThreadLocalRandom.current().nextInt(100) < 95) {
            bh.consume(cache.get(key));
        } else {
            cache.put(key, simulator.nextTick());
        }
        latencyHistogram.recordValue(System.nanoTime() - start);
        operationCount.incrementAndGet();
    }
}
