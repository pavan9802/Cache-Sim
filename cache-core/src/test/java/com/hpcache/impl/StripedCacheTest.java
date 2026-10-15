package com.hpcache.impl;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hpcache.api.Cache;
import com.hpcache.api.CacheStats;

/**
 * Unit tests for {@link StripedCache}.
 *
 * <p>Each test proves a single invariant in isolation — no concurrency here.
 * See {@link StripedCacheConcurrencyTest} for thread-safety and scaling tests.
 */
class StripedCacheTest {

    private Cache<String, String> cache;
    private int cachesize;
    private int stripeCount;
    private int maxSize;
    @BeforeEach
    void setUp() {
        this.stripeCount = 8;
        this.cachesize = 1000;
        this.maxSize = this.cachesize * 2;
        cache = CacheBuilder.<String, String>newBuilder()
            .maxSize(maxSize)
            .stripeCount(stripeCount)
            .build();
    }

    @AfterEach
    void tearDown() {
        cache.close();
    }

    // --- routing correctness ---

    @Test
    void put1000ItemsGetAll_noneAreLost() {
        for(int i = 0; i< cachesize; i++){
            cache.put(String.valueOf(i), "value" + i);
        }
        for(int i = 0; i< cachesize; i++){
            assertEquals("value" + i, cache.get(String.valueOf(i)));
        }
    }

    @Test
    void putSameKeyTwice_secondValueWins() {
        cache.put("testKey", "value1");
        cache.put("testKey", "value2");
        assertEquals("value2", cache.get("testKey"));
    }

    // --- capacity ---

    @Test
    void fillBeyondMaxSize_totalSizeNeverExceedsMaxSize() {
        for(int i = 0; i< maxSize + 1000; i++){
            cache.put("testKey" +i, "value" +i);
        }
        assertTrue(cache.size() <= maxSize);
    }

    // --- invalidation ---

    @Test
    void invalidate_removesOnlyThatKey() {
        cache.put("key", "value");
        cache.put("key2", "value2");
        assertEquals("value", cache.get("key"));
        cache.invalidate("key");
        assertNull(cache.get("key"));
        assertEquals("value2", cache.get("key2"));
    }

    @Test
    void invalidateAll_sizeBecomesZero() {
        for(int i = 0; i< maxSize; i++){
            cache.put("testKey" +i, "value" +i);
        }
        assertTrue(cache.size() > 0);
        cache.invalidateAll();
        assertEquals(0, cache.size());

    }

    // --- containsKey ---

    @Test
    void containsKey_returnsFalseAfterInvalidate() {
        cache.put("key", "value");
        assertTrue(cache.containsKey("key"));
        cache.invalidate("key");
        assertFalse(cache.containsKey("key"));
    }

    @Test
    void containsKey_returnsTrueForPresentKey() {
        cache.put("key", "value");
        assertTrue(cache.containsKey("key"));
    }

    @Test
    void containsKey_returnsFalseForAbsentKey() {
        assertFalse(cache.containsKey("key"));
    }

    // --- stats aggregation ---

    @Test
    void stats_allCountersZeroOnFreshCache() {
        CacheStats stats = cache.stats();
        assertEquals(0, stats.hitCount());
        assertEquals(0, stats.missCount());
        assertEquals(0, stats.evictionCount());
        assertEquals(0, stats.expirationCount());
    }

    @Test
    void stats_hitsAndMissesFromMultipleSegmentsSumCorrectly() {
        // Put 200 keys — spread across all 8 segments via Wang hash
        int keyCount = 200;
        for (int i = 0; i < keyCount; i++) {
            cache.put("key" + i, "value" + i);
        }

        // 200 hits (keys that exist) + 100 misses (keys that don't)
        int hits = keyCount;
        int misses = 100;
        for (int i = 0; i < hits; i++) {
            cache.get("key" + i);
        }
        for (int i = 0; i < misses; i++) {
            cache.get("absent" + i);
        }

        CacheStats stats = cache.stats();
        assertEquals(hits, stats.hitCount());
        assertEquals(misses, stats.missCount());
    }

    @Test
    void stats_evictionCountAggregatesAcrossSegments() {
        // Insert more keys than the cache can hold to force evictions across segments
        int insertCount = maxSize + 500;
        for (int i = 0; i < insertCount; i++) {
            cache.put("key" + i, "value" + i);
        }

        // Whatever was evicted + whatever remains must equal what was inserted
        CacheStats stats = cache.stats();
        assertEquals(insertCount, stats.evictionCount() + cache.size());
    }

    // --- stripe count ---

    @Test
    void stripeCountIsAlwaysAPowerOfTwo() {
        StripedCache<String, String> striped = (StripedCache<String, String>) cache;
        int count = striped.stripeCount();
        assertTrue(count > 0 && (count & (count - 1)) == 0); 
    }

    @Test
    void nonPowerOfTwoStripeCountIsRoundedUp() {
        try(Cache<String, String> c = CacheBuilder.<String, String>newBuilder()
                .maxSize(maxSize)
                .stripeCount(7)
                .build()) {
            assertEquals(8, ((StripedCache<String, String>) c).stripeCount());
        }
        
    }

    // --- null key ---

    @Test
    void getNullKey_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> cache.get(null));
    }

    @Test
    void putNullKey_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> cache.put(null, "value"));
    }

    // --- TTL ---

    @Test
    void perEntryTtlOverride_expiresIndependentlyOfDefault() throws InterruptedException {
        // Cache has no default TTL — only the per-entry override should expire
        Cache<String, String> noDefaultTtlCache = CacheBuilder.<String, String>newBuilder()
            .maxSize(maxSize)
            .stripeCount(stripeCount)
            .build();
        try {
            noDefaultTtlCache.put("expires", "value", 100);
            noDefaultTtlCache.put("permanent", "value");
            Thread.sleep(200);
            assertNull(noDefaultTtlCache.get("expires"));
            assertEquals("value", noDefaultTtlCache.get("permanent"));
        } finally {
            noDefaultTtlCache.close();
        }
    }

    @Test
    void entryExpiresAfterTtl() throws InterruptedException {
        Cache<String, String> ttlCache = CacheBuilder.<String, String>newBuilder()
            .maxSize(maxSize)
            .stripeCount(stripeCount)
            .ttl(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build();
        try {
            ttlCache.put("key", "value");
            assertEquals("value", ttlCache.get("key"));
            Thread.sleep(200);
            assertNull(ttlCache.get("key"));
        } finally {
            ttlCache.close();
        }
    }

    // --- builder ---

    @Test
    void stripeCountBelowTwo_builderThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
            CacheBuilder.<String, String>newBuilder()
                .maxSize(maxSize)
                .stripeCount(1)
                .build()
        );
    }
}
