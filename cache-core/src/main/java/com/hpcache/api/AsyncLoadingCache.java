package com.hpcache.api;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * A non-blocking {@link Cache} that loads values asynchronously on a miss.
 *
 * <p>All get methods return immediately with a {@link CompletableFuture} — the calling thread
 * is never blocked waiting for a load. Implementations that support refresh-ahead may
 * proactively reload entries that are near expiry so callers always find a warm value.
 *
 * <p>Like {@link LoadingCache}, thundering herd prevention is required: concurrent misses on
 * the same key must result in exactly one load; all callers receive the same future.
 *
 * <p><b>Null contract:</b> loaders must not return {@code null} or complete with {@code null}.
 * {@code null} is the sentinel for a cache miss; a null-returning loader causes the entry to
 * appear absent and triggers reloading on every access.
 *
 * <p><b>Thread safety:</b> all methods are safe for concurrent use from multiple threads.
 *
 * @param <K> key type
 * @param <V> value type
 */
public interface AsyncLoadingCache<K, V> extends Cache<K, V> {

    /**
     * Returns a future that completes with the value for {@code key}, using the loader
     * configured at build time on a miss.
     *
     * <p>On a cache hit the returned future is already complete. On a miss, exactly one virtual
     * thread runs the loader; all concurrent callers for the same key receive the same future.
     * The future completes exceptionally if the loader fails.
     *
     * @param key the key to look up; must not be {@code null}
     * @return a future that completes with the cached or freshly loaded value
     * @throws IllegalStateException if no loader was configured via {@link CacheBuilder#loader}
     * @throws NullPointerException  if {@code key} is {@code null}
     */
    CompletableFuture<V> getAsync(K key);

    /**
     * Returns a future that completes with the value for {@code key}, using {@code asyncLoader}
     * on a miss.
     *
     * <p>The {@code asyncLoader} is invoked at most once per concurrent miss event — additional
     * callers that miss on the same key while a load is in-flight receive the same future.
     * On failure, the in-flight entry is removed so subsequent requests trigger a fresh load.
     *
     * @param key         the key to look up; must not be {@code null}
     * @param asyncLoader function that returns a future producing the value for a missing key;
     *                    must not return a future that completes with {@code null}
     * @return a future that completes with the cached or freshly loaded value; completes
     *         exceptionally if {@code asyncLoader}'s future completes exceptionally
     * @throws NullPointerException if {@code key} is {@code null}
     */
    CompletableFuture<V> getAsync(K key, Function<K, CompletableFuture<V>> asyncLoader);
}
