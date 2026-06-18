package com.hpcache.bench;

import java.util.concurrent.ThreadLocalRandom;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.infra.Blackhole;

import com.hpcache.sim.MarketDataSimulator;

public class ReadHeavyBenchmark extends BenchmarkBase {
    
    @Benchmark
    public void readHeavy(Blackhole bh, ThreadState ts) {
        int r = ThreadLocalRandom.current().nextInt(keyUniverse.length * 100);
        String key = keyUniverse[r / 100];
        MarketDataSimulator simulator = ts.simulator;

        long start = System.nanoTime();
        if ((r % 100) < 95) {
            bh.consume(cache.get(key));
        } else {
            cache.put(key, simulator.nextTick());
        }
        latencyHistogram.recordValue(System.nanoTime() - start);
        operationCount.incrementAndGet();
    }
}
 