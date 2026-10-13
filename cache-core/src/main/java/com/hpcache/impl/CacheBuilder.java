package com.hpcache.impl;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.hpcache.api.Cache;
import com.hpcache.api.RemovalListener;

/**
 * Fluent builder for {@link Cache} instances.
 *
 * <p>This is the only supported way to construct a cache. Direct instantiation of
 * implementation classes is package-private and subject to change across phases.
 *
 * <p>Usage:
 * <pre>{@code
 * Cache<String, MarketEvent> cache = CacheBuilder.<String, MarketEvent>newBuilder()
 *     .maxSize(10_000)
 *     .ttl(30, TimeUnit.SECONDS)
 *     .recordStats()
 *     .removalListener((key, value, cause) -> log.info("Evicted: {}", key))
 *     .build();
 * }</pre>
 *
 * @param <K> key type — must implement {@code hashCode()} and {@code equals()}
 * @param <V> value type
 */
public final class CacheBuilder<K, V> {

    private static final long DEFAULT_CLEANUP_INTERVAL_MS = 1_000L;

    private int maxSize = -1;
    private long ttlMs = 0;                      // 0 means no expiry
    private boolean ttlSet = false;
    private long cleanupIntervalMs = DEFAULT_CLEANUP_INTERVAL_MS;
    @SuppressWarnings("unused")                  // no-op in Phase 1; LRUCache always records stats
    private boolean recordStats = false;
    private RemovalListener<K, V> removalListener;

    private CacheBuilder() {}

    /**
     * Returns a new builder. The type parameters are inferred from the call site
     * or can be provided explicitly: {@code CacheBuilder.<String, Event>newBuilder()}.
     */
    public static <K, V> CacheBuilder<K, V> newBuilder() {
        return new CacheBuilder<>();
    }

    /**
     * Sets the maximum number of entries the cache may hold.
     *
     * <p>When the cache reaches this limit, the least-recently-used entry is evicted
     * synchronously on the next {@code put}.
     *
     * @param maxSize maximum entry count; must be &gt; 0
     * @return this builder (for chaining)
     */
    public CacheBuilder<K, V> maxSize(int maxSize) {
        this.maxSize = maxSize;
        return this;
    }

    /**
     * Sets the default time-to-live for entries added via {@code put(key, value)}.
     *
     * <p>Entries added with {@code put(key, value, ttlMs)} use a per-entry override instead.
     * If this method is never called, entries do not expire.
     *
     * @param duration TTL duration; must be &gt; 0
     * @param unit     time unit for {@code duration}
     * @return this builder (for chaining)
     */
    public CacheBuilder<K, V> ttl(long duration, TimeUnit unit) {
        this.ttlMs = unit.toMillis(duration);
        this.ttlSet = true;
        return this;
    }

    /**
     * Sets the interval at which the background cleaner sweeps for expired entries.
     *
     * <p>Defaults to 1 second. A shorter interval reduces the window during which expired
     * entries occupy memory, at the cost of more frequent background sweeps.
     *
     * @param duration interval duration; must be &gt; 0
     * @param unit     time unit for {@code duration}
     * @return this builder (for chaining)
     */
    public CacheBuilder<K, V> cleanupInterval(long duration, TimeUnit unit) {
        this.cleanupIntervalMs = unit.toMillis(duration);
        return this;
    }

    /**
     * Enables cache statistics collection (hit rate, miss rate, eviction count).
     *
     * <p>In Phase 1 all {@link LRUCache} instances collect stats unconditionally, so this
     * method is a no-op for now. It is retained so call sites do not need to change in
     * later phases where stats tracking carries a measurable overhead and can be disabled.
     *
     * @return this builder (for chaining)
     */
    public CacheBuilder<K, V> recordStats() {
        this.recordStats = true;
        return this;
    }

    /**
     * Registers a callback invoked whenever an entry is evicted, expired, or explicitly
     * removed via {@link Cache#invalidate} or {@link Cache#invalidateAll}.
     *
     * @param listener removal callback; {@code null} clears any previously registered listener
     * @return this builder (for chaining)
     */
    public CacheBuilder<K, V> removalListener(RemovalListener<K, V> listener) {
        this.removalListener = listener;
        return this;
    }

    /**
     * Validates the configuration and constructs the cache.
     *
     * <p>The current implementation always returns an {@link LRUCache}. Later phases will
     * select among {@code StripedCache}, {@code StampedCache}, and {@code TinyLFUCache}
     * based on additional builder flags.
     *
     * @return a new, empty {@link Cache} instance
     * @throws IllegalStateException if {@code maxSize} was not set or is &lt;= 0,
     *                               or if a TTL was set but is &lt;= 0
     */
    public Cache<K, V> build() {
        if (maxSize <= 0) {
            throw new IllegalStateException(
                "maxSize must be > 0 (was " + maxSize + "). Call .maxSize(n) before .build().");
        }
        if (ttlSet && ttlMs <= 0) {
            throw new IllegalStateException(
                "TTL must be > 0 ms (was " + ttlMs + " ms). Pass a positive duration to .ttl().");
        }
        if (cleanupIntervalMs <= 0) {
            throw new IllegalStateException(
                "cleanupInterval must be > 0 ms (was " + cleanupIntervalMs + " ms).");
        }

        return new LRUCache<>(
            maxSize,
            ttlMs,
            removalListener,
            Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("cache-cleaner").factory()
            ),
            cleanupIntervalMs
        );
    }
}
