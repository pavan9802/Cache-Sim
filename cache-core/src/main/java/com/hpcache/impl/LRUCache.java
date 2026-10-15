package com.hpcache.impl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hpcache.api.Cache;
import com.hpcache.api.CacheStats;
import com.hpcache.api.RemovalCause;
import com.hpcache.api.RemovalListener;
import com.hpcache.internal.entry.CacheEntry;

import net.jcip.annotations.ThreadSafe;

/**
 * Thread-safe LRU cache with per-entry TTL expiry and size-based eviction.
 *
 * <p>Backed by a {@code LinkedHashMap} in access order with a single
 * {@link ReentrantReadWriteLock} guarding all mutations. Because {@code LinkedHashMap}
 * with {@code accessOrder=true} mutates internal node order on every {@code get()},
 * reads and writes both acquire the <em>write</em> lock. This makes the implementation
 * correct but limits concurrent read throughput; Phase 2 replaces this with a
 * segmented or lock-free strategy.
 *
 * <p>Expired entries are lazily removed on {@link #get} and proactively swept by a
 * background virtual-thread cleaner that runs every second.
 *
 * <p>This class is {@link ThreadSafe}.
 */
@ThreadSafe
public class LRUCache<K, V> implements Cache<K, V> {
    private static final Logger log = LoggerFactory.getLogger(LRUCache.class);
    private final LinkedHashMap<K, CacheEntry<V>> map;   // accessOrder=true
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final int maxSize;
    private final long defaultTtlMs;
    private final RemovalListener<K, V> removalListener;
    private final ScheduledExecutorService cleaner;
    // Incremented exclusively under writeLock — volatile for visibility in stats() which reads without a lock.
    private volatile long hitCount;
    private volatile long missCount;
    private volatile long evictionCount;
    private volatile long expirationCount;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Full constructor for testing and dependency injection.
     *
     * <p>Schedules a fixed-rate expired-entry sweep on {@code cleaner} immediately
     * after construction. Callers that supply an external executor remain responsible
     * for its lifecycle, but note that {@link #close()} will call
     * {@link ScheduledExecutorService#shutdown()} on it.
     *
     * @param maxSize         maximum number of entries before LRU eviction; must be &gt; 0
     * @param defaultTtlMs    TTL applied by {@link #put(Object, Object)} when no per-entry
     *                        TTL is given; {@code <= 0} means entries never expire
     * @param removalListener callback fired on eviction, expiry, or explicit removal;
     *                        {@code null} disables notifications
     * @param cleaner         executor used for the background expiry sweep
     */
    LRUCache(int maxSize, long defaultTtlMs, RemovalListener<K, V> removalListener,
             ScheduledExecutorService cleaner, long cleanupIntervalMs) {
        this.maxSize = maxSize;
        this.defaultTtlMs = defaultTtlMs;
        this.removalListener = removalListener;
        this.cleaner = cleaner;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, CacheEntry<V>> eldest) {
                if (size() > maxSize) {
                    evictionCount++;
                    notifyRemoval(eldest.getKey(), eldest.getValue().value(), RemovalCause.SIZE);
                    return true;
                }
                return false;
            }
        };
        cleaner.scheduleAtFixedRate(this::removeExpiredEntries,
            cleanupIntervalMs, cleanupIntervalMs, TimeUnit.MILLISECONDS);
    }

    LRUCache(int maxSize, long defaultTtlMs, RemovalListener<K, V> removalListener, ScheduledExecutorService cleaner) {
        this(maxSize, defaultTtlMs, removalListener, cleaner, 1_000L);
    }

    /**
     * Constructs an instance with an owned virtual-thread cleaner.
     *
     * <p>Creates a single-thread {@link ScheduledExecutorService} backed by a virtual
     * thread named {@code cache-cleaner}. Virtual threads are daemon threads by default,
     * so this executor will not prevent JVM shutdown.
     *
     * @param maxSize         maximum number of entries before LRU eviction; must be &gt; 0
     * @param defaultTtlMs    default TTL in milliseconds; {@code <= 0} means no expiry
     * @param removalListener removal callback; {@code null} disables notifications
     */
    LRUCache(int maxSize, long defaultTtlMs, RemovalListener<K, V> removalListener) {
        this(maxSize, defaultTtlMs, removalListener,
            Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("cache-cleaner").factory()
            ));
    }

    /**
     * Constructs an instance with no removal listener and an owned virtual-thread cleaner.
     *
     * @param maxSize      maximum number of entries before LRU eviction; must be &gt; 0
     * @param defaultTtlMs default TTL in milliseconds; {@code <= 0} means no expiry
     */
    LRUCache(int maxSize, long defaultTtlMs) {
        this(maxSize, defaultTtlMs, null);
    }

    /**
     * Returns the value for {@code key}, or {@code null} on a cache miss or expired entry.
     *
     * <p>Acquires the <em>write</em> lock because {@code LinkedHashMap} with
     * {@code accessOrder=true} moves the accessed node to the tail of the doubly-linked
     * list on every call — a structural mutation that is unsafe under concurrent reads.
     * An expired entry is eagerly removed and counted as both a miss and an eviction.
     *
     * @param key the key to look up; must not be {@code null}
     * @return the cached value, or {@code null} if absent or expired
     */
    @Override
    public V get(K key) {
        rejectNullKey(key);
        lock.writeLock().lock();
        try {
            CacheEntry<V> entry = map.get(key);
            if (entry == null) {
                missCount++;
                return null;
            }
            if (entry.isExpired()) {
                map.remove(key);
                evictionCount++;
                missCount++;
                expirationCount++;
                notifyRemoval(key, entry.value(), RemovalCause.EXPIRED);
                return null;
            }
            hitCount++;
            return entry.value();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Associates {@code value} with {@code key} using the cache-wide default TTL.
     *
     * <p>If {@code defaultTtlMs <= 0} the entry is stored without an expiry.
     * If a previous mapping existed, the {@link RemovalListener} is notified with
     * {@link RemovalCause#EXPLICIT} for the overwritten value. If adding the entry
     * causes {@code size > maxSize}, the least-recently-used entry is evicted synchronously
     * and the listener is notified with {@link RemovalCause#SIZE}.
     *
     * @param key   the key to associate; must not be {@code null}
     * @param value the value to store; must not be {@code null}
     */
    @Override
    public void put(K key, V value) {
        rejectNullKey(key);
        lock.writeLock().lock();
        try {
            CacheEntry<V> newEntry = defaultTtlMs > 0
                ? CacheEntry.withTtl(value, defaultTtlMs)
                : CacheEntry.noExpiry(value);
            CacheEntry<V> previous = map.put(key, newEntry);
            if (previous != null) {
                notifyRemoval(key, previous.value(), RemovalCause.EXPLICIT);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Associates {@code value} with {@code key} and expires the entry after {@code ttlMs}
     * milliseconds, overriding the cache-wide default TTL for this entry.
     *
     * <p>The expiry deadline is computed as {@code System.nanoTime() + ttlMs * 1_000_000}
     * at the time of this call. If a previous mapping existed, the {@link RemovalListener}
     * is notified with {@link RemovalCause#EXPLICIT} for the overwritten value.
     *
     * @param key   the key to associate; must not be {@code null}
     * @param value the value to store; must not be {@code null}
     * @param ttlMs time-to-live in milliseconds; {@code <= 0} causes immediate expiry
     */
    @Override
    public void put(K key, V value, long ttlMs) {
        rejectNullKey(key);
        lock.writeLock().lock();
        try {
            CacheEntry<V> previous = map.put(key, CacheEntry.withTtl(value, ttlMs));
            if (previous != null) {
                notifyRemoval(key, previous.value(), RemovalCause.EXPLICIT);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes the entry for {@code key} if present, notifying the {@link RemovalListener}
     * with {@link RemovalCause#EXPLICIT}.
     *
     * @param key the key to remove; must not be {@code null}
     */
    @Override
    public void invalidate(K key) {
        rejectNullKey(key);
        lock.writeLock().lock();
        try {
            CacheEntry<V> entry = map.remove(key);
            if (entry != null) {
                notifyRemoval(key, entry.value(), RemovalCause.EXPLICIT);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes all entries from the cache.
     *
     * <p>If a {@link RemovalListener} is registered, it is called synchronously for
     * every entry before the map is cleared. The listener receives
     * {@link RemovalCause#EXPLICIT} for each entry.
     */
    @Override
    public void invalidateAll() {
        lock.writeLock().lock();
        try {
            if (removalListener != null) {
                map.forEach((k, entry) -> notifyRemoval(k, entry.value(), RemovalCause.EXPLICIT));
            }
            map.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns true if the cache contains a live entry for {@code key}.
     *
     * <p>Trade-off: internally calls {@code map.get()}, which promotes the entry to
     * most-recently-used in the LRU order. A pure existence check cannot retrieve the
     * {@link CacheEntry} for expiry inspection without this side effect — {@code LinkedHashMap}
     * has no peek API. Callers on hot paths where LRU promotion is undesirable should use
     * {@link #get} instead and handle the null return.
     */
    @Override
    public boolean containsKey(K key) {
        rejectNullKey(key);
        lock.writeLock().lock();
        try {
            CacheEntry<V> entry = map.get(key);
            return entry != null && !entry.isExpired();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the number of entries currently in the cache, including entries that may
     * be expired but not yet swept by the background cleaner.
     *
     * <p>Acquires the read lock. Unlike {@link #get}, {@code size()} does not mutate
     * the map's access order, so a read lock is sufficient here.
     *
     * @return current entry count; never negative
     */
    @Override
    public int size() {
        lock.readLock().lock();
        try {
            return map.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns an immutable snapshot of cache statistics at the time of this call.
     *
     * <p>Counters are read from {@link AtomicLong} fields without acquiring a lock,
     * so the snapshot is weakly consistent — individual counters are always accurate,
     * but they may not reflect a single atomic instant under concurrent access.
     * {@code loadCount} and {@code totalLoadTimeNanos} are always zero in Phase 1
     * (no read-through loading is supported yet).
     *
     * @return a {@link CacheStats} snapshot; never {@code null}
     */
    @Override
    public CacheStats stats() {
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
     * Shuts down the background expiry cleaner and marks this cache as closed.
     *
     * <p>Idempotent — safe to call multiple times. Any in-flight cleaner sweep is
     * allowed to finish gracefully ({@link ScheduledExecutorService#shutdown()} does not
     * interrupt running tasks). Cache operations after {@code close()} are not defined.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cleaner.shutdown();
        }
    }

    /**
     * Delivers a removal notification to the {@link RemovalListener}, if one is registered.
     *
     * <p>Must be called while holding the write lock. Any exception thrown by the listener
     * is caught and logged — it must not propagate, as doing so would corrupt the lock state
     * of the caller.
     *
     * @param key   the key being removed
     * @param value the value that was associated with {@code key}
     * @param cause the reason for removal
     */
    private void notifyRemoval(K key, V value, RemovalCause cause) {
        rejectNullKey(key);
        if (removalListener != null) {
            try {
                removalListener.onRemoval(key, value, cause);
            } catch (Exception e) {
                log.warn("RemovalListener threw for key {}", key, e);
            }
        }
    }

    /**
     * Scans all entries and removes those whose TTL has elapsed.
     *
     * <p>Called by the background cleaner on a fixed 1-second interval. Acquires the
     * write lock for the duration of the scan to ensure the {@code removeIf} iteration
     * is isolated from concurrent {@link #put} and {@link #get} operations. Each removed
     * entry increments {@code evictionCount} and fires the {@link RemovalListener} with
     * {@link RemovalCause#EXPIRED}.
     */
    private void  removeExpiredEntries() {
        lock.writeLock().lock();
        try {
            map.entrySet().removeIf(entry -> {
                if (entry.getValue().isExpired()) {
                    evictionCount++;
                    expirationCount++;
                    notifyRemoval(entry.getKey(), entry.getValue().value(), RemovalCause.EXPIRED);
                    return true;
                }
                return false;
            });
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void rejectNullKey(K key){
        if (key == null) throw new NullPointerException("key must not be null");
    }
}
