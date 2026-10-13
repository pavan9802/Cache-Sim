package com.hpcache.impl;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

import com.hpcache.api.Cache;

/**
 * Concurrency tests for {@link LRUCache}.
 *
 * <p>Repetition counts are set per the project spec:
 * <ul>
 *   <li>Thread-safety (no data corruption): {@code @RepeatedTest(50)}
 *   <li>Critical paths (eviction count accuracy): {@code @RepeatedTest(100)}
 *   <li>Deadlock tests: {@code @RepeatedTest(20)} + {@code @Timeout}
 * </ul>
 *
 * <p>A single failure in any run is a failed gate — race conditions are non-deterministic.
 */
class LRUCacheConcurrencyTest {

    private static final int MAX_SIZE = 1_000;

    private Cache<String, String> cache;

    @BeforeEach
    void setUp() {
        cache = CacheBuilder.<String, String>newBuilder()
            .maxSize(MAX_SIZE)
            .build();
    }

    @AfterEach
    void tearDown() {
        cache.close();
    }

    // -------------------------------------------------------------------------
    // Test 1 — No data corruption under concurrent writes
    // 16 threads each writing 10,000 entries
    // After completion: no exceptions, size() <= maxSize
    // -------------------------------------------------------------------------

    @RepeatedTest(50)
    void noDataCorruptionUnderConcurrentWrites() throws InterruptedException {
        int threadCount = 16;
        int writesPerThread = 10_000;

        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threadCount);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int i = 0; i < writesPerThread; i++) {
                        cache.put("key-" + threadId + "-" + i, "value-" + threadId + "-" + i);
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
        assertTrue(cache.size() <= MAX_SIZE);
    }

    // -------------------------------------------------------------------------
    // Test 2 — TTL expiry is thread-safe
    // put with 100ms TTL, sleep 200ms, 8 threads all reading → all return null
    // -------------------------------------------------------------------------

    @RepeatedTest(50)
    void ttlExpiryIsThreadSafe() throws InterruptedException {
        int threadCount = 8;

        cache.put("key", "value", 100);  // single put before any threads start
        Thread.sleep(200);               // outlast the TTL

        CountDownLatch ready  = new CountDownLatch(threadCount);
        CountDownLatch start  = new CountDownLatch(1);
        CountDownLatch done   = new CountDownLatch(threadCount);
        AtomicInteger nulls   = new AtomicInteger(0);
        AtomicInteger errors  = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (cache.get("key") == null) {
                        nulls.incrementAndGet();
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
        done.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(0, errors.get());
        assertEquals(threadCount, nulls.get(), "All threads must see the expired entry as null");
    }

    // -------------------------------------------------------------------------
    // Test 3 — Eviction count accuracy
    // Fill to maxSize + 1000, assert evictionCount == 1000
    // -------------------------------------------------------------------------

    @RepeatedTest(100)
    void evictionCountIsAccurate() {
        int overflow = 1_000;

        for (int i = 0; i < MAX_SIZE + overflow; i++) {
            cache.put("key-" + i, "value");
        }

        assertEquals(overflow, cache.stats().evictionCount());
    }

    // -------------------------------------------------------------------------
    // Test 4 — No deadlock under concurrent reads and writes
    // 8 reader threads + 4 writer threads hammering the same key for 5 seconds
    // Test must complete within 10 seconds
    // -------------------------------------------------------------------------

    @RepeatedTest(20)
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void noDeadlockUnderConcurrentReadsAndWrites() throws InterruptedException {
        int readers = 8;
        int writers = 4;
        long durationMs = 5_000;

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(readers + writers);

        ExecutorService pool = Executors.newFixedThreadPool(readers + writers);

        for (int i = 0; i < readers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    long deadline = System.currentTimeMillis() + durationMs;
                    while (System.currentTimeMillis() < deadline) {
                        cache.get("hot-key");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        for (int i = 0; i < writers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    long deadline = System.currentTimeMillis() + durationMs;
                    while (System.currentTimeMillis() < deadline) {
                        cache.put("hot-key", "value");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(9, TimeUnit.SECONDS), "Deadlock detected — threads did not finish within 9s");
        pool.shutdown();
    }
}
