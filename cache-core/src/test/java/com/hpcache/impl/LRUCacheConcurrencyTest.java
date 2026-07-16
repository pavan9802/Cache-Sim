package com.hpcache.impl;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.RepeatedTest;

class LRUCacheConcurrencyTest {

    private LRUCache<String, Integer> cache;

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @RepeatedTest(50)
    void concurrentWritesFromManyThreadsCauseNoCorruption() throws InterruptedException {
        cache = new LRUCache<>(1000, -1);
        int threadCount = 16;
        int entriesPerThread = 10_000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger failures = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            for (int t = 0; t < threadCount; t++) {
                int threadId = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < entriesPerThread; i++) {
                            cache.put("t" + threadId + "-k" + i, i);
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "threads did not finish in time");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, failures.get());
        assertTrue(cache.size() <= 1000);
    }

    @RepeatedTest(50)
    void ttlExpiryVisibleToAllReaderThreads() throws InterruptedException {
        cache = new LRUCache<>(100, -1);
        cache.put("key", 1, 100L);
        Thread.sleep(200);
        int readerCount = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(readerCount);
        AtomicInteger staleReads = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(readerCount);
        try {
            for (int i = 0; i < readerCount; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (cache.get("key") != null) {
                            staleReads.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, staleReads.get());
    }

    @RepeatedTest(100)
    void evictionCountIsExactUnderConcurrentFill() throws InterruptedException {
        int maxSize = 1000;
        int overflow = 1000;
        cache = new LRUCache<>(maxSize, -1);
        int threadCount = 8;
        int totalPuts = maxSize + overflow;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            for (int t = 0; t < threadCount; t++) {
                int threadId = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = threadId; i < totalPuts; i += threadCount) {
                            cache.put("k" + i, i);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(overflow, cache.stats().evictionCount());
        assertEquals(0, cache.stats().expirationCount());
    }

    @RepeatedTest(20)
    void readersAndWritersOnSameKeyDoNotDeadlock() {
        cache = new LRUCache<>(10, -1);
        cache.put("shared", 0);
        int readerCount = 8;
        int writerCount = 4;
        long durationMs = 5000;
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(readerCount + writerCount);
            ExecutorService pool = Executors.newFixedThreadPool(readerCount + writerCount);
            try {
                long deadline = System.currentTimeMillis() + durationMs;
                for (int i = 0; i < readerCount; i++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            while (System.currentTimeMillis() < deadline) {
                                cache.get("shared");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                for (int i = 0; i < writerCount; i++) {
                    int writerId = i;
                    pool.submit(() -> {
                        try {
                            start.await();
                            int counter = 0;
                            while (System.currentTimeMillis() < deadline) {
                                cache.put("shared", writerId * 1_000_000 + counter++);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                done.await(durationMs + 5000, TimeUnit.MILLISECONDS);
            } finally {
                pool.shutdownNow();
            }
        });
    }
}
