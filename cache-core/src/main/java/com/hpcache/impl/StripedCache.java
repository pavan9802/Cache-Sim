package com.hpcache.impl;

import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.hpcache.api.Cache;
import com.hpcache.api.CacheStats;
import com.hpcache.api.RemovalListener;

import net.jcip.annotations.ThreadSafe;

/**
 * A concurrent cache that partitions entries across N independent {@link LRUCache} segments.
 *
 * <p>Keys are routed to segments via a Wang hash of their {@code hashCode()}. Threads operating
 * on different segments never contend — throughput scales near-linearly up to the stripe count.
 * Beyond that it flattens, since each segment is still protected by its own lock.
 *
 * <p>The stripe count is always rounded up to the next power of two so that segment selection
 * can use a bitmask ({@code hash & stripeMask}) instead of modulo. If {@code maxSize} is not
 * evenly divisible by the stripe count, the remainder is lost to integer truncation — total
 * capacity may be slightly less than {@code maxSize}.
 *
 * <p>Construct via {@link CacheBuilder#stripeCount(int)}, not directly.
 *
 * @param <K> key type — must implement {@code hashCode()} and {@code equals()}; must not be null
 * @param <V> value type
 */
@ThreadSafe
public class StripedCache<K, V> implements Cache<K, V>{
    private final LRUCache<K, V>[] segments;
    private final int stripeCount;
    private final int stripeMask;   // stripeCount - 1 (requires power-of-two stripeCount)

    /**
     * Constructs a striped cache with the given configuration.
     *
     * <p>The supplied {@code stripeCount} is rounded up to the next power of two. Each segment
     * receives its own virtual-thread cleaner named {@code cache-cleaner-N}.
     *
     * @param maxSize           maximum total entries across all segments; distributed evenly
     * @param defaultTtlMs      default TTL in milliseconds; {@code <= 0} means no expiry
     * @param stripeCount       desired number of segments; rounded up to next power of two
     * @param removalListener   callback fired on eviction, expiry, or explicit removal; may be null
     * @param cleanupIntervalMs how often each segment's cleaner sweeps for expired entries
     */
    StripedCache(int maxSize, long defaultTtlMs, int stripeCount,
                    RemovalListener<K, V> removalListener, long cleanupIntervalMs) {
        this.stripeCount = nextPowerOfTwo(stripeCount);
        this.stripeMask = this.stripeCount - 1;
        int segmentSize = Math.max(1, maxSize / this.stripeCount);

        @SuppressWarnings("unchecked")
        LRUCache<K, V>[] segs = new LRUCache[this.stripeCount];
        this.segments = segs;
        for (int i = 0; i < this.stripeCount; i++) {
            segments[i] = new LRUCache<>(segmentSize, defaultTtlMs, removalListener,
                Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("cache-cleaner-" + i).factory()
                ), cleanupIntervalMs);
        }
    }

    /**
     * Returns the value associated with {@code key}, or {@code null} if absent or expired.
     *
     * @throws NullPointerException if {@code key} is null
     */
    @Override
    public V get(K key) {
        rejectNullKey(key);
        return segments[stripeFor(key)].get(key);
    }

    /**
     * Associates {@code key} with {@code value} using the cache's default TTL.
     *
     * @throws NullPointerException if {@code key} is null
     */
    @Override
    public void put(K key, V value) {
        rejectNullKey(key);
        segments[stripeFor(key)].put(key, value);
    }

    /**
     * Associates {@code key} with {@code value}, overriding the default TTL for this entry.
     *
     * @param ttlMs per-entry TTL in milliseconds; {@code <= 0} means no expiry for this entry
     * @throws NullPointerException if {@code key} is null
     */
    @Override
    public void put(K key, V value, long ttlMs) {
        rejectNullKey(key);
        segments[stripeFor(key)].put(key, value, ttlMs);
    }

    /**
     * Removes the entry for {@code key} if present.
     *
     * @throws NullPointerException if {@code key} is null
     */
    @Override
    public void invalidate(K key) {
        rejectNullKey(key);
        segments[stripeFor(key)].invalidate(key);
    }

    /**
     * Removes all entries from every segment.
     */
    @Override
    public void invalidateAll() {
        forEachSegment(LRUCache::invalidateAll);
    }

    /**
     * Returns {@code true} if the cache contains a non-expired entry for {@code key}.
     *
     * @throws NullPointerException if {@code key} is null
     */
    @Override
    public boolean containsKey(K key) {
        rejectNullKey(key);
        return segments[stripeFor(key)].containsKey(key);
    }

    /**
     * Returns the total number of entries currently held across all segments.
     *
     * <p>This is a point-in-time snapshot; concurrent writes may change the value immediately
     * after it is read.
     */
    @Override
    public int size() {
        int totalSize = 0;
        for (LRUCache<K, V> cache : segments) {
            totalSize += cache.size();
        }
        return totalSize;
    }

    /**
     * Returns an aggregate snapshot of statistics across all segments.
     *
     * <p>Counters are summed sequentially — a concurrent write to a later segment may
     * increment its counter between reads, so the result is always approximate. Locking
     * all segments for an exact snapshot would defeat the purpose of striping.
     *
     * <p>{@code loadCount} and {@code totalLoadTimeNanos} are {@code 0} — loading is not
     * supported until Phase 2C.
     */
    @Override
    public CacheStats stats() {
        long hitCount = 0L;
        long missCount = 0L;
        long evictionCount = 0L;
        long expirationCount = 0L;
        for (LRUCache<K, V> cache : segments) {
            CacheStats stats = cache.stats();
            hitCount += stats.hitCount();
            missCount += stats.missCount();
            evictionCount += stats.evictionCount();
            expirationCount += stats.expirationCount();
        }
        return new CacheStats(
            hitCount,
            missCount,
            evictionCount,
            expirationCount,
            0L,
            0L,
            System.currentTimeMillis()
        );
    }

    /**
     * Shuts down the background cleaner thread in every segment.
     *
     * <p>After this call the cache should not be used. Already-stored entries remain readable
     * until the object is garbage collected, but no further TTL cleanup will occur.
     */
    @Override
    public void close() {
        forEachSegment(LRUCache::close);
    }

    /**
     * Returns the number of stripes this cache was constructed with (always a power of two).
     */
    public int stripeCount() {
        return stripeCount;
    }

    /**
     * Returns the segment index for {@code key} using a Wang hash of its {@code hashCode()}.
     *
     * <p>Wang hash reduces clustering from poor {@code hashCode()} implementations and
     * distributes keys more uniformly than a plain modulo.
     */
    private int stripeFor(K key) {
        int h = key.hashCode();
        h = ((h >>> 16) ^ h) * 0x45d9f3b;
        h = ((h >>> 16) ^ h) * 0x45d9f3b;
        h = (h >>> 16) ^ h;
        return h & stripeMask;
    }

    /**
     * Applies {@code action} to every segment in order. Zero allocation — no iterator or
     * stream is created.
     */
    private void forEachSegment(Consumer<LRUCache<K, V>> action) {
        for (LRUCache<K, V> segment : segments) {
            action.accept(segment);
        }
    }

    /** @throws NullPointerException if {@code key} is null */
    private void rejectNullKey(K key) {
        if (key == null) throw new NullPointerException("key must not be null");
    }

    /** Returns the smallest power of two that is {@code >= n}. */
    private static int nextPowerOfTwo(int n) {
        return n <= 1 ? 1 : Integer.highestOneBit(n - 1) << 1;
    }

    int[] segmentSizes() {
        int[] sizes = new int[stripeCount];
        for (int i = 0; i < stripeCount; i++) {
            sizes[i] = segments[i].size();
        }
    return sizes;
    }
}
