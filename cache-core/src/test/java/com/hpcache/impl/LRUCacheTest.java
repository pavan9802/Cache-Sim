package com.hpcache.impl;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

import com.hpcache.api.RemovalCause;

class LRUCacheTest {

    private LRUCache<String, String> cache;

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void evictionCountTracksOnlySizeEvictions() {
        cache = new LRUCache<>(2, -1);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");
        assertEquals(1, cache.stats().evictionCount());
        assertEquals(0, cache.stats().expirationCount());
    }

    @Test
    void expirationCountTracksOnlyTtlExpiry() throws InterruptedException {
        cache = new LRUCache<>(10, -1, null, 50L);
        cache.put("a", "1", 50L);
        Thread.sleep(300);
        assertNull(cache.get("a"));
        assertEquals(0, cache.stats().evictionCount());
        assertEquals(1, cache.stats().expirationCount());
    }

    @Test
    void cleanupIntervalIsConfigurable() throws InterruptedException {
        cache = new LRUCache<>(10, -1, null, 50L);
        cache.put("a", "1", 50L);
        Thread.sleep(300);
        assertEquals(0, cache.size());
    }

    @Test
    void getOnEmptyCacheReturnsNull() {
        cache = new LRUCache<>(10, -1);
        assertNull(cache.get("missing"));
    }

    @Test
    void putThenGetSameKeyReturnsValue() {
        cache = new LRUCache<>(10, -1);
        cache.put("k", "v");
        assertEquals("v", cache.get("k"));
    }

    @Test
    void sizeNeverExceedsMaxSize() {
        cache = new LRUCache<>(3, -1);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");
        cache.put("d", "4");
        assertEquals(3, cache.size());
    }

    @Test
    void leastRecentlyUsedEntryIsEvictedFirst() {
        cache = new LRUCache<>(2, -1);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.get("a");
        cache.put("c", "3");
        assertNull(cache.get("b"));
        assertEquals("1", cache.get("a"));
        assertEquals("3", cache.get("c"));
    }

    @Test
    void ttlExpiryViaGetReturnsNullAfterExpiry() throws InterruptedException {
        cache = new LRUCache<>(10, -1, null, 100_000L);
        cache.put("k", "v", 100L);
        Thread.sleep(200);
        assertNull(cache.get("k"));
    }

    @Test
    void hitCountIncrementsOnHit() {
        cache = new LRUCache<>(10, -1);
        cache.put("k", "v");
        cache.get("k");
        assertEquals(1, cache.stats().hitCount());
    }

    @Test
    void missCountIncrementsOnMissAndExpiredEntry() throws InterruptedException {
        cache = new LRUCache<>(10, -1, null, 100_000L);
        cache.get("missing");
        cache.put("k", "v", 50L);
        Thread.sleep(200);
        cache.get("k");
        assertEquals(2, cache.stats().missCount());
    }

    @Test
    void removalListenerCalledWithSizeCauseOnEviction() {
        List<RemovalCause> causes = new ArrayList<>();
        cache = new LRUCache<>(1, -1, (k, v, cause) -> causes.add(cause));
        cache.put("a", "1");
        cache.put("b", "2");
        assertEquals(List.of(RemovalCause.SIZE), causes);
    }

    @Test
    void removalListenerCalledWithExpiredCauseOnTtlExpiry() throws InterruptedException {
        List<RemovalCause> causes = new ArrayList<>();
        cache = new LRUCache<>(10, -1, (k, v, cause) -> causes.add(cause), 50L);
        cache.put("a", "1", 50L);
        Thread.sleep(300);
        assertEquals(List.of(RemovalCause.EXPIRED), causes);
    }

    @Test
    void closeIsIdempotent() {
        cache = new LRUCache<>(10, -1);
        assertDoesNotThrow(() -> {
            cache.close();
            cache.close();
        });
    }
}
