package com.hpcache.api;

/**
 * Contract for all cache implementations in this library.
 *
 * <p>Thread safety: all implementations must be thread-safe unless explicitly documented
 * otherwise. {@code K} must implement {@code hashCode()} and {@code equals()}.
 */
public interface Cache<K, V> {

    /**
     * Returns the value for {@code key}, or {@code null} on a cache miss.
     * Never throws for a missing key — {@code null} is the canonical miss signal.
     */
    V get(K key);

    /**
     * Associates {@code value} with {@code key}. Overwrites any existing mapping.
     */
    void put(K key, V value);

    /**
     * Associates {@code value} with {@code key} and expires the entry after {@code ttlMs}
     * milliseconds. A TTL of zero or negative is implementation-defined; most implementations
     * treat it as "expire immediately."
     */
    void put(K key, V value, long ttlMs);

    /**
     * Removes the entry for {@code key} if present. No-op if the key is absent.
     */
    void invalidate(K key);

    /**
     * Removes all entries. The cache is empty after this call returns.
     */
    void invalidateAll();

    /**
     * Returns {@code true} if the cache contains a live (non-expired) entry for {@code key}.
     */
    boolean containsKey(K key);

    /**
     * Returns the number of entries currently in the cache. May be an approximation under
     * concurrent access.
     */
    int size();

    /**
     * Returns a snapshot of cache statistics, or {@code null} if stats are not supported
     * (e.g., the stub implementation in Phase 0). Cast to {@code CacheStats} once available.
     */
    Object stats();

    /**
     * Releases resources held by this cache (background threads, off-heap memory, etc.).
     * Idempotent — safe to call more than once.
     */
    void close();
}
