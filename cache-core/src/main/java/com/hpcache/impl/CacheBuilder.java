package com.hpcache.impl;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.hpcache.api.AsyncLoadingCache;
import com.hpcache.api.Cache;
import com.hpcache.api.LoadingCache;
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
    private int stripeCount = 0;                   // 0 means use LRUCache (no striping)
    private boolean optimisticReads = false;
    private Function<K, V> loader = null;

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
     * Enables lock striping across N independent segments for higher concurrency.
     *
     * <p>When set, {@link #build()} returns a {@link StripedCache} instead of {@link LRUCache}.
     * The actual stripe count is rounded up to the next power of two. If this method is
     * never called, {@link #build()} returns an {@link LRUCache}.
     *
     * @param stripeCount desired number of stripes; must be &gt;= 2
     * @return this builder (for chaining)
     * @throws IllegalArgumentException if {@code stripeCount} is less than 2
     */
    public CacheBuilder<K, V> stripeCount(int stripeCount) {
        if (stripeCount < 2) {
            throw new IllegalArgumentException(
                "stripeCount must be >= 2 (was " + stripeCount + "). Omit stripeCount() to get a plain LRUCache.");
        }
        this.stripeCount = stripeCount;
        return this;
    }

    /**
     * Enables optimistic reads via {@link java.util.concurrent.locks.StampedLock}.
     *
     * <p>When set, {@link #build()} returns a {@link StampedCache} instead of {@link LRUCache}.
     * Combine with {@link #stripeCount(int)} — if both are set, {@code stripeCount} takes
     * precedence and returns a {@link StripedCache} (striped + optimistic is Phase 3+).
     *
     * <p>Trade-off: optimistic reads win under low write rates (&lt;10% writes) and lose
     * under write-heavy workloads (&gt;30% writes) where {@code validate()} fails frequently.
     *
     * @param optimisticReads {@code true} to use {@link StampedCache}
     * @return this builder (for chaining)
     */
    public CacheBuilder<K, V> optimisticReads(boolean optimisticReads) {
        this.optimisticReads = optimisticReads;
        return this;
    }

    /**
     * Sets the loader function invoked by {@link LoadingCache#getOrLoad}.
     *
     * <p>Required when calling {@link #buildLoadingCache()}. Has no effect on {@link #build()}.
     *
     * @param loader function that computes a value for a missing key; must not be {@code null}
     * @return this builder (for chaining)
     */
    public CacheBuilder<K, V> loader(Function<K, V> loader) {
        this.loader = loader;
        return this;
    }

    /**
     * Validates the configuration and constructs a {@link LoadingCache}.
     *
     * <p>Wraps an {@link LRUCache} delegate in a {@link LoadingCacheImpl}. The {@code loader}
     * may be {@code null} if the caller will always use {@link LoadingCache#get(Object, java.util.function.Function)}.
     *
     * @return a new, empty {@link LoadingCache} instance
     * @throws IllegalStateException if {@code maxSize} was not set or is &lt;= 0,
     *                               or if a TTL was set but is &lt;= 0
     */
    public LoadingCache<K, V> buildLoadingCache() {
        if (maxSize <= 0) {
            throw new IllegalStateException(
                "maxSize must be > 0 (was " + maxSize + "). Call .maxSize(n) before .buildLoadingCache().");
        }
        if (ttlSet && ttlMs <= 0) {
            throw new IllegalStateException(
                "TTL must be > 0 ms (was " + ttlMs + " ms). Pass a positive duration to .ttl().");
        }
        if (cleanupIntervalMs <= 0) {
            throw new IllegalStateException(
                "cleanupInterval must be > 0 ms (was " + cleanupIntervalMs + " ms).");
        }
        return new LoadingCacheImpl<>(
            maxSize, ttlMs, loader, removalListener,
            Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("loading-cache-cleaner").factory()),
            cleanupIntervalMs
        );
    }

    /**
     * Validates the configuration and constructs an {@link AsyncLoadingCache}.
     *
     * <p>Wraps an {@link LRUCache} delegate in an {@link AsyncLoadingCacheImpl}. The
     * {@code loader} may be {@code null} if the caller will always use
     * {@link AsyncLoadingCache#getAsync(Object, java.util.function.Function)}.
     *
     * @return a new, empty {@link AsyncLoadingCache} instance
     * @throws IllegalStateException if {@code maxSize} was not set or is &lt;= 0,
     *                               or if a TTL was set but is &lt;= 0
     */
    public AsyncLoadingCache<K, V> buildAsyncLoadingCache() {
        if (maxSize <= 0) {
            throw new IllegalStateException(
                "maxSize must be > 0 (was " + maxSize + "). Call .maxSize(n) before .buildAsyncLoadingCache().");
        }
        if (ttlSet && ttlMs <= 0) {
            throw new IllegalStateException(
                "TTL must be > 0 ms (was " + ttlMs + " ms). Pass a positive duration to .ttl().");
        }
        if (cleanupIntervalMs <= 0) {
            throw new IllegalStateException(
                "cleanupInterval must be > 0 ms (was " + cleanupIntervalMs + " ms).");
        }
        return new AsyncLoadingCacheImpl<>(
            maxSize, ttlMs, loader, removalListener,
            Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("async-loading-cache-cleaner").factory()),
            cleanupIntervalMs
        );
    }

    /**
     * Validates the configuration and constructs the cache.
     *
     * <p>Returns a {@link StripedCache} when {@code stripeCount > 1} was configured,
     * a {@link StampedCache} when {@code optimisticReads(true)} was set,
     * otherwise an {@link LRUCache}.
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

        if (stripeCount > 0) {
            return new StripedCache<>(maxSize, ttlMs, stripeCount, removalListener, cleanupIntervalMs);
        }

        if (optimisticReads) {
            return new StampedCache<>(maxSize, ttlMs, removalListener,
                Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("stamped-cache-cleaner").factory()
                ),
                cleanupIntervalMs);
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
