package com.hpcache.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentHashMapCacheTest {

    private ConcurrentHashMapCache<String, String> cache;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentHashMapCache<>();
    }

    @Test
    void getMissingKeyReturnsNull() {
        assertNull(cache.get("missing"));
    }

    @Test
    void putThenGetReturnsValue() {
        cache.put("k", "v");
        assertEquals("v", cache.get("k"));
    }

    @Test
    void putWithTtlStoresValue() {
        cache.put("k", "v", 1000L);
        assertEquals("v", cache.get("k"));
    }

    @Test
    void invalidateRemovesEntry() {
        cache.put("k", "v");
        cache.invalidate("k");
        assertNull(cache.get("k"));
    }

    @Test
    void invalidateAllClearsCache() {
        cache.put("a", "1");
        cache.put("b", "2");
        cache.invalidateAll();
        assertEquals(0, cache.size());
    }

    @Test
    void containsKeyReturnsTrueAfterPut() {
        cache.put("k", "v");
        assertTrue(cache.containsKey("k"));
    }

    @Test
    void containsKeyReturnsFalseForMissing() {
        assertFalse(cache.containsKey("missing"));
    }

    @Test
    void sizeReflectsEntryCount() {
        assertEquals(0, cache.size());
        cache.put("a", "1");
        cache.put("b", "2");
        assertEquals(2, cache.size());
    }

    @Test
    void statsReturnsNullPlaceholder() {
        assertNull(cache.stats());
    }

    @Test
    void closeIsIdempotent() {
        assertDoesNotThrow(() -> {
            cache.close();
            cache.close();
        });
    }

    @Test
    void putOverwritesExistingValue() {
        cache.put("k", "v1");
        cache.put("k", "v2");
        assertEquals("v2", cache.get("k"));
    }
}
