package com.hpcache.impl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import com.hpcache.api.Cache;
import com.hpcache.api.CacheLoadException;
import com.hpcache.api.CacheStats;
import com.hpcache.api.LoadingCache;
import com.hpcache.api.RemovalListener;
import com.hpcache.internal.CacheSupport;

import net.jcip.annotations.ThreadSafe;

/**
 * Thread-safe read-through cache with thundering herd prevention.
 *
 * <p>Wraps any {@link Cache} delegate and adds loader semantics. On a miss,
 * {@link #get(Object, Function)} uses {@link ConcurrentHashMap#computeIfAbsent} to ensure
 * exactly one thread runs the loader per key; all other threads block on the same
 * {@link CompletableFuture} until the value is ready.
 *
 * <p>Thundering herd note: there is a narrow race between the in-flight future completing
 * (and removing itself) and a new request arriving for the same key. In that window, a
 * second load may start. Both loads produce the same value and one overwrites the other —
 * this is safe but not perfectly deduplicated.
 */
@ThreadSafe
public class LoadingCacheImpl<K, V> implements LoadingCache<K, V> {

    // Virtual-thread executor for loader tasks — never use ForkJoinPool.commonPool()
    private static final Executor LOADER_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final Cache<K, V> delegate;
    private final Function<K, V> loader;  // configured loader for getOrLoad(); null if not set
    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

    /**
     * Primary constructor. Accepts a pre-built {@code Cache} delegate and an optional
     * pre-configured loader. Pass {@code null} for {@code loader} when only the
     * per-call {@link #get(Object, Function)} form will be used.
     *
     * @param delegate backing cache that handles eviction, TTL, and locking
     * @param loader   default loader invoked by {@link #getOrLoad}; may be {@code null}
     */
    LoadingCacheImpl(Cache<K, V> delegate, Function<K, V> loader) {
        this.delegate = delegate;
        this.loader = loader;
    }

    /**
     * Builds and owns an {@link LRUCache} delegate with the given parameters.
     *
     * @param maxSize           maximum number of entries before LRU eviction; must be &gt; 0
     * @param defaultTtlMs      default TTL in milliseconds; {@code <= 0} means no expiry
     * @param loader            default loader; may be {@code null}
     * @param removalListener   removal callback; {@code null} disables notifications
     * @param cleaner           executor for the background expiry sweep
     * @param cleanupIntervalMs interval between sweeps in milliseconds; must be &gt; 0
     */
    LoadingCacheImpl(int maxSize, long defaultTtlMs, Function<K, V> loader,
                     RemovalListener<K, V> removalListener,
                     ScheduledExecutorService cleaner, long cleanupIntervalMs) {
        this(new LRUCache<>(maxSize, defaultTtlMs, removalListener, cleaner, cleanupIntervalMs),
             loader);
    }

    /**
     * Builds an {@link LRUCache} delegate with an owned virtual-thread cleaner and a
     * 1-second sweep interval.
     *
     * @param maxSize         maximum number of entries; must be &gt; 0
     * @param defaultTtlMs    default TTL in milliseconds; {@code <= 0} means no expiry
     * @param loader          default loader; may be {@code null}
     * @param removalListener removal callback; {@code null} disables notifications
     */
    LoadingCacheImpl(int maxSize, long defaultTtlMs, Function<K, V> loader,
                     RemovalListener<K, V> removalListener) {
        this(maxSize, defaultTtlMs, loader, removalListener,
             Executors.newSingleThreadScheduledExecutor(
                 Thread.ofVirtual().name("loading-cache-cleaner").factory()),
             1_000L);
    }

    /**
     * Builds an {@link LRUCache} delegate with no removal listener and an owned
     * virtual-thread cleaner.
     *
     * @param maxSize      maximum number of entries; must be &gt; 0
     * @param defaultTtlMs default TTL in milliseconds; {@code <= 0} means no expiry
     * @param loader       default loader; may be {@code null}
     */
    LoadingCacheImpl(int maxSize, long defaultTtlMs, Function<K, V> loader) {
        this(maxSize, defaultTtlMs, loader, null);
    }

    // -------------------------------------------------------------------------
    // LoadingCache methods
    // -------------------------------------------------------------------------

    /**
     * Returns the value for {@code key}, invoking {@code loader} on a miss.
     *
     * <p>Thundering herd prevention: {@link ConcurrentHashMap#computeIfAbsent} guarantees
     * that exactly one thread creates the {@link CompletableFuture} for a given key. All
     * other threads that miss on the same key receive the same future and block on
     * {@link CompletableFuture#get()} until the load completes. Only one backend call is
     * made regardless of how many threads miss concurrently.
     *
     * <p>Double-check inside {@code computeIfAbsent}: the lambda re-reads the delegate before
     * creating the async task. If a concurrent load completed between the caller's initial null
     * check and entering {@code computeIfAbsent}, the lambda returns a pre-completed future and
     * the loader is never called a second time.
     *
     * @param key    the key to look up; must not be {@code null}
     * @param loader function that computes the value for a missing key; must not be {@code null}
     * @return the cached or freshly loaded value; never {@code null} if the loader returns non-null
     * @throws CacheLoadException   if the loader throws or the waiting thread is interrupted
     * @throws NullPointerException if {@code key} is null
     */
    @Override
    public V get(K key, Function<K, V> loader) {
        CacheSupport.rejectNullKey(key);
        V cached = delegate.get(key);
        if (cached != null) return cached;

        // computeIfAbsent is atomic per key — only ONE thread creates the future.
        // The lambda double-checks the delegate so a thread that was preempted between its
        // initial null-check and here does not start a redundant second load.
        var future = inFlight.computeIfAbsent(key, k -> {
            V existing = delegate.get(k);
            if (existing != null) return CompletableFuture.completedFuture(existing);
            return CompletableFuture.supplyAsync(() -> {
                V value = loader.apply(k);
                delegate.put(k, value);
                inFlight.remove(k);
                return value;
            }, LOADER_EXECUTOR);
        });

        try {
            return future.get();   // all other threads block here on the same future
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CacheLoadException(key, e);
        } catch (ExecutionException e) {
            inFlight.remove(key);  // remove failed future so next request retries
            Throwable cause = e.getCause();
            if (cause instanceof CacheLoadException cle) throw cle;  // don't double-wrap
            throw new CacheLoadException(key, cause);
        }
    }

    /**
     * Returns the value for {@code key}, invoking the configured loader on a miss.
     *
     * <p>Delegates to {@link #get(Object, Function)} using the loader set at construction
     * time. Applies the same thundering herd prevention as that method.
     *
     * @param key the key to look up; must not be {@code null}
     * @return the cached or freshly loaded value
     * @throws IllegalStateException if no loader was configured via {@link CacheBuilder#loader}
     * @throws CacheLoadException    if the loader throws or the waiting thread is interrupted
     * @throws NullPointerException  if {@code key} is null
     */
    @Override
    public V getOrLoad(K key) {
        CacheSupport.rejectNullKey(key);
        requireLoader();
        return get(key, loader);
    }

    /**
     * Forces a synchronous reload of {@code key} regardless of whether a value is already cached.
     *
     * <p>Runs the configured loader on the calling thread and overwrites any existing entry.
     * Unlike {@link #get(Object, Function)}, this method does not use the in-flight map —
     * it is an explicit, intentional reload, not a concurrent miss.
     *
     * @param key the key to reload; must not be {@code null}
     * @throws IllegalStateException if no loader was configured via {@link CacheBuilder#loader}
     * @throws CacheLoadException    if the loader throws a non-{@link CacheLoadException}
     * @throws NullPointerException  if {@code key} is null
     */
    @Override
    public void refresh(K key) {
        CacheSupport.rejectNullKey(key);
        requireLoader();
        try {
            V value = loader.apply(key);
            delegate.put(key, value);
        } catch (CacheLoadException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CacheLoadException(key, e);
        }
    }

    private void requireLoader() {
        if (loader == null) {
            throw new IllegalStateException(
                "No loader configured. Set one via CacheBuilder.loader() or use get(key, loader).");
        }
    }

    // -------------------------------------------------------------------------
    // Cache delegation — all forwarded directly to the delegate
    // -------------------------------------------------------------------------

    @Override
    public V get(K key) {
        CacheSupport.rejectNullKey(key);
        return delegate.get(key);
    }

    @Override
    public void put(K key, V value) {
        CacheSupport.rejectNullKey(key);
        delegate.put(key, value);
    }

    @Override
    public void put(K key, V value, long ttlMs) {
        CacheSupport.rejectNullKey(key);
        delegate.put(key, value, ttlMs);
    }

    @Override
    public void invalidate(K key) {
        CacheSupport.rejectNullKey(key);
        delegate.invalidate(key);
    }

    @Override
    public void invalidateAll() {
        delegate.invalidateAll();
    }

    @Override
    public boolean containsKey(K key) {
        CacheSupport.rejectNullKey(key);
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
