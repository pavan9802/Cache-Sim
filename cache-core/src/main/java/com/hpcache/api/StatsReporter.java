package com.hpcache.api;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.HdrHistogram.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.jcip.annotations.ThreadSafe;

@ThreadSafe
public class StatsReporter {

    private static final Logger log = LoggerFactory.getLogger(StatsReporter.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Cache<?, ?> cache;
    private final Histogram latencyHistogram;
    private final ScheduledExecutorService executor;

    public StatsReporter(Cache<?, ?> cache, Histogram latencyHistogram) {
        this.cache = cache;
        this.latencyHistogram = latencyHistogram;
        this.executor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("stats-reporter").factory()
        );
        executor.scheduleAtFixedRate(this::printStats, 5, 5, TimeUnit.SECONDS);
    }

    public void close() {
        executor.shutdown();
    }

    private void printStats() {
        String time = LocalTime.now().format(TIME_FMT);
        Object statsObj = cache.stats();

        log.info("[{}] Cache Stats", time);

        // TODO(Phase 1): cast statsObj to CacheStats and print hit rate, throughput, evictions
        if (statsObj != null) {
            log.info("  Stats:         {}", statsObj);
        }

        // TODO(Phase 1): print p75/p95/p999 latency lines once CacheStats is available
        log.info("  p50 latency:   {} ns", latencyHistogram.getValueAtPercentile(50));
        log.info("  p99 latency:   {} ns", latencyHistogram.getValueAtPercentile(99));

        // TODO(Phase 1): print "Size: N / MAX" once Cache interface exposes maxSize()
        log.info("  Size:          {}", cache.size());
    }
}
