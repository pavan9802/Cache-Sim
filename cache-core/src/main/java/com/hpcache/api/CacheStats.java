package com.hpcache.api;

public record CacheStats(
    long hitCount,
    long missCount,
    long evictionCount,
    long loadCount,
    long totalLoadTimeNanos,
    long snapshotTimestampMs) 
{
    public double hitRate() {
        long total = hitCount + missCount;
        return total == 0 ? 0.0 : (double) hitCount / total;
    }

    public double missRate() {
        return requestCount() == 0 ? 0.0 : 1.0 - hitRate();
    }

    public double averageLoadTimeMs() {
        return loadCount == 0 ? 0.0 : (double) totalLoadTimeNanos / loadCount / 1_000_000.0;
    }

    public long requestCount() {
        return hitCount + missCount;
    }

    public String formatted() {
        return String.format(
            "CacheStats{hitCount=%d, missCount=%d, evictionCount=%d, loadCount=%d, totalLoadTimeNanos=%d, snapshotTimestampMs=%d, hitRate=%.2f%%, missRate=%.2f%%, averageLoadTime=%.2fms}",
            hitCount,
            missCount,
            evictionCount,
            loadCount,
            totalLoadTimeNanos,
            snapshotTimestampMs,
            hitRate() * 100,
            missRate() * 100,
            averageLoadTimeMs()
        );
    }
}
