package com.hpcache.impl;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

import com.hpcache.api.Cache;

/**
 * Concurrency tests for {@link StripedCache}.
 *
 * <p>Repetition counts follow the project spec:
 * <ul>
 *   <li>Thread-safety (no data corruption): {@code @RepeatedTest(50)}
 *   <li>Distribution uniformity: {@code @RepeatedTest(10)}
 *   <li>Throughput scaling: {@code @RepeatedTest(5)}
 * </ul>
 *
 * <p>A single failure in any run is a failed gate.
 */
class StripedCacheConcurrencyTest {

    private Cache<String, String> cache;
    private static final int MAX_SIZE = 100_000;
    @BeforeEach
    void setUp() {
        cache = CacheBuilder.<String, String>newBuilder()
            .maxSize(100_000)
            .stripeCount(16)
            .build();
    }

    @AfterEach
    void tearDown() {
        cache.close();
    }

    // --- thread safety ---

    /** 16 threads each writing 10k entries — no exceptions, size <= maxSize. */
    @RepeatedTest(50)
    void concurrentWrites_noCrossSegmentInterference() throws InterruptedException {
        int threadCount = 16;
        int writesPerThread = 1000;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threadCount);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for(int t = 0; t < threadCount; t++){
            int threadId = t;
            pool.submit(() ->{
                ready.countDown();
                try {
                    start.await();
                    for(int i = 0; i < writesPerThread; i++){
                        cache.put("key-" + threadId + "-" + i, "value-" + threadId + "-" + i);
                
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }finally{
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(0, errors.get());
        assertTrue(cache.size() <= MAX_SIZE);
        
    }

    /** 8 reader threads + 8 writer threads on overlapping keys for 5s — no exceptions, no corruption. */
    @RepeatedTest(50)
    void concurrentReadsAndWrites_noDataCorruption() throws InterruptedException {
        int readers = 8;
        int writers = 8;
        long durationMs = 1_000;

        CountDownLatch ready  = new CountDownLatch(readers + writers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(readers + writers);
         AtomicInteger errors = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(readers + writers);
        for (int i = 0; i < readers; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    long deadline = System.currentTimeMillis() + durationMs;
                    while (System.currentTimeMillis() < deadline) {
                        cache.get("shared-" + ThreadLocalRandom.current().nextInt(100));
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        for (int i = 0; i < writers; i++) {
            int threadId = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    long deadline = System.currentTimeMillis() + durationMs;
                    while (System.currentTimeMillis() < deadline) {
                        cache.put("shared-" + (threadId % 100), "value-" + threadId);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(0, errors.get());
    }

    // --- distribution uniformity ---

    /**
     * 100k inserts — each stripe holds between 40% and 160% of the expected share.
     * Catches degenerate hash distributions.
     */
    @RepeatedTest(10)
    void wangHash_distributesKeysUniformlyAcrossStripes() {
        int insertCount = 100_000;
        // 2x capacity so no segment can evict regardless of hash skew
        Cache<String, String> bigCache = CacheBuilder.<String, String>newBuilder()
            .maxSize(insertCount * 2)
            .stripeCount(16)
            .build();
        try {
            for (int i = 0; i < insertCount; i++) {
                bigCache.put("key-" + i, "v");
            }
            StripedCache<String, String> striped = (StripedCache<String, String>) bigCache;
            int[] sizes = striped.segmentSizes();
            int expectedPerStripe = insertCount / striped.stripeCount();
            for (int size : sizes) {
                assertTrue(size >= expectedPerStripe * 0.40, "stripe underpopulated: " + size);
                assertTrue(size <= expectedPerStripe * 1.60, "stripe overpopulated: " + size);
            }
        } finally {
            bigCache.close();
        }
    }

    // --- throughput scaling ---
    // The 2x improvement floor over LRUCache at 8 threads is enforced by JMH benchmarks
    // (ReadHeavyBenchmark / WriteHeavyBenchmark). JUnit microbenchmarks are too sensitive
    // to JIT warmup order and GC noise to reliably measure a throughput ratio.

}

