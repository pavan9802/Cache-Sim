package com.hpcache.impl;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hpcache.api.Cache;
import com.hpcache.api.CacheStats;
import com.hpcache.api.RemovalCause;

class LRUCacheTest {

    private Cache<String, String> cache;

    @BeforeEach
    void setUp() {
        cache = CacheBuilder.<String, String>newBuilder()
            .maxSize(5)
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
    void nullKeyPutThrowsNPE(){
        assertThrows(NullPointerException.class, () -> cache.put(null, "val"));
    }

        @Test
    void nullKeyGetThrowsNPE(){
        assertThrows(NullPointerException.class, () -> cache.get(null));
    }
    // -------------------------------------------------------------------------
    // Size eviction
    // -------------------------------------------------------------------------

    @Test
    void cacheDoesNotExceedMaxSize() {
        // Insert maxSize + 1 entries, assert size() == maxSize
        cache.put("key1", "value1");
        cache.put("key2", "value2");
        cache.put("key3", "value3");
        cache.put("key4", "value4");
        cache.put("key5", "value5");
        cache.put("key6", "value6");
        assertEquals(5, cache.size());
    }

    @Test
    void lruEntryIsEvictedFirst() throws InterruptedException {
        // Access entries in a specific order, trigger eviction, verify the
        // least-recently-used key is gone and the others remain
        cache.put("key1", "value1");
        cache.put("key2", "value2");
        cache.put("key3", "value3");
        cache.put("key4", "value4");
        cache.put("key5", "value5");
        cache.get("key1"); // access key1 to make it recently used
        cache.put("key6", "value6"); // this should evict key2 (
        
        assertNull(cache.get("key2"));
        assertEquals(5, cache.size());
    }

    // -------------------------------------------------------------------------
    // TTL expiry
    // -------------------------------------------------------------------------

    @Test
    void getReturnsNullAfterPerEntryTtlExpires() throws InterruptedException {
        // put with 100ms TTL, sleep 200ms, assert get() returns null
        cache.put("key1", "value1", 100);
        Thread.sleep(200);

        assertNull(cache.get("key1"));
    }

    @Test
    void backgroundCleanerRemovesExpiredEntries() throws InterruptedException {
        // put with 100ms TTL, sleep 2s (cleaner fires at 1s), assert size() decreased
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
        CacheStats statsBefore = cache.stats();
        cache.get("key1");
        CacheStats statsAfter = cache.stats();

        assertEquals(statsBefore.hitCount(), 0);
        assertEquals(statsAfter.hitCount(), 1);
    }

    @Test
    void missCountIncrementsOnMissAndExpiredEntry() throws InterruptedException {
        // Verify miss increments on a cold miss AND on a get() of an expired entry
        cache.get("key1"); // cold miss
        cache.put("key1", "value1", 100);
        Thread.sleep(101);
        cache.get("key1"); // expired entry

        CacheStats stats = cache.stats();
        assertEquals(2, stats.missCount());
    }

    @Test
    void evictionCountIncrementsOnSizeEviction() {
        // Fill past maxSize, verify stats().evictionCount() equals overflow amount
        cache.put("key1", "value1");
        cache.put("key2", "value2");
        cache.put("key3", "value3");
        cache.put("key4", "value4");
        cache.put("key5", "value5");
        cache.put("key6", "value6"); // this should evict key1  
        CacheStats stats = cache.stats();
        
        assertEquals(1, stats.evictionCount());
    }

    // -------------------------------------------------------------------------
    // RemovalListener
    // -------------------------------------------------------------------------

    @Test
    void removalListenerCalledWithSizeCauseOnEviction() {
        AtomicReference<String> evictedKey   = new AtomicReference<>();
        AtomicReference<RemovalCause> cause  = new AtomicReference<>();

        Cache<String, String> c = CacheBuilder.<String, String>newBuilder()
            .maxSize(3)
            .removalListener((k, v, rc) -> { evictedKey.set(k); cause.set(rc); })
            .build();

        try {
            c.put("a", "1");
            c.put("b", "2");
            c.put("c", "3");
            c.put("d", "4");  // evicts "a" (LRU)

            assertEquals(RemovalCause.SIZE, cause.get());
            assertEquals("a", evictedKey.get());
        } finally {
            c.close();
        }
    }

    @Test
    void removalListenerCalledWithExpiredCauseOnTtlExpiry() throws InterruptedException {
        AtomicReference<RemovalCause> cause = new AtomicReference<>();

        Cache<String, String> c = CacheBuilder.<String, String>newBuilder()
            .maxSize(10)
            .removalListener((k, v, rc) -> cause.set(rc))
            .build();

        try {
            c.put("key", "value", 100);  // 100ms TTL
            Thread.sleep(200);
            c.get("key");                // lazy expiry fires the listener

            assertEquals(RemovalCause.EXPIRED, cause.get());
        } finally {
            c.close();
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Test
    void closeTwiceDoesNotThrow() {
        // TODO
        cache.close();
        cache.close();
    }

    // -------------------------------------------------------------------------
    // CacheBuilder validation
    // -------------------------------------------------------------------------

    @Test
    void builderThrowsWhenMaxSizeIsZero() {
        assertThrows(IllegalStateException.class, () ->
            CacheBuilder.newBuilder().maxSize(0).build());
    }

    @Test
    void builderThrowsWhenMaxSizeIsNegative() {
        assertThrows(IllegalStateException.class, () ->
            CacheBuilder.newBuilder().maxSize(-1).build());
    }

    @Test
    void builderThrowsWhenMaxSizeNotSet() {
        assertThrows(IllegalStateException.class, () ->
            CacheBuilder.newBuilder().build());
    }

    @Test
    void builderThrowsWhenTtlIsNegative() {
        assertThrows(IllegalStateException.class, () ->
            CacheBuilder.newBuilder().maxSize(10).ttl(-1, TimeUnit.SECONDS).build());
    }

    @Test
    void builderThrowsWhenTtlIsZero() {
        assertThrows(IllegalStateException.class, () ->
            CacheBuilder.newBuilder().maxSize(10).ttl(0, TimeUnit.SECONDS).build());
    }

    @Test
    void customCleanupIntervalIsAppliedToBuiltCache() throws InterruptedException {
        Cache<String, String> c = CacheBuilder.<String, String>newBuilder()
            .maxSize(10)
            .ttl(50, TimeUnit.MILLISECONDS)
            .cleanupInterval(100, TimeUnit.MILLISECONDS)
            .build();
        try {
            c.put("k", "v");
            // Entry is accessible before TTL elapses
            assertEquals("v", c.get("k"));
            // Wait for TTL + one full cleanup interval to pass
            Thread.sleep(300);
            // The background cleaner must have swept the expired entry
            assertEquals(0, c.size());
        } finally {
            c.close();
        }
    }
}
