package com.hpcache.impl;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

import com.hpcache.api.Cache;

/**
 * Concurrency tests for {@link StampedCache}.
 *
 * <p>Repetition counts follow the project spec:
 * <ul>
 *   <li>Thread-safety / data corruption: {@code @RepeatedTest(100)}
 *   <li>Deadlock detection: {@code @RepeatedTest(20)} + {@code @Timeout}
 * </ul>
 *
 * <p>A single failure in any run is a failed gate.
 */
class StampedCacheConcurrencyTest {

    private Cache<String, String> cache;

    @BeforeEach
    void setUp() {
        cache = CacheBuilder.<String, String>newBuilder()
            .maxSize(1_000)
            .optimisticReads(true)
            .build();
    }

    @AfterEach
    void tearDown() {
        cache.close();
    }

    /**
     * 8 reader + 4 writer threads running concurrently for 1s.
     *
     * <p>Invariant: every read returns either {@code null} or a value that matches
     * the known put format — never a torn, partially-written, or invented string.
     * This verifies that the optimistic read fallback is correct: a failed
     * {@code validate()} always falls back to a real read lock before returning.
     */
    @RepeatedTest(100)
    void optimisticReadFallback_neverReturnsCorruptedValue() throws InterruptedException {
        int readers    = 8;
        int writers    = 4;
        long durationMs = 1_000;

        CountDownLatch ready  = new CountDownLatch(readers + writers);
        CountDownLatch start  = new CountDownLatch(1);
        CountDownLatch done   = new CountDownLatch(readers + writers);
        AtomicInteger  errors = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(readers + writers);

        for (int i = 0; i < readers; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    long deadline = System.currentTimeMillis() + durationMs;
                    while (System.currentTimeMillis() < deadline) {
                        String key    = "key-" + ThreadLocalRandom.current().nextInt(50);
                        String result = cache.get(key);
                        // result must be null or start with "v-" (the writer prefix)
                        if (result != null && !result.startsWith("v-")) {
                            errors.incrementAndGet();
                        }
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
                    int iteration = 0;
                    while (System.currentTimeMillis() < deadline) {
                        String key = "key-" + (threadId * 10 + (iteration % 10) % 50);
                        cache.put(key, "v-" + threadId + "-" + iteration++);
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
        assertTrue(done.await(30, TimeUnit.SECONDS), "threads did not finish in time");
        pool.shutdown();

        assertEquals(0, errors.get(), "reads returned corrupted or unexpected values");
    }

    /**
     * Verifies that no combination of concurrent reads, writes, and invalidations
     * deadlocks — exercising the non-reentrancy contract of {@link java.util.concurrent.locks.StampedLock}.
     *
     * <p>If any locked section incorrectly calls another method that tries to acquire
     * the same {@code StampedLock}, the test will hang and the 10-second timeout will
     * catch it as a failure.
     */
    @RepeatedTest(20)
    @Timeout(10)
    void noDeadlock_concurrentReadWriteInvalidate() throws InterruptedException {
        int threadCount  = 12;
        long durationMs  = 500;

        CountDownLatch ready  = new CountDownLatch(threadCount);
        CountDownLatch start  = new CountDownLatch(1);
        CountDownLatch done   = new CountDownLatch(threadCount);
        AtomicInteger  errors = new AtomicInteger();
        AtomicBoolean  stop   = new AtomicBoolean(false);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        // 4 readers
        for (int i = 0; i < 4; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    while (!stop.get()) {
                        cache.get("key-" + ThreadLocalRandom.current().nextInt(20));
                        cache.containsKey("key-" + ThreadLocalRandom.current().nextInt(20));
                        cache.size();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        // 4 writers
        for (int i = 0; i < 4; i++) {
            int threadId = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    int iter = 0;
                    while (!stop.get()) {
                        cache.put("key-" + (threadId * 5 + iter++ % 20), "v");
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        // 4 invalidators (mix of single-key and bulk)
        for (int i = 0; i < 4; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    int iter = 0;
                    while (!stop.get()) {
                        if (iter++ % 50 == 0) {
                            cache.invalidateAll();
                        } else {
                            cache.invalidate("key-" + ThreadLocalRandom.current().nextInt(20));
                        }
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
        Thread.sleep(durationMs);
        stop.set(true);
        assertTrue(done.await(8, TimeUnit.SECONDS), "threads did not finish — possible deadlock");
        pool.shutdown();

        assertEquals(0, errors.get());
    }
}
