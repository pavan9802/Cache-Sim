package com.hpcache.impl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hpcache.api.Cache;
import com.hpcache.api.CacheStats;
import com.hpcache.api.RemovalCause;
import com.hpcache.api.RemovalListener;
import com.hpcache.internal.CacheSupport;
import com.hpcache.internal.entry.CacheEntry;

/**
 * Thread-safe LRU-like cache that serves reads optimistically via
 * {@link java.util.concurrent.locks.StampedLock}.
 *
 * <p>The map uses insertion order ({@code accessOrder=false}) instead of access order.
 * Access-order maps mutate internal node linkage on every {@code get()}, which is
 * incompatible with optimistic reads — a concurrent structural mutation would corrupt
 * the read result. The eviction policy is therefore FIFO across inserts, not strict LRU.
 *
 * <p>Read path: {@link #get} and {@link #containsKey} first attempt a zero-cost optimistic
 * read ({@code tryOptimisticRead} + {@code validate}). Only if a concurrent write invalidates
 * the stamp do they fall back to a full read lock. All writes and the background expiry sweep
 * acquire the write lock.
 *
 * <p>{@link java.util.concurrent.locks.StampedLock} is <em>not reentrant</em>. No method in
 * this class may call another method that acquires the same lock while already holding it.
 *
 * <p>Construct via {@link CacheBuilder#optimisticReads(boolean)}, not directly.
 *
 * @param <K> key type — must implement {@code hashCode()} and {@code equals()}; must not be null
 * @param <V> value type
 */
@net.jcip.annotations.ThreadSafe
public class StampedCache<K, V> implements Cache<K, V> {

    private static final Logger log = LoggerFactory.getLogger(StampedCache.class);

    // accessOrder=false: safe under optimistic reads (no node-order mutation on get)
    private final LinkedHashMap<K, CacheEntry<V>> map;
    private final StampedLock lock = new StampedLock();
    private final int maxSize;
    private final long defaultTtlMs;
    private final RemovalListener<K, V> removalListener;
    private final ScheduledExecutorService cleaner;
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();
    private final AtomicLong evictionCount = new AtomicLong();
    private final AtomicLong expirationCount = new AtomicLong();

    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Full constructor for testing and dependency injection.
     *
     * <p>Schedules a fixed-rate expired-entry sweep on {@code cleaner} immediately after
     * construction. The caller remains responsible for the executor's lifecycle, but note
     * that {@link #close()} will call {@link ScheduledExecutorService#shutdown()} on it.
     *
     * @param maxSize           maximum number of entries before FIFO eviction; must be &gt; 0
     * @param defaultTtlMs      TTL applied by {@link #put(Object, Object)} when no per-entry
     *                          TTL is given; {@code <= 0} means entries never expire
     * @param removalListener   callback fired on expiry or explicit removal; {@code null} disables
     * @param cleaner           executor used for the background expiry sweep
     * @param cleanupIntervalMs interval between background sweeps in milliseconds; must be &gt; 0
     */
    StampedCache(int maxSize, long defaultTtlMs, RemovalListener<K, V> removalListener,
                 ScheduledExecutorService cleaner, long cleanupIntervalMs) {
        this.maxSize = maxSize;
        this.defaultTtlMs = defaultTtlMs;
        this.removalListener = removalListener;
        this.cleaner = cleaner;
        this.map = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, CacheEntry<V>> eldest) {
                if (size() > maxSize) {
                    CacheSupport.notifyRemoval(removalListener, log,  eldest.getKey(), eldest.getValue().value(), RemovalCause.SIZE);
                    evictionCount.getAndIncrement();
                    return true;
                }
                return false;
            }
        };
        cleaner.scheduleAtFixedRate(this::removeExpiredEntries,
            cleanupIntervalMs, cleanupIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Constructs an instance with an owned virtual-thread cleaner and a 1-second sweep interval.
     *
     * @param maxSize         maximum number of entries before FIFO eviction; must be &gt; 0
     * @param defaultTtlMs    default TTL in milliseconds; {@code <= 0} means no expiry
     * @param removalListener removal callback; {@code null} disables notifications
     */
    StampedCache(int maxSize, long defaultTtlMs, RemovalListener<K, V> removalListener) {
        this(maxSize, defaultTtlMs, removalListener,
            Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("stamped-cache-cleaner").factory()
            ),
            1_000L);
    }

    /**
     * Constructs an instance with no removal listener and an owned virtual-thread cleaner.
     *
     * @param maxSize      maximum number of entries before FIFO eviction; must be &gt; 0
     * @param defaultTtlMs default TTL in milliseconds; {@code <= 0} means no expiry
     */
    StampedCache(int maxSize, long defaultTtlMs) {
        this(maxSize, defaultTtlMs, null);
    }

    /**
     * Scans all entries and removes those whose TTL has elapsed.
     *
     * <p>Called by the background cleaner on a fixed interval. Acquires the write lock for
     * the duration of the scan so that the {@code removeIf} iteration is isolated from
     * concurrent {@link #put} operations. Each removed entry increments both
     * {@code evictionCount} and {@code expirationCount} and fires the
     * {@link RemovalListener} with {@link com.hpcache.api.RemovalCause#EXPIRED}.
     */
    private void removeExpiredEntries() {
       long stamp = lock.writeLock();
        try {
            map.entrySet().removeIf(entry -> {
                if (entry.getValue().isExpired()) {
                    evictionCount.getAndIncrement();
                    expirationCount.getAndIncrement();
                    CacheSupport.notifyRemoval(removalListener, log, entry.getKey(), entry.getValue().value(), RemovalCause.EXPIRED);
                    return true;
                }
                return false;
            });
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Returns the value for {@code key}, or {@code null} on a cache miss or expired entry.
     *
     * <p>Attempts an optimistic read first: if no concurrent write is in progress the result
     * is returned without acquiring any lock. If {@code validate(stamp)} fails — meaning a
     * write occurred between the read and the validation — the method falls back to a full
     * read lock. Expired entries are not removed from the map here; they are left for the
     * background cleaner, since mutation requires the write lock.
     *
     * @param key the key to look up; must not be {@code null}
     * @return the cached value, or {@code null} if absent or expired
     * @throws NullPointerException if {@code key} is null
     */
    @Override
    public V get(K key) {
        CacheSupport.rejectNullKey(key);
        // Attempt 1: optimistic read (no lock acquired at all)
        long stamp = lock.tryOptimisticRead();
        CacheEntry<V> entry = map.get(key);  // may see torn state if concurrent write

        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                entry = map.get(key);
            } finally {
                lock.unlockRead(stamp);
            }
        }

        if (entry == null) {
            missCount.incrementAndGet();
            return null;
        }
        if (entry.isExpired()) {
            // Cannot mutate under a read lock — schedule removal or accept lazy cleanup
            missCount.incrementAndGet();
            return null;
        }
        hitCount.incrementAndGet();
        return entry.value();
    }


    /**
     * Associates {@code value} with {@code key} using the cache-wide default TTL.
     *
     * <p>Acquires the write lock. If {@code defaultTtlMs <= 0} the entry is stored without
     * an expiry. If a previous mapping existed, the {@link RemovalListener} is notified with
     * {@link com.hpcache.api.RemovalCause#EXPLICIT} for the overwritten value.
     *
     * @param key   the key to associate; must not be {@code null}
     * @param value the value to store; must not be {@code null}
     * @throws NullPointerException if {@code key} is null
     */
    @Override
    public void put(K key, V value) {
        CacheSupport.rejectNullKey(key);
        long stamp = lock.writeLock();
        try {
            CacheEntry<V> newEntry = CacheEntry.withDefaultTtl(value, defaultTtlMs);
            CacheEntry<V> previous = map.put(key, newEntry);
            if (previous != null) {
                CacheSupport.notifyRemoval(removalListener, log, key, previous.value(), RemovalCause.EXPLICIT);
            }
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Associates {@code value} with {@code key} and expires the entry after {@code ttlMs}
     * milliseconds, overriding the cache-wide default TTL for this entry.
     *
     * <p>Acquires the write lock. If a previous mapping existed, the {@link RemovalListener}
     * is notified with {@link com.hpcache.api.RemovalCause#EXPLICIT} for the overwritten value.
     *
     * @param key   the key to associate; must not be {@code null}
     * @param value the value to store; must not be {@code null}
     * @param ttlMs time-to-live in milliseconds; {@code <= 0} causes immediate expiry
     * @throws NullPointerException if {@code key} is null
     */
    @Override
    public void put(K key, V value, long ttlMs) {
        CacheSupport.rejectNullKey(key);
        long stamp = lock.writeLock();
        try {
            CacheEntry<V> previous = map.put(key, CacheEntry.withTtl(value, ttlMs));
            if (previous != null) {
                CacheSupport.notifyRemoval(removalListener, log, key, previous.value(), RemovalCause.EXPLICIT);
            }
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Removes the entry for {@code key} if present, notifying the {@link RemovalListener}
     * with {@link com.hpcache.api.RemovalCause#EXPLICIT}.
     *
     * <p>Acquires the write lock.
     *
     * @param key the key to remove; must not be {@code null}
     * @throws NullPointerException if {@code key} is null
     */
    @Override
    public void invalidate(K key) {
        CacheSupport.rejectNullKey(key);
        long stamp = lock.writeLock();
        try {
            CacheEntry<V> entry = map.remove(key);
            if (entry != null) {
                CacheSupport.notifyRemoval(removalListener, log, key, entry.value(), RemovalCause.EXPLICIT);
            }
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Removes all entries from the cache.
     *
     * <p>Acquires the write lock. If a {@link RemovalListener} is registered, it is called
     * synchronously for every entry with {@link com.hpcache.api.RemovalCause#EXPLICIT}
     * before the map is cleared.
     */
    @Override
    public void invalidateAll() {
        long stamp = lock.writeLock();
        try {
            if (removalListener != null) {
                map.forEach((k, entry) -> CacheSupport.notifyRemoval(removalListener, log, k, entry.value(), RemovalCause.EXPLICIT));
            }
            map.clear();
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Returns {@code true} if the cache contains a live (non-expired) entry for {@code key}.
     *
     * <p>Uses the same optimistic-read strategy as {@link #get}: tries without a lock, falls
     * back to a read lock if the stamp is invalidated by a concurrent write.
     *
     * @param key the key to test; must not be {@code null}
     * @return {@code true} if a non-expired entry exists for {@code key}
     * @throws NullPointerException if {@code key} is null
     */
    @Override
    public boolean containsKey(K key) {
        CacheSupport.rejectNullKey(key);
        // Attempt 1: optimistic read (no lock acquired at all)
        long stamp = lock.tryOptimisticRead();
        CacheEntry<V> entry = map.get(key); 
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                entry = map.get(key);
            } finally {
                lock.unlockRead(stamp);
            }
        }
        
        return entry != null && !entry.isExpired();
        
    }

    /**
     * Returns the number of entries currently in the cache, including entries that may be
     * expired but not yet swept by the background cleaner.
     *
     * <p>Uses an optimistic read; falls back to a read lock if a concurrent write is detected.
     *
     * @return current entry count; never negative
     */
    @Override
    public int size() {
        long stamp = lock.tryOptimisticRead();
        int size = map.size(); 

        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                size = map.size(); 
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return size;
    }

    /**
     * Returns an immutable snapshot of cache statistics at the time of this call.
     *
     * <p>Counters are read from {@link AtomicLong} fields without acquiring a lock, so
     * individual counters are always accurate but may not reflect a single atomic instant
     * under concurrent access. {@code loadCount} and {@code totalLoadTimeNanos} are always
     * zero (no read-through loading in this phase).
     *
     * @return a {@link CacheStats} snapshot; never {@code null}
     */
    @Override
    public CacheStats stats() {
        return new CacheStats(
            hitCount.get(),
            missCount.get(),
            evictionCount.get(),
            expirationCount.get(),
            0L,
            0L,
            System.currentTimeMillis()
        );
    }

    /**
     * Shuts down the background expiry cleaner and marks this cache as closed.
     *
     * <p>Idempotent — safe to call multiple times. Any in-flight sweep is allowed to finish
     * ({@link ScheduledExecutorService#shutdown()} does not interrupt running tasks).
     * Cache operations after {@code close()} are undefined.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cleaner.shutdown();
        }
    }

}
