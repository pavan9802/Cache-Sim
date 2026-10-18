package com.hpcache.impl;

import java.util.concurrent.CopyOnWriteArrayList;
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

import com.hpcache.api.CacheLoadException;
import com.hpcache.api.LoadingCache;

/**
 * Concurrency tests for {@link LoadingCacheImpl} thundering herd prevention.
 *
 * <p>Repetition counts per project spec:
 * <ul>
 *   <li>Critical paths (thundering herd): {@code @RepeatedTest(100)}
 *   <li>Thread-safety: {@code @RepeatedTest(50)}
 * </ul>
 *
 * <p>A single failure in any run is a failed gate — race conditions are non-deterministic.
 */
class LoadingCacheConcurrencyTest {

    private static final int THREAD_COUNT = 16;

    private LoadingCache<String, String> cache;

    @BeforeEach
    void setUp() {
        cache = CacheBuilder.<String, String>newBuilder()
            .maxSize(100)
            .buildLoadingCache();
    }

    @AfterEach
    void tearDown() {
        cache.close();
    }

    // -------------------------------------------------------------------------
    // Test 1 — Thundering herd: loader called exactly once for simultaneous misses
    // -------------------------------------------------------------------------

    @RepeatedTest(100)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void thunderingHerdLoaderCalledExactlyOnce() throws InterruptedException {
        AtomicInteger loaderCalls = new AtomicInteger(0);

        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(THREAD_COUNT);
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    cache.get("key", k -> { loaderCalls.incrementAndGet(); return "value"; });
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS), "threads did not finish in time");
        pool.shutdown();

        assertEquals(1, loaderCalls.get(),
            "thundering herd prevention failed: loader called " + loaderCalls.get() + " times for " + THREAD_COUNT + " concurrent misses");
    }

    // -------------------------------------------------------------------------
    // Test 2 — All 16 threads receive the same correct loaded value (no nulls)
    // -------------------------------------------------------------------------

    @RepeatedTest(100)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void thunderingHerdAllThreadsReceiveCorrectValue() throws InterruptedException {
        CopyOnWriteArrayList<String> results = new CopyOnWriteArrayList<>();

        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(THREAD_COUNT);
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    results.add(cache.get("key", k -> "loaded-value"));
                } catch (Exception e) {
                    results.add(null);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS), "threads did not finish in time");
        pool.shutdown();

        assertEquals(THREAD_COUNT, results.size());
        assertTrue(results.stream().allMatch("loaded-value"::equals),
            "all threads must receive the correct loaded value, none null — got: " + results);
    }

    // -------------------------------------------------------------------------
    // Test 3 — Loader throws: all waiting threads receive CacheLoadException
    // -------------------------------------------------------------------------

    @RepeatedTest(50)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void loaderThrowsBroadcastsExceptionToAllWaitingThreads() throws InterruptedException {
        AtomicInteger exceptionCount = new AtomicInteger(0);

        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(THREAD_COUNT);
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    cache.get("key", k -> { throw new RuntimeException("backend down"); });
                } catch (CacheLoadException e) {
                    exceptionCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS), "threads did not finish in time");
        pool.shutdown();

        assertEquals(THREAD_COUNT, exceptionCount.get(),
            "every thread that called get() on a failing load must receive CacheLoadException");
    }

    // -------------------------------------------------------------------------
    // Test 4 — Different keys load concurrently: loader called exactly once per key
    // -------------------------------------------------------------------------

    @RepeatedTest(50)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void differentKeysConcurrentlyLoadedEachExactlyOnce() throws InterruptedException {
        AtomicInteger[] callsPerKey = new AtomicInteger[THREAD_COUNT];
        for (int i = 0; i < THREAD_COUNT; i++) {
            callsPerKey[i] = new AtomicInteger(0);
        }

        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(THREAD_COUNT);
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    cache.get("key-" + idx, k -> {
                        callsPerKey[idx].incrementAndGet();
                        return "value-" + idx;
                    });
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS), "threads did not finish in time");
        pool.shutdown();

        for (int i = 0; i < THREAD_COUNT; i++) {
            assertEquals(1, callsPerKey[i].get(),
                "loader for key-" + i + " must be called exactly once");
        }
    }
}
