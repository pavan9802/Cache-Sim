package com.hpcache.impl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import com.hpcache.api.AsyncLoadingCache;
import com.hpcache.api.Cache;
import com.hpcache.api.CacheStats;
import com.hpcache.api.RemovalListener;
import com.hpcache.internal.CacheSupport;
import com.hpcache.internal.entry.CacheEntry;

import net.jcip.annotations.ThreadSafe;

/**
 * Non-blocking read-through cache with refresh-ahead support.
 *
 * <p>Returns {@link CompletableFuture} immediately on every call — callers are never
 * blocked waiting for a load. When an entry is near expiry, a background refresh is
 * triggered so the next caller still finds a warm value (refresh-ahead).
 *
 * <p>Construct via {@link CacheBuilder#buildAsyncLoadingCache()}, not directly.
 */
@ThreadSafe
public class AsyncLoadingCacheImpl<K, V> implements AsyncLoadingCache<K, V> {

    private static final Executor REFRESH_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final Cache<K, V> delegate;
    private final Function<K, V> loader;  // configured loader; null if not set
    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();
    // Nanos remaining on an entry's TTL below which a background refresh is triggered.
    // 0 means refresh-ahead is disabled (no TTL configured).
    private final long refreshThresholdNanos;

    private static final double REFRESH_AHEAD_RATIO = 0.20;

    /**
     * Primary constructor. Accepts a pre-built {@code Cache} delegate, a loader, scheduler,
     * and an explicit refresh-ahead threshold.
     *
     * @param delegate               backing cache that handles eviction, TTL, and locking
     * @param loader                 loader invoked on a miss; may be {@code null}
     * @param refreshThresholdNanos  remaining TTL nanos below which refresh-ahead fires;
     *                               {@code 0} disables refresh-ahead
     */
    AsyncLoadingCacheImpl(Cache<K, V> delegate, Function<K, V> loader, long refreshThresholdNanos) {
        this.delegate = delegate;
        this.loader = loader;
        this.refreshThresholdNanos = refreshThresholdNanos;
    }

    /**
     * Builds and owns an {@link LRUCache} delegate with the given parameters and
     * creates an owned virtual-thread scheduler. Sets refresh-ahead threshold to 20% of TTL.
     *
     * @param maxSize           maximum number of entries; must be &gt; 0
     * @param defaultTtlMs      default TTL in milliseconds; {@code <= 0} means no expiry
     * @param loader            loader invoked on a miss; may be {@code null}
     * @param removalListener   removal callback; {@code null} disables notifications
     * @param cleaner           executor for the LRU background expiry sweep
     * @param cleanupIntervalMs interval between sweeps in milliseconds; must be &gt; 0
     */
    AsyncLoadingCacheImpl(int maxSize, long defaultTtlMs, Function<K, V> loader,
                          RemovalListener<K, V> removalListener,
                          ScheduledExecutorService cleaner, long cleanupIntervalMs) {
        this(new LRUCache<>(maxSize, defaultTtlMs, removalListener, cleaner, cleanupIntervalMs),
             loader,
             defaultTtlMs > 0 ? (long)(defaultTtlMs * 1_000_000L * REFRESH_AHEAD_RATIO) : 0L);
    }

    /**
     * Builds an {@link LRUCache} delegate with an owned virtual-thread cleaner and a
     * 1-second sweep interval.
     *
     * @param maxSize         maximum number of entries; must be &gt; 0
     * @param defaultTtlMs    default TTL in milliseconds; {@code <= 0} means no expiry
     * @param loader          loader invoked on a miss; may be {@code null}
     * @param removalListener removal callback; {@code null} disables notifications
     */
    AsyncLoadingCacheImpl(int maxSize, long defaultTtlMs, Function<K, V> loader,
                          RemovalListener<K, V> removalListener) {
        this(maxSize, defaultTtlMs, loader, removalListener,
             Executors.newSingleThreadScheduledExecutor(
                 Thread.ofVirtual().name("async-cache-cleaner").factory()),
             1_000L);
    }

    /**
     * Builds an {@link LRUCache} delegate with no removal listener and owned virtual-thread
     * executors.
     *
     * @param maxSize      maximum number of entries; must be &gt; 0
     * @param defaultTtlMs default TTL in milliseconds; {@code <= 0} means no expiry
     * @param loader       loader invoked on a miss; may be {@code null}
     */
    AsyncLoadingCacheImpl(int maxSize, long defaultTtlMs, Function<K, V> loader) {
        this(maxSize, defaultTtlMs, loader, null);
    }

    // -------------------------------------------------------------------------
    // AsyncLoadingCache methods
    // -------------------------------------------------------------------------

    /**
     * Returns a future that completes with the value for {@code key}, loading it if absent.
     *
     * <p>Uses the configured loader set via {@link CacheBuilder#loader}. On a hit the future
     * is already complete ({@link CompletableFuture#completedFuture}). On a miss, exactly one
     * virtual thread runs the loader while all other callers receive the same future.
     *
     * @param key the key to look up; must not be {@code null}
     * @return a future that completes with the cached or freshly loaded value
     * @throws IllegalStateException if no loader was configured via {@link CacheBuilder#loader}
     * @throws NullPointerException  if {@code key} is null
     */
    @Override
    public CompletableFuture<V> getAsync(K key) {
        if (loader == null) {
            throw new IllegalStateException(
                "No loader configured. Use getAsync(key, asyncLoader) or set a loader via CacheBuilder.loader().");
        }
        // Wrap the sync loader as an async loader so we share the same thundering herd path.
        return getAsync(key, k -> CompletableFuture.supplyAsync(() -> loader.apply(k), REFRESH_EXECUTOR));
    }

    /**
     * Returns a future that completes with the value for {@code key}, using {@code asyncLoader}
     * on a miss.
     *
     * <p>{@code computeIfAbsent} is atomic per key — only one caller's {@code asyncLoader} is
     * invoked. All other callers receive the same in-flight future and are notified when it
     * completes. The future is removed from the in-flight map on both success and failure so
     * subsequent requests retry rather than receiving a cached exception.
     *
     * @param key         the key to look up; must not be {@code null}
     * @param asyncLoader function that returns a future producing the value for a missing key
     * @return a future that completes with the cached or freshly loaded value; completes
     *         exceptionally if {@code asyncLoader}'s future completes exceptionally
     * @throws NullPointerException if {@code key} is null
     */
    @Override
    public CompletableFuture<V> getAsync(K key, Function<K, CompletableFuture<V>> asyncLoader) {
        CacheSupport.rejectNullKey(key);
        V cached = delegate.get(key);
        if (cached != null) {
            maybeRefreshAhead(key);
            return CompletableFuture.completedFuture(cached);
        }

        // computeIfAbsent is atomic — only ONE future is created per key
        return inFlight.computeIfAbsent(key, k ->
            asyncLoader.apply(k).whenComplete((value, ex) -> {
                inFlight.remove(k);       // always remove so failures can be retried
                if (ex == null) {
                    delegate.put(k, value);
                }
            })
        );
    }

    /**
     * Triggers a background reload if the entry for {@code key} is within the refresh-ahead
     * threshold of its expiry.
     *
     * <p>Only fires when: a loader is configured, refresh-ahead is enabled
     * ({@code refreshThresholdNanos > 0}), the delegate is an {@link LRUCache} (so entry
     * metadata is accessible), and no load is already in-flight for this key.
     *
     * <p>The {@code !inFlight.containsKey(key)} check is a non-atomic optimisation — two
     * threads may both pass it and call {@code computeIfAbsent}. {@code computeIfAbsent}
     * handles the race correctly; only one future is created.
     */
    private void maybeRefreshAhead(K key) {
        if (loader == null || refreshThresholdNanos == 0) return;
        if (!(delegate instanceof LRUCache<K, V> lruDelegate)) return;

        CacheEntry<V> entry = lruDelegate.peekEntry(key);
        if (entry == null) return;

        long remainingNanos = entry.expiryNanos() - System.nanoTime();
        if (remainingNanos < refreshThresholdNanos && !inFlight.containsKey(key)) {
            inFlight.computeIfAbsent(key, k ->
                CompletableFuture.supplyAsync(() -> loader.apply(k), REFRESH_EXECUTOR)
                    .whenComplete((v, ex) -> {
                        inFlight.remove(k);
                        if (ex == null) delegate.put(k, v);
                    })
            );
        }
    }

    // -------------------------------------------------------------------------
    // Cache delegation — all forwarded directly to the delegate
    // -------------------------------------------------------------------------

    @Override
    public V get(K key) {
        return delegate.get(key);
    }

    @Override
    public void put(K key, V value) {
        delegate.put(key, value);
    }

    @Override
    public void put(K key, V value, long ttlMs) {
        delegate.put(key, value, ttlMs);
    }

    @Override
    public void invalidate(K key) {
        delegate.invalidate(key);
    }

    @Override
    public void invalidateAll() {
        delegate.invalidateAll();
    }

    @Override
    public boolean containsKey(K key) {
        return delegate.containsKey(key);
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public CacheStats stats() {
        return delegate.stats();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
