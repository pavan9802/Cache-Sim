package com.hpcache.builder;

import java.util.concurrent.TimeUnit;

import com.hpcache.api.Cache;
import com.hpcache.api.RemovalListener;
import com.hpcache.impl.LRUCache;

/**
 * Fluent builder — the only supported way to construct a {@link Cache}.
 */
public final class CacheBuilder<K, V> {
    private static final long DEFAULT_CLEANUP_INTERVAL_MS = 1000L;

    private int maxSize;
    private boolean maxSizeSet = false;
    private long ttlMs;
    private boolean ttlSet = false;
    private long cleanupIntervalMs = DEFAULT_CLEANUP_INTERVAL_MS;
    // no-op in Phase 1 — LRUCache always records stats; flag exists for forward API
    // compatibility.
    private boolean recordStats = false;
    private RemovalListener<K, V> removalListener;

    private CacheBuilder() {

    }

    public static <K, V> CacheBuilder<K, V> newBuilder() {
        return new CacheBuilder<>();
    }

    public CacheBuilder<K, V> maxSize(int maxSize) {
        this.maxSize = maxSize;
        this.maxSizeSet = true;
        return this;
    }

    public CacheBuilder<K, V> ttl(long duration, TimeUnit unit) {
        this.ttlMs = unit.toMillis(duration);
        this.ttlSet = true;
        return this;
    }

    public CacheBuilder<K, V> cleanupInterval(long duration, TimeUnit unit) {
        this.cleanupIntervalMs = unit.toMillis(duration);
        return this;
    }

    public CacheBuilder<K, V> recordStats() {
        this.recordStats = true;
        return this;
    }

    public CacheBuilder<K, V> removalListener(RemovalListener<K, V> removalListener) {
        this.removalListener = removalListener;
        return this;
    }

    public Cache<K, V> build() {
        if (!maxSizeSet) {
            throw new IllegalStateException("maxSize is required");
        }
        if (maxSize <= 0) {
            throw new IllegalStateException("maxSize must be > 0, got: " + maxSize);
        }
        if (ttlSet && ttlMs <= 0) {
            throw new IllegalStateException("ttl must be > 0, got: " + ttlMs);
        }
        if (cleanupIntervalMs <= 0) {
            throw new IllegalStateException("cleanupInterval must be > 0, got: " + cleanupIntervalMs);
        }
        long effectiveTtlMs = ttlSet ? ttlMs : -1L;
        return new LRUCache<>(maxSize, effectiveTtlMs, removalListener, cleanupIntervalMs);
    }
}
