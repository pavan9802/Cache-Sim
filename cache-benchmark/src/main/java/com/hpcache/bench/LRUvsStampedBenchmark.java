package com.hpcache.bench;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import com.hpcache.api.Cache;
import com.hpcache.impl.CacheBuilder;
import com.hpcache.sim.MarketDataSimulator;
import com.hpcache.sim.MarketEvent;

/**
 * Head-to-head LRU vs StampedCache comparison for the Phase 2B gate.
 *
 * <p>Uses @Param to switch implementations in the same JVM so JIT state is comparable.
 * Workload is 95% reads / 5% writes (optimistic-read sweet spot).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class LRUvsStampedBenchmark {

    private static final int KEY_UNIVERSE_SIZE = 10_000;
    private static final int CACHE_SIZE = (int) (KEY_UNIVERSE_SIZE * 0.8);

    @Param({"lru", "stamped"})
    public String cacheType;

    private Cache<String, MarketEvent> cache;
    private String[] keyUniverse;

    @Setup(Level.Trial)
    public void setup() {
        keyUniverse = new String[KEY_UNIVERSE_SIZE];
        for (int i = 0; i < KEY_UNIVERSE_SIZE; i++) {
            keyUniverse[i] = "key-" + i;
        }

        MarketEvent seed = new MarketEvent("SEED", 99.99, 100.01, 0L, System.nanoTime(), 0);

        CacheBuilder<String, MarketEvent> builder = CacheBuilder.<String, MarketEvent>newBuilder()
            .maxSize(CACHE_SIZE);

        if ("stamped".equals(cacheType)) {
            builder = builder.optimisticReads(true);
        }

        cache = builder.build();
        for (int i = 0; i < CACHE_SIZE; i++) {
            cache.put(keyUniverse[i], seed);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        cache.close();
    }

    @State(Scope.Thread)
    public static class SimState {
        MarketDataSimulator simulator;

        @Setup(Level.Trial)
        public void setup() {
            simulator = new MarketDataSimulator(BenchmarkBase.buildSymbols(), 100.0, 0.2);
        }
    }

    @Benchmark
    @Threads(1)
    public Object readHeavy1Thread(SimState ts) {
        return doReadHeavy(ts);
    }

    @Benchmark
    @Threads(4)
    public Object readHeavy4Threads(SimState ts) {
        return doReadHeavy(ts);
    }

    @Benchmark
    @Threads(8)
    public Object readHeavy8Threads(SimState ts) {
        return doReadHeavy(ts);
    }

    @Benchmark
    @Threads(16)
    public Object readHeavy16Threads(SimState ts) {
        return doReadHeavy(ts);
    }

    private Object doReadHeavy(SimState ts) {
        int r = ThreadLocalRandom.current().nextInt(KEY_UNIVERSE_SIZE * 100);
        String key = keyUniverse[r / 100];
        if ((r % 100) < 95) {
            return cache.get(key);
        } else {
            cache.put(key, ts.simulator.nextTick());
            return null;
        }
    }
}
