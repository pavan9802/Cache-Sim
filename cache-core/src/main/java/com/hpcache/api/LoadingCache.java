package com.hpcache.api;

import java.util.function.Function;

/**
 * A {@link Cache} that can load values on demand when a key is absent.
 *
 * <p>Implementations must provide <em>thundering herd prevention</em>: when multiple threads
 * concurrently miss the same key, exactly one thread runs the loader and the rest wait for
 * that result. Only one backend call is made per key per miss event.
 *
 * <p><b>Null contract:</b> loaders must not return {@code null}. Because {@code null} is the
 * sentinel for a cache miss in {@link Cache#get}, a null-returning loader would cause the value
 * to appear absent on the next read and be reloaded indefinitely.
 *
 * <p><b>Thread safety:</b> all methods are safe for concurrent use from multiple threads.
 *
 * @param <K> key type
 * @param <V> value type
 */
public interface LoadingCache<K, V> extends Cache<K, V> {

    /**
     * Returns the cached value for {@code key}, invoking {@code loader} on a miss.
     *
     * <p>If {@code key} is absent, exactly one thread runs {@code loader} — all other threads
     * that concurrently miss on the same key block until the load completes and then receive
     * the same value. Subsequent calls find the loaded value in the cache and return it without
     * calling the loader.
     *
     * @param key    the key to look up; must not be {@code null}
     * @param loader function that computes the value for a missing key; must not return
     *               {@code null}
     * @return the cached or freshly loaded value; never {@code null} if the loader contract
     *         is respected
     * @throws CacheLoadException   if the loader throws or the calling thread is interrupted
     * @throws NullPointerException if {@code key} is {@code null}
     */
    V get(K key, Function<K, V> loader);

    /**
     * Returns the cached value for {@code key}, invoking the loader configured at build time
     * on a miss.
     *
     * <p>Applies the same thundering herd prevention as {@link #get(Object, Function)}.
     *
     * @param key the key to look up; must not be {@code null}
     * @return the cached or freshly loaded value
     * @throws IllegalStateException if no loader was configured via {@link CacheBuilder#loader}
     * @throws CacheLoadException    if the loader throws or the calling thread is interrupted
     * @throws NullPointerException  if {@code key} is {@code null}
     */
    V getOrLoad(K key);

    /**
     * Forces a synchronous reload of {@code key} using the configured loader, replacing any
     * existing value.
     *
     * <p>Unlike {@link #getOrLoad}, this always invokes the loader regardless of whether the
     * key is currently cached. The call runs on the calling thread and blocks until the loader
     * returns.
     *
     * @param key the key to reload; must not be {@code null}
     * @throws IllegalStateException if no loader was configured via {@link CacheBuilder#loader}
     * @throws CacheLoadException    if the loader throws
     * @throws NullPointerException  if {@code key} is {@code null}
     */
    void refresh(K key);
}
