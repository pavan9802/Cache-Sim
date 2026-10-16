package com.hpcache.impl;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import com.hpcache.api.Cache;
import com.hpcache.api.CacheStats;
import com.hpcache.api.RemovalCause;

class StampedCacheTest {

    private Cache<String, String> cache;

    @BeforeEach
    void setUp() {
        cache = CacheBuilder.<String, String>newBuilder()
            .maxSize(5)
            .optimisticReads(true)
            .build();
    }

    @AfterEach
    void tearDown() {
        cache.close();
    }

    // -------------------------------------------------------------------------
    // Basic get / put
    // -------------------------------------------------------------------------

    @Test
    void getOnEmptyCacheReturnsNull() {
        assertNull(cache.get("key"));
    }

    @Test
    void putThenGetSameKeyReturnsValue() {
        cache.put("key", "value");
        assertEquals("value", cache.get("key"));
    }

    @Test
    void nullKeyPutThrowsNPE() {
        assertThrows(NullPointerException.class, () -> cache.put(null, "val"));
    }

    @Test
    void nullKeyGetThrowsNPE() {
        assertThrows(NullPointerException.class, () -> cache.get(null));
    }

    // -------------------------------------------------------------------------
    // Size eviction — insertion order (accessOrder=false)
    //
    // StampedCache uses accessOrder=false to avoid LinkedHashMap node-order
    // mutations during optimistic reads. Eviction removes the eldest-INSERTED
    // entry, not the least-recently-ACCESSED one.
    // -------------------------------------------------------------------------

    @Test
    void cacheDoesNotExceedMaxSize() {
        cache.put("key1", "value1");
        cache.put("key2", "value2");
        cache.put("key3", "value3");
        cache.put("key4", "value4");
        cache.put("key5", "value5");
        cache.put("key6", "value6");
        assertEquals(5, cache.size());
    }

    @Test
    void eldestInsertedEntryIsEvictedFirst() {
        // In LRUCache a get() would promote key1 to MRU; here it does nothing.
        // key1 remains the eldest-inserted and is evicted when key6 is added.
        cache.put("key1", "value1");
        cache.put("key2", "value2");
        cache.put("key3", "value3");
        cache.put("key4", "value4");
        cache.put("key5", "value5");
        cache.get("key1"); // no LRU promotion — key1 is still the eldest
        cache.put("key6", "value6");

        assertNull(cache.get("key1"));   // evicted (eldest inserted)
        assertNotNull(cache.get("key2")); // still present
        assertEquals(5, cache.size());
    }

    // -------------------------------------------------------------------------
    // TTL expiry
    // -------------------------------------------------------------------------

    @Test
    void getReturnsNullAfterPerEntryTtlExpires() throws InterruptedException {
        cache.put("key1", "value1", 100);
        Thread.sleep(200);
        assertNull(cache.get("key1"));
    }

    @Test
    void backgroundCleanerRemovesExpiredEntries() throws InterruptedException {
        cache.put("key1", "value1", 100);
        Thread.sleep(2000);
        assertEquals(0, cache.size());
    }

    // -------------------------------------------------------------------------
    // Stats
    // -------------------------------------------------------------------------

    @Test
    void hitCountIncrementsOnCacheHit() {
        cache.put("key1", "value1");
        CacheStats before = cache.stats();
        cache.get("key1");
        CacheStats after = cache.stats();

        assertEquals(0, before.hitCount());
        assertEquals(1, after.hitCount());
    }

    @Test
    void missCountIncrementsOnMissAndExpiredEntry() throws InterruptedException {
        cache.get("key1"); // cold miss
        cache.put("key1", "value1", 100);
        Thread.sleep(101);
        cache.get("key1"); // expired entry — optimistic read returns null

        assertEquals(2, cache.stats().missCount());
    }

    @Test
    void evictionCountIncrementsOnSizeEviction() {
        cache.put("key1", "value1");
        cache.put("key2", "value2");
        cache.put("key3", "value3");
        cache.put("key4", "value4");
        cache.put("key5", "value5");
        cache.put("key6", "value6");

        assertEquals(1, cache.stats().evictionCount());
    }

    // -------------------------------------------------------------------------
    // RemovalListener
    // -------------------------------------------------------------------------

    @Test
    void removalListenerCalledWithSizeCauseOnEviction() {
        AtomicReference<String> evictedKey  = new AtomicReference<>();
        AtomicReference<RemovalCause> cause = new AtomicReference<>();

        Cache<String, String> c = CacheBuilder.<String, String>newBuilder()
            .maxSize(3)
            .optimisticReads(true)
            .removalListener((k, v, rc) -> { evictedKey.set(k); cause.set(rc); })
            .build();

        try {
            c.put("a", "1");
            c.put("b", "2");
            c.put("c", "3");
            c.put("d", "4"); // evicts "a" (eldest inserted)

            assertEquals(RemovalCause.SIZE, cause.get());
            assertEquals("a", evictedKey.get());
        } finally {
            c.close();
        }
    }

    @Test
    void removalListenerCalledWithExpiredCauseByBackgroundCleaner() throws InterruptedException {
        // StampedCache.get() on an expired entry returns null but does NOT call the
        // removal listener — only the background cleaner does. Use a short cleanup
        // interval so the sweep fires quickly.
        AtomicReference<RemovalCause> cause = new AtomicReference<>();

        Cache<String, String> c = CacheBuilder.<String, String>newBuilder()
            .maxSize(10)
            .optimisticReads(true)
            .cleanupInterval(100, TimeUnit.MILLISECONDS)
            .removalListener((k, v, rc) -> cause.set(rc))
            .build();

        try {
            c.put("key", "value", 50); // 50ms TTL
            Thread.sleep(400);         // wait for TTL + at least one cleaner sweep

            assertEquals(RemovalCause.EXPIRED, cause.get());
        } finally {
            c.close();
        }
    }

    // -------------------------------------------------------------------------
    // Optimistic read fallback — correctness under concurrent writes
    // -------------------------------------------------------------------------

    @RepeatedTest(100)
    void getReturnsNullOrValidValueAfterConcurrentPuts() throws InterruptedException {
        int threadCount = 100;
        Set<String> validValues = ConcurrentHashMap.newKeySet();
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            String v = "value-" + i;
            validValues.add(v);
            Thread.ofVirtual().start(() -> {
                ready.countDown();
                try {
                    start.await();
                    cache.put("shared-key", v);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await(10, TimeUnit.SECONDS);

        String result = cache.get("shared-key");
        assertTrue(result == null || validValues.contains(result),
            "get() returned a value that was never put: " + result);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Test
    void closeTwiceDoesNotThrow() {
        cache.close();
        cache.close();
    }

    // -------------------------------------------------------------------------
    // CacheBuilder validation
    // -------------------------------------------------------------------------

    @Test
    void builderThrowsWhenMaxSizeIsZero() {
        assertThrows(IllegalStateException.class, () ->
            CacheBuilder.newBuilder().maxSize(0).optimisticReads(true).build());
    }

    @Test
    void builderThrowsWhenMaxSizeIsNegative() {
        assertThrows(IllegalStateException.class, () ->
            CacheBuilder.newBuilder().maxSize(-1).optimisticReads(true).build());
    }

    @Test
    void builderThrowsWhenMaxSizeNotSet() {
        assertThrows(IllegalStateException.class, () ->
            CacheBuilder.newBuilder().optimisticReads(true).build());
    }

    @Test
    void builderThrowsWhenTtlIsNegative() {
        assertThrows(IllegalStateException.class, () ->
            CacheBuilder.newBuilder().maxSize(10).optimisticReads(true)
                .ttl(-1, TimeUnit.SECONDS).build());
    }

    @Test
    void builderThrowsWhenTtlIsZero() {
        assertThrows(IllegalStateException.class, () ->
            CacheBuilder.newBuilder().maxSize(10).optimisticReads(true)
                .ttl(0, TimeUnit.SECONDS).build());
    }

    @Test
    void customCleanupIntervalIsAppliedToBuiltCache() throws InterruptedException {
        Cache<String, String> c = CacheBuilder.<String, String>newBuilder()
            .maxSize(10)
            .optimisticReads(true)
            .ttl(50, TimeUnit.MILLISECONDS)
            .cleanupInterval(100, TimeUnit.MILLISECONDS)
            .build();
        try {
            c.put("k", "v");
            assertEquals("v", c.get("k"));
            Thread.sleep(300);
            assertEquals(0, c.size());
        } finally {
            c.close();
        }
    }
}
