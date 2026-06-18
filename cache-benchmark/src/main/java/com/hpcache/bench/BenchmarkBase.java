package com.hpcache.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hpcache.api.Cache;
import com.hpcache.impl.ConcurrentHashMapCache;
import com.hpcache.sim.MarketDataSimulator;
import com.hpcache.sim.MarketEvent;
import com.hpcache.sim.ZipfianSymbolSelector;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgs = {
    "-Xms2g", "-Xmx2g", "-XX:+UseG1GC",
    "--add-opens", "java.base/jdk.internal.vm.annotation=ALL-UNNAMED",
    "-XX:-RestrictContended"
})
@Threads(8)
@State(Scope.Benchmark)
public class BenchmarkBase {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkBase.class);
    private static final int KEY_UNIVERSE_SIZE = 10_000;
    private static final int INITIAL_CACHE_SIZE = (int) (KEY_UNIVERSE_SIZE * 0.8);

    protected Cache<String, MarketEvent> cache;
    protected ZipfianSymbolSelector selector;
    protected Histogram latencyHistogram;
    protected AtomicLong operationCount;
    protected String[] keyUniverse;

    @Setup(Level.Trial)
    public void setup() {
        keyUniverse = buildKeyUniverse(KEY_UNIVERSE_SIZE);
        selector = new ZipfianSymbolSelector(Arrays.asList(keyUniverse), 1.0);
        latencyHistogram = new ConcurrentHistogram(TimeUnit.SECONDS.toNanos(10), 3);
        operationCount = new AtomicLong();
        cache = buildCache();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        printHistogram();
        saveResults();
        cache.close();
    }

    private Cache<String, MarketEvent> buildCache() {
        var c = new ConcurrentHashMapCache<String, MarketEvent>();
        MarketEvent seed = new MarketEvent("SEED", 99.99, 100.01, 0L, System.nanoTime(), 0);
        for (int i = 0; i < INITIAL_CACHE_SIZE; i++) {
            c.put(keyUniverse[i], seed);
        }
        return c;
    }

    private void printHistogram() {
        System.out.printf("Latency (ns):%n");
        System.out.printf("  p50  : %,d%n", latencyHistogram.getValueAtPercentile(50));
        System.out.printf("  p75  : %,d%n", latencyHistogram.getValueAtPercentile(75));
        System.out.printf("  p95  : %,d%n", latencyHistogram.getValueAtPercentile(95));
        System.out.printf("  p99  : %,d%n", latencyHistogram.getValueAtPercentile(99));
        System.out.printf("  p99.9: %,d%n", latencyHistogram.getValueAtPercentile(99.9));
        Object stats = cache.stats();
        if (stats != null) {
            System.out.printf("Cache hit rate: %s%n", stats);
        }
    }

    private void saveResults() {
        Path resultsDir = Path.of("results");
        try {
            Files.createDirectories(resultsDir);
            String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path file = resultsDir.resolve("phase-0-" + getClass().getSimpleName() + "-" + timestamp + ".json");
            String json = """
                {
                  "phase": 0,
                  "timestamp": "%s",
                  "latencyNs": {
                    "p50":  %d,
                    "p75":  %d,
                    "p95":  %d,
                    "p99":  %d,
                    "p999": %d
                  },
                  "totalOperations": %d
                }
                """.formatted(
                    timestamp,
                    latencyHistogram.getValueAtPercentile(50),
                    latencyHistogram.getValueAtPercentile(75),
                    latencyHistogram.getValueAtPercentile(95),
                    latencyHistogram.getValueAtPercentile(99),
                    latencyHistogram.getValueAtPercentile(99.9),
                    operationCount.get());
            Files.writeString(file, json);
            System.out.printf("Results saved to %s%n", file.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to save benchmark results", e);
        }
    }

    static List<String> buildSymbols() {
        List<String> symbols = new ArrayList<>(100);
        symbols.add("AAPL");
        symbols.add("MSFT");
        symbols.add("GOOGL");
        for (int i = 4; i <= 100; i++) {
            symbols.add(String.format("SYM%03d", i));
        }
        return symbols;
    }

    private static String[] buildKeyUniverse(int count) {
        List<String> symbols = buildSymbols();
        int bucketsPerSymbol = count / symbols.size();
        String[] keys = new String[count];
        for (int i = 0; i < count; i++) {
            keys[i] = symbols.get(i / bucketsPerSymbol) + ":1m:" + String.format("%02d", i % bucketsPerSymbol);
        }
        return keys;
    }

    /** Per-thread state: MarketDataSimulator is @NotThreadSafe and must not be shared. */
    @State(Scope.Thread)
    public static class ThreadState {
        MarketDataSimulator simulator;

        @Setup(Level.Trial)
        public void setup() {
            simulator = new MarketDataSimulator(BenchmarkBase.buildSymbols(), 100.0, 0.2);
        }
    }
}
