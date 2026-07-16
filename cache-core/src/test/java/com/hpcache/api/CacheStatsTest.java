package com.hpcache.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class CacheStatsTest {

    @Test
    void hitRateIsZeroWhenNoRequestsRecorded() {
        CacheStats stats = new CacheStats(0, 0, 0, 0, 0, 0, 0);
        assertEquals(0.0, stats.hitRate());
    }

    @Test
    void hitRateComputesFromHitsAndMisses() {
        CacheStats stats = new CacheStats(3, 1, 0, 0, 0, 0, 0);
        assertEquals(0.75, stats.hitRate());
    }

    @Test
    void evictionAndExpirationCountsAreIndependent() {
        CacheStats stats = new CacheStats(0, 0, 5, 2, 0, 0, 0);
        assertEquals(5, stats.evictionCount());
        assertEquals(2, stats.expirationCount());
    }

    @Test
    void averageLoadTimeMsIsZeroWhenNoLoadsRecorded() {
        CacheStats stats = new CacheStats(0, 0, 0, 0, 0, 0, 0);
        assertEquals(0.0, stats.averageLoadTimeMs());
    }
}