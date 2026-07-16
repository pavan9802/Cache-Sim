# Phase 1 Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the remaining Phase 1 work from `docs/phase-1-lru-cache.md`: split the combined eviction/expiration counters, make the background TTL-cleanup interval configurable, build the real `CacheBuilder` fluent API, and add the full unit/concurrency/jcstress/benchmark coverage the Phase 1 exit gate requires.

**Architecture:** `LRUCache` keeps the single `ReentrantReadWriteLock`-guarded `LinkedHashMap` design already committed in Phase 1 — this plan only extends its stats tracking and cleaner configuration, and adds `CacheBuilder` as the sole supported construction path per `docs/phase-1-lru-cache.md`. Tests are added in three layers matching CLAUDE.md's Concurrency Testing Standards table: functional unit tests, threaded concurrency tests under `@RepeatedTest`, and one jcstress memory-model test for the `get()` write-lock invariant.

**Tech Stack:** Java 21, JUnit 5 (Jupiter), jcstress (`cache-stress` module), JMH (`cache-benchmark` module), SLF4J/Logback, HdrHistogram.

## Global Constraints

Copied verbatim from `CLAUDE.md` — every task below implicitly includes these:

- `cache-core` → zero dependencies on other modules; no frameworks; no Caffeine/Guava.
- **`LRUCache.get()` must use `writeLock`** — never `readLock`. Do not touch this in any task; verify it is unchanged after every edit to `LRUCache.java`.
- **TTL must use `System.nanoTime()`** — never `System.currentTimeMillis()`.
- **Background threads must be daemon threads** — `Thread.ofVirtual()...factory()` already satisfies this; keep using it for any new executor.
- **`StampedLock` is not reentrant** — not applicable to this plan (Phase 1 uses `ReentrantReadWriteLock` only).
- Every `lock.lock()` has a corresponding `finally { lock.unlock(); }` block.
- Java `record` for immutable data (`CacheStats`).
- `final` on all fields that do not change after construction.
- No `Optional<V>` returned from `Cache.get()` — `null` is the correct miss signal.
- `ThreadLocalRandom`, not `new Random()`, in any concurrent context.
- Thread safety documented on every class: `@ThreadSafe` or `@NotThreadSafe`.
- No `printStackTrace()` — use SLF4J.
- Java 21 — virtual threads for background/async work: `Thread.ofVirtual().name("...").factory()`.
- Concurrency Testing Standards (minimum repetitions):

  | Test type | Minimum repetitions |
  |---|---|
  | Thread-safety (no data corruption) | `@RepeatedTest(50)` |
  | Critical paths (write-lock, thundering herd) | `@RepeatedTest(100)` |
  | Deadlock tests | `@RepeatedTest(20)` + timeout assertion |
  | Memory model assertions | jcstress — zero forbidden outcomes |

- A single failure in any repeated run is a failed gate — investigate, do not retry away.

---

### Task 1: `CacheStats` — split `expirationCount` from `evictionCount`

**Files:**
- Modify: `cache-core/src/main/java/com/hpcache/api/CacheStats.java`
- Test: `cache-core/src/test/java/com/hpcache/api/CacheStatsTest.java`

**Interfaces:**
- Produces: `CacheStats(long hitCount, long missCount, long evictionCount, long expirationCount, long loadCount, long totalLoadTimeNanos, long snapshotTimestampMs)` — this exact field order and arity is consumed by Task 2 (`LRUCache.stats()`).

- [ ] **Step 1: Write the failing test**

Create `cache-core/src/test/java/com/hpcache/api/CacheStatsTest.java`:

```java
package com.hpcache.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CacheStatsTest {

    @Test
    void hitRateIsZeroWhenNoRequestsRecorded() {
        CacheStats stats = new CacheStats(0, 0, 0, 0, 0, 0, 0);
        assertEquals(0.0, stats.hitRate());
    }

    @Test
    void hitRateComputesFromHitsAndMisses() {
        CacheStats stats = new CacheStats(3, 1, 0, 0, 0, 0, 0);
        assertEquals(0.75, stats.hitRate());
    }

    @Test
    void evictionAndExpirationCountsAreIndependent() {
        CacheStats stats = new CacheStats(0, 0, 5, 2, 0, 0, 0);
        assertEquals(5, stats.evictionCount());
        assertEquals(2, stats.expirationCount());
    }

    @Test
    void averageLoadTimeMsIsZeroWhenNoLoadsRecorded() {
        CacheStats stats = new CacheStats(0, 0, 0, 0, 0, 0, 0);
        assertEquals(0.0, stats.averageLoadTimeMs());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl cache-core test -Dtest=CacheStatsTest`
Expected: COMPILE ERROR — `CacheStats(int,int,int,int,int,int,int)` has no 7-arg constructor matching (current record has 6 fields).

- [ ] **Step 3: Add `expirationCount` to `CacheStats`**

Replace the full contents of `cache-core/src/main/java/com/hpcache/api/CacheStats.java`:

```java
package com.hpcache.api;

public record CacheStats(
    long hitCount,
    long missCount,
    long evictionCount,
    long expirationCount,
    long loadCount,
    long totalLoadTimeNanos,
    long snapshotTimestampMs)
{
    public double hitRate() {
        long total = hitCount + missCount;
        return total == 0 ? 0.0 : (double) hitCount / total;
    }

    public double missRate() {
        return requestCount() == 0 ? 0.0 : 1.0 - hitRate();
    }

    public double averageLoadTimeMs() {
        return loadCount == 0 ? 0.0 : (double) totalLoadTimeNanos / loadCount / 1_000_000.0;
    }

    public long requestCount() {
        return hitCount + missCount;
    }

    public String formatted() {
        return String.format(
            "CacheStats{hitCount=%d, missCount=%d, evictionCount=%d, expirationCount=%d, loadCount=%d, totalLoadTimeNanos=%d, snapshotTimestampMs=%d, hitRate=%.2f%%, missRate=%.2f%%, averageLoadTime=%.2fms}",
            hitCount,
            missCount,
            evictionCount,
            expirationCount,
            loadCount,
            totalLoadTimeNanos,
            snapshotTimestampMs,
            hitRate() * 100,
            missRate() * 100,
            averageLoadTimeMs()
        );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl cache-core test -Dtest=CacheStatsTest`
Expected: PASS (4/4)

- [ ] **Step 5: Commit**

```bash
git add cache-core/src/main/java/com/hpcache/api/CacheStats.java cache-core/src/test/java/com/hpcache/api/CacheStatsTest.java
git commit -m "feat: split expirationCount out of evictionCount in CacheStats"
```

---

### Task 2: `LRUCache` — separate expiration counter + configurable cleanup interval

**Files:**
- Modify: `cache-core/src/main/java/com/hpcache/impl/LRUCache.java`
- Create: `cache-core/src/test/java/com/hpcache/impl/LRUCacheTest.java`

**Interfaces:**
- Consumes: `CacheStats(hitCount, missCount, evictionCount, expirationCount, loadCount, totalLoadTimeNanos, snapshotTimestampMs)` from Task 1.
- Produces (consumed by Tasks 3, 4, 5, 6):
  - `LRUCache(int maxSize, long defaultTtlMs)`
  - `LRUCache(int maxSize, long defaultTtlMs, RemovalListener<K,V> removalListener)`
  - `LRUCache(int maxSize, long defaultTtlMs, RemovalListener<K,V> removalListener, long cleanupIntervalMs)` — **new**, owns its own cleaner thread
  - `LRUCache(int maxSize, long defaultTtlMs, RemovalListener<K,V> removalListener, ScheduledExecutorService cleaner)`
  - `LRUCache(int maxSize, long defaultTtlMs, RemovalListener<K,V> removalListener, ScheduledExecutorService cleaner, long cleanupIntervalMs)` — **new**, most complete overload
  - `cache.stats().evictionCount()` — increments only on `RemovalCause.SIZE`
  - `cache.stats().expirationCount()` — increments only on `RemovalCause.EXPIRED`

- [ ] **Step 1: Write the failing test**

Create `cache-core/src/test/java/com/hpcache/impl/LRUCacheTest.java`:

```java
package com.hpcache.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LRUCacheTest {

    private LRUCache<String, String> cache;

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void evictionCountTracksOnlySizeEvictions() {
        cache = new LRUCache<>(2, -1);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");
        assertEquals(1, cache.stats().evictionCount());
        assertEquals(0, cache.stats().expirationCount());
    }

    @Test
    void expirationCountTracksOnlyTtlExpiry() throws InterruptedException {
        cache = new LRUCache<>(10, -1, null, 50L);
        cache.put("a", "1", 50L);
        Thread.sleep(300);
        assertNull(cache.get("a"));
        assertEquals(0, cache.stats().evictionCount());
        assertEquals(1, cache.stats().expirationCount());
    }

    @Test
    void cleanupIntervalIsConfigurable() throws InterruptedException {
        cache = new LRUCache<>(10, -1, null, 50L);
        cache.put("a", "1", 50L);
        Thread.sleep(300);
        assertEquals(0, cache.size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl cache-core test -Dtest=LRUCacheTest`
Expected: COMPILE ERROR — no `LRUCache(int, long, RemovalListener, long)` constructor exists yet.

- [ ] **Step 3: Add the `expirationCount` field and the `DEFAULT_CLEANUP_INTERVAL_MS` constant**

In `cache-core/src/main/java/com/hpcache/impl/LRUCache.java`, replace:

```java
    private static final Logger log = LoggerFactory.getLogger(LRUCache.class);
    private final LinkedHashMap<K, CacheEntry<V>> map;   // accessOrder=true
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final int maxSize;
    private final long defaultTtlMs;
    private final RemovalListener<K, V> removalListener;
    private final ScheduledExecutorService cleaner;
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();
    private final AtomicLong evictionCount = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean(false);
```

with:

```java
    private static final Logger log = LoggerFactory.getLogger(LRUCache.class);
    private static final long DEFAULT_CLEANUP_INTERVAL_MS = 1000L;
    private final LinkedHashMap<K, CacheEntry<V>> map;   // accessOrder=true
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final int maxSize;
    private final long defaultTtlMs;
    private final RemovalListener<K, V> removalListener;
    private final ScheduledExecutorService cleaner;
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();
    private final AtomicLong evictionCount = new AtomicLong();
    private final AtomicLong expirationCount = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean(false);
```

- [ ] **Step 4: Replace the constructors to accept a configurable cleanup interval**

Replace the three existing constructors (from the full-arg constructor through the 2-arg convenience constructor):

```java
    public LRUCache(int maxSize, long defaultTtlMs, RemovalListener<K, V> removalListener, ScheduledExecutorService cleaner) {
        this.maxSize = maxSize;
        this.defaultTtlMs = defaultTtlMs;
        this.removalListener = removalListener;
        this.cleaner = cleaner;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, CacheEntry<V>> eldest) {
                if (size() > maxSize) {
                    evictionCount.incrementAndGet();
                    notifyRemoval(eldest.getKey(), eldest.getValue().value(), RemovalCause.SIZE);
                    return true;
                }
                return false;
            }
        };
        cleaner.scheduleAtFixedRate(this::removeExpiredEntries, 1, 1, TimeUnit.SECONDS);
    }

    public LRUCache(int maxSize, long defaultTtlMs, RemovalListener<K, V> removalListener) {
        this(maxSize, defaultTtlMs, removalListener,
            Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("cache-cleaner").factory()
            ));
    }

    public LRUCache(int maxSize, long defaultTtlMs) {
        this(maxSize, defaultTtlMs, null);
    }
```

with:

```java
    /**
     * Full constructor for testing and dependency injection.
     *
     * @param maxSize           maximum number of entries before LRU eviction; must be &gt; 0
     * @param defaultTtlMs      TTL applied by {@link #put(Object, Object)} when no per-entry
     *                          TTL is given; {@code <= 0} means entries never expire
     * @param removalListener   callback fired on eviction, expiry, or explicit removal;
     *                          {@code null} disables notifications
     * @param cleaner           executor used for the background expiry sweep
     * @param cleanupIntervalMs how often, in milliseconds, the background sweep runs
     */
    public LRUCache(int maxSize, long defaultTtlMs, RemovalListener<K, V> removalListener,
                     ScheduledExecutorService cleaner, long cleanupIntervalMs) {
        this.maxSize = maxSize;
        this.defaultTtlMs = defaultTtlMs;
        this.removalListener = removalListener;
        this.cleaner = cleaner;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, CacheEntry<V>> eldest) {
                if (size() > maxSize) {
                    evictionCount.incrementAndGet();
                    notifyRemoval(eldest.getKey(), eldest.getValue().value(), RemovalCause.SIZE);
                    return true;
                }
                return false;
            }
        };
        cleaner.scheduleAtFixedRate(this::removeExpiredEntries, cleanupIntervalMs, cleanupIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Constructor with an externally supplied cleaner, defaulting the sweep interval to
     * {@value #DEFAULT_CLEANUP_INTERVAL_MS}ms.
     */
    public LRUCache(int maxSize, long defaultTtlMs, RemovalListener<K, V> removalListener, ScheduledExecutorService cleaner) {
        this(maxSize, defaultTtlMs, removalListener, cleaner, DEFAULT_CLEANUP_INTERVAL_MS);
    }

    /**
     * Constructs an instance with an owned virtual-thread cleaner running at
     * {@code cleanupIntervalMs}.
     *
     * @param maxSize           maximum number of entries before LRU eviction; must be &gt; 0
     * @param defaultTtlMs      default TTL in milliseconds; {@code <= 0} means no expiry
     * @param removalListener   removal callback; {@code null} disables notifications
     * @param cleanupIntervalMs how often, in milliseconds, the background sweep runs
     */
    public LRUCache(int maxSize, long defaultTtlMs, RemovalListener<K, V> removalListener, long cleanupIntervalMs) {
        this(maxSize, defaultTtlMs, removalListener,
            Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("cache-cleaner").factory()
            ),
            cleanupIntervalMs);
    }

    /**
     * Constructs an instance with an owned virtual-thread cleaner running at the default
     * {@value #DEFAULT_CLEANUP_INTERVAL_MS}ms interval.
     *
     * @param maxSize         maximum number of entries before LRU eviction; must be &gt; 0
     * @param defaultTtlMs    default TTL in milliseconds; {@code <= 0} means no expiry
     * @param removalListener removal callback; {@code null} disables notifications
     */
    public LRUCache(int maxSize, long defaultTtlMs, RemovalListener<K, V> removalListener) {
        this(maxSize, defaultTtlMs, removalListener, DEFAULT_CLEANUP_INTERVAL_MS);
    }

    /**
     * Constructs an instance with no removal listener and the default cleanup interval.
     *
     * @param maxSize      maximum number of entries before LRU eviction; must be &gt; 0
     * @param defaultTtlMs default TTL in milliseconds; {@code <= 0} means no expiry
     */
    public LRUCache(int maxSize, long defaultTtlMs) {
        this(maxSize, defaultTtlMs, null);
    }
```

- [ ] **Step 5: Split `evictionCount` from `expirationCount` in `get()`**

Replace:

```java
            if (entry.isExpired()) {
                map.remove(key);
                evictionCount.incrementAndGet();
                missCount.incrementAndGet();
                notifyRemoval(key, entry.value(), RemovalCause.EXPIRED);
                return null;
            }
```

with:

```java
            if (entry.isExpired()) {
                map.remove(key);
                expirationCount.incrementAndGet();
                missCount.incrementAndGet();
                notifyRemoval(key, entry.value(), RemovalCause.EXPIRED);
                return null;
            }
```

Also update the surrounding Javadoc line `An expired entry is eagerly removed and counted as both a miss and an eviction.` to read `An expired entry is eagerly removed and counted as both a miss and an expiration.`

- [ ] **Step 6: Split `evictionCount` from `expirationCount` in `stats()`**

Replace:

```java
    @Override
    public CacheStats stats() {
        return new CacheStats(
            hitCount.get(),
            missCount.get(),
            evictionCount.get(),
            0L,
            0L,
            System.currentTimeMillis()
        );
    }
```

with:

```java
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
```

- [ ] **Step 7: Split `evictionCount` from `expirationCount` in the background sweep**

Replace:

```java
    private void removeExpiredEntries() {
        lock.writeLock().lock();
        try {
            map.entrySet().removeIf(entry -> {
                if (entry.getValue().isExpired()) {
                    evictionCount.incrementAndGet();
                    notifyRemoval(entry.getKey(), entry.getValue().value(), RemovalCause.EXPIRED);
                    return true;
                }
                return false;
            });
        } finally {
            lock.writeLock().unlock();
        }
    }
```

with:

```java
    private void removeExpiredEntries() {
        lock.writeLock().lock();
        try {
            map.entrySet().removeIf(entry -> {
                if (entry.getValue().isExpired()) {
                    expirationCount.incrementAndGet();
                    notifyRemoval(entry.getKey(), entry.getValue().value(), RemovalCause.EXPIRED);
                    return true;
                }
                return false;
            });
        } finally {
            lock.writeLock().unlock();
        }
    }
```

Also update the method's Javadoc line `Each removed entry increments {@code evictionCount} and fires...` to read `Each removed entry increments {@code expirationCount} and fires...`.

- [ ] **Step 8: Run test to verify it passes**

Run: `mvn -pl cache-core test -Dtest=LRUCacheTest`
Expected: PASS (3/3)

- [ ] **Step 9: Commit**

```bash
git add cache-core/src/main/java/com/hpcache/impl/LRUCache.java cache-core/src/test/java/com/hpcache/impl/LRUCacheTest.java
git commit -m "feat: separate expirationCount from evictionCount and make LRUCache cleanup interval configurable"
```

---

### Task 3: `CacheBuilder` — fluent construction API

**Files:**
- Modify: `cache-core/src/main/java/com/hpcache/builder/CacheBuilder.java` (currently a broken, non-compiling stub)
- Create: `cache-core/src/test/java/com/hpcache/builder/CacheBuilderTest.java`

**Interfaces:**
- Consumes: `LRUCache(int maxSize, long defaultTtlMs, RemovalListener<K,V> removalListener, long cleanupIntervalMs)` from Task 2; `Cache<K,V>` interface (`get`, `put`, `close`, `size`).
- Produces (consumed by Task 7): `CacheBuilder.<K,V>newBuilder()`, `.maxSize(int)`, `.ttl(long, TimeUnit)`, `.cleanupInterval(long, TimeUnit)`, `.recordStats()`, `.removalListener(RemovalListener<K,V>)`, `.build(): Cache<K,V>`.

- [ ] **Step 1: Write the failing test**

Create `cache-core/src/test/java/com/hpcache/builder/CacheBuilderTest.java`:

```java
package com.hpcache.builder;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.hpcache.api.Cache;
import com.hpcache.api.RemovalCause;

import static org.junit.jupiter.api.Assertions.*;

class CacheBuilderTest {

    @Test
    void maxSizeZeroThrowsIllegalStateException() {
        var builder = CacheBuilder.<String, String>newBuilder().maxSize(0);
        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void missingMaxSizeThrowsIllegalStateException() {
        var builder = CacheBuilder.<String, String>newBuilder();
        IllegalStateException e = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(e.getMessage().contains("maxSize is required"));
    }

    @Test
    void zeroTtlThrowsIllegalStateException() {
        var builder = CacheBuilder.<String, String>newBuilder()
            .maxSize(10)
            .ttl(0, TimeUnit.SECONDS);
        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void negativeTtlThrowsIllegalStateException() {
        var builder = CacheBuilder.<String, String>newBuilder()
            .maxSize(10)
            .ttl(-5, TimeUnit.SECONDS);
        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void builtCacheStoresAndRetrievesValues() {
        Cache<String, String> cache = CacheBuilder.<String, String>newBuilder()
            .maxSize(10)
            .build();
        try {
            cache.put("k", "v");
            assertEquals("v", cache.get("k"));
        } finally {
            cache.close();
        }
    }

    @Test
    void removalListenerIsWiredToBuiltCache() {
        AtomicInteger evictions = new AtomicInteger();
        Cache<String, String> cache = CacheBuilder.<String, String>newBuilder()
            .maxSize(1)
            .removalListener((k, v, cause) -> {
                if (cause == RemovalCause.SIZE) {
                    evictions.incrementAndGet();
                }
            })
            .build();
        try {
            cache.put("a", "1");
            cache.put("b", "2");
            assertEquals(1, evictions.get());
        } finally {
            cache.close();
        }
    }

    @Test
    void customCleanupIntervalIsAppliedToBuiltCache() throws InterruptedException {
        Cache<String, String> cache = CacheBuilder.<String, String>newBuilder()
            .maxSize(10)
            .ttl(50, TimeUnit.MILLISECONDS)
            .cleanupInterval(50, TimeUnit.MILLISECONDS)
            .build();
        try {
            cache.put("k", "v");
            Thread.sleep(300);
            assertEquals(0, cache.size());
        } finally {
            cache.close();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl cache-core test -Dtest=CacheBuilderTest`
Expected: COMPILE ERROR — the current `CacheBuilder.newBuilder()` has an empty method body with no return statement.

- [ ] **Step 3: Implement `CacheBuilder`**

Replace the full contents of `cache-core/src/main/java/com/hpcache/builder/CacheBuilder.java`:

```java
package com.hpcache.builder;

import java.util.concurrent.TimeUnit;

import com.hpcache.api.Cache;
import com.hpcache.api.RemovalListener;
import com.hpcache.impl.LRUCache;

/**
 * Fluent builder — the only supported way to construct a {@link Cache}.
 */
public final class CacheBuilder<K, V> {
    private static final long DEFAULT_CLEANUP_INTERVAL_MS = 1000L;

    private int maxSize;
    private boolean maxSizeSet = false;
    private long ttlMs;
    private boolean ttlSet = false;
    private long cleanupIntervalMs = DEFAULT_CLEANUP_INTERVAL_MS;
    // no-op in Phase 1 — LRUCache always records stats; flag exists for forward API compatibility.
    private boolean recordStats = false;
    private RemovalListener<K, V> removalListener;

    private CacheBuilder() {
    }

    public static <K, V> CacheBuilder<K, V> newBuilder() {
        return new CacheBuilder<>();
    }

    public CacheBuilder<K, V> maxSize(int maxSize) {
        this.maxSize = maxSize;
        this.maxSizeSet = true;
        return this;
    }

    public CacheBuilder<K, V> ttl(long duration, TimeUnit unit) {
        this.ttlMs = unit.toMillis(duration);
        this.ttlSet = true;
        return this;
    }

    public CacheBuilder<K, V> cleanupInterval(long duration, TimeUnit unit) {
        this.cleanupIntervalMs = unit.toMillis(duration);
        return this;
    }

    public CacheBuilder<K, V> recordStats() {
        this.recordStats = true;
        return this;
    }

    public CacheBuilder<K, V> removalListener(RemovalListener<K, V> removalListener) {
        this.removalListener = removalListener;
        return this;
    }

    public Cache<K, V> build() {
        if (!maxSizeSet) {
            throw new IllegalStateException("maxSize is required");
        }
        if (maxSize <= 0) {
            throw new IllegalStateException("maxSize must be > 0, got: " + maxSize);
        }
        if (ttlSet && ttlMs <= 0) {
            throw new IllegalStateException("ttl must be > 0, got: " + ttlMs);
        }
        if (cleanupIntervalMs <= 0) {
            throw new IllegalStateException("cleanupInterval must be > 0, got: " + cleanupIntervalMs);
        }
        long effectiveTtlMs = ttlSet ? ttlMs : -1L;
        return new LRUCache<>(maxSize, effectiveTtlMs, removalListener, cleanupIntervalMs);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl cache-core test -Dtest=CacheBuilderTest`
Expected: PASS (7/7)

- [ ] **Step 5: Commit**

```bash
git add cache-core/src/main/java/com/hpcache/builder/CacheBuilder.java cache-core/src/test/java/com/hpcache/builder/CacheBuilderTest.java
git commit -m "feat: implement CacheBuilder fluent construction API"
```

---

### Task 4: `LRUCacheTest` — full functional coverage

**Files:**
- Modify: `cache-core/src/test/java/com/hpcache/impl/LRUCacheTest.java` (created in Task 2 with 3 tests; this task appends the rest)

**Interfaces:**
- Consumes: all `LRUCache` constructors from Task 2. No production code changes in this task — `LRUCache`'s public API is already complete.

- [ ] **Step 1: Add the remaining functional tests**

Add the following imports to the top of `cache-core/src/test/java/com/hpcache/impl/LRUCacheTest.java` (alongside the existing `org.junit.jupiter.api.AfterEach` / `org.junit.jupiter.api.Test` / static `Assertions.*` imports):

```java
import java.util.ArrayList;
import java.util.List;

import com.hpcache.api.RemovalCause;
```

Add these test methods inside the `LRUCacheTest` class, after the three existing tests:

```java
    @Test
    void getOnEmptyCacheReturnsNull() {
        cache = new LRUCache<>(10, -1);
        assertNull(cache.get("missing"));
    }

    @Test
    void putThenGetSameKeyReturnsValue() {
        cache = new LRUCache<>(10, -1);
        cache.put("k", "v");
        assertEquals("v", cache.get("k"));
    }

    @Test
    void sizeNeverExceedsMaxSize() {
        cache = new LRUCache<>(3, -1);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");
        cache.put("d", "4");
        assertEquals(3, cache.size());
    }

    @Test
    void leastRecentlyUsedEntryIsEvictedFirst() {
        cache = new LRUCache<>(2, -1);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.get("a");
        cache.put("c", "3");
        assertNull(cache.get("b"));
        assertEquals("1", cache.get("a"));
        assertEquals("3", cache.get("c"));
    }

    @Test
    void ttlExpiryViaGetReturnsNullAfterExpiry() throws InterruptedException {
        cache = new LRUCache<>(10, -1, null, 100_000L);
        cache.put("k", "v", 100L);
        Thread.sleep(200);
        assertNull(cache.get("k"));
    }

    @Test
    void hitCountIncrementsOnHit() {
        cache = new LRUCache<>(10, -1);
        cache.put("k", "v");
        cache.get("k");
        assertEquals(1, cache.stats().hitCount());
    }

    @Test
    void missCountIncrementsOnMissAndExpiredEntry() throws InterruptedException {
        cache = new LRUCache<>(10, -1, null, 100_000L);
        cache.get("missing");
        cache.put("k", "v", 50L);
        Thread.sleep(200);
        cache.get("k");
        assertEquals(2, cache.stats().missCount());
    }

    @Test
    void removalListenerCalledWithSizeCauseOnEviction() {
        List<RemovalCause> causes = new ArrayList<>();
        cache = new LRUCache<>(1, -1, (k, v, cause) -> causes.add(cause));
        cache.put("a", "1");
        cache.put("b", "2");
        assertEquals(List.of(RemovalCause.SIZE), causes);
    }

    @Test
    void removalListenerCalledWithExpiredCauseOnTtlExpiry() throws InterruptedException {
        List<RemovalCause> causes = new ArrayList<>();
        cache = new LRUCache<>(10, -1, (k, v, cause) -> causes.add(cause), 50L);
        cache.put("a", "1", 50L);
        Thread.sleep(300);
        assertEquals(List.of(RemovalCause.EXPIRED), causes);
    }

    @Test
    void closeIsIdempotent() {
        cache = new LRUCache<>(10, -1);
        assertDoesNotThrow(() -> {
            cache.close();
            cache.close();
        });
    }
```

- [ ] **Step 2: Run the full test class to verify everything passes**

Run: `mvn -pl cache-core test -Dtest=LRUCacheTest`
Expected: PASS (12/12)

- [ ] **Step 3: Run 50 repetitions to check for flakiness**

Run: `mvn -pl cache-core test -Dtest=LRUCacheTest -Dsurefire.rerunFailingTestsCount=0`

Then run the whole class three times in a row and confirm identical (all-green) results each time:

```bash
for i in 1 2 3; do mvn -pl cache-core test -Dtest=LRUCacheTest; done
```

Expected: PASS (12/12) all three times.

- [ ] **Step 4: Commit**

```bash
git add cache-core/src/test/java/com/hpcache/impl/LRUCacheTest.java
git commit -m "test: add full functional coverage for LRUCache"
```

---

### Task 5: `LRUCacheConcurrencyTest` — threaded correctness tests

**Files:**
- Create: `cache-core/src/test/java/com/hpcache/impl/LRUCacheConcurrencyTest.java`

**Interfaces:**
- Consumes: `LRUCache(int, long)`, `cache.put`, `cache.get`, `cache.size()`, `cache.stats().evictionCount()`, `cache.stats().expirationCount()` from Tasks 1–2. No production code changes.

- [ ] **Step 1: Write the concurrency test suite**

Create `cache-core/src/test/java/com/hpcache/impl/LRUCacheConcurrencyTest.java`:

```java
package com.hpcache.impl;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;

import static org.junit.jupiter.api.Assertions.*;

class LRUCacheConcurrencyTest {

    private LRUCache<String, Integer> cache;

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @RepeatedTest(50)
    void concurrentWritesFromManyThreadsCauseNoCorruption() throws InterruptedException {
        cache = new LRUCache<>(1000, -1);
        int threadCount = 16;
        int entriesPerThread = 10_000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger failures = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            for (int t = 0; t < threadCount; t++) {
                int threadId = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < entriesPerThread; i++) {
                            cache.put("t" + threadId + "-k" + i, i);
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "threads did not finish in time");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, failures.get());
        assertTrue(cache.size() <= 1000);
    }

    @RepeatedTest(50)
    void ttlExpiryVisibleToAllReaderThreads() throws InterruptedException {
        cache = new LRUCache<>(100, -1);
        cache.put("key", 1, 100L);
        Thread.sleep(200);
        int readerCount = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(readerCount);
        AtomicInteger staleReads = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(readerCount);
        try {
            for (int i = 0; i < readerCount; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (cache.get("key") != null) {
                            staleReads.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, staleReads.get());
    }

    @RepeatedTest(100)
    void evictionCountIsExactUnderConcurrentFill() throws InterruptedException {
        int maxSize = 1000;
        int overflow = 1000;
        cache = new LRUCache<>(maxSize, -1);
        int threadCount = 8;
        int totalPuts = maxSize + overflow;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            for (int t = 0; t < threadCount; t++) {
                int threadId = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = threadId; i < totalPuts; i += threadCount) {
                            cache.put("k" + i, i);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(overflow, cache.stats().evictionCount());
        assertEquals(0, cache.stats().expirationCount());
    }

    @RepeatedTest(20)
    void readersAndWritersOnSameKeyDoNotDeadlock() {
        cache = new LRUCache<>(10, -1);
        cache.put("shared", 0);
        int readerCount = 8;
        int writerCount = 4;
        long durationMs = 5000;
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(readerCount + writerCount);
            ExecutorService pool = Executors.newFixedThreadPool(readerCount + writerCount);
            try {
                long deadline = System.currentTimeMillis() + durationMs;
                for (int i = 0; i < readerCount; i++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            while (System.currentTimeMillis() < deadline) {
                                cache.get("shared");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                for (int i = 0; i < writerCount; i++) {
                    int writerId = i;
                    pool.submit(() -> {
                        try {
                            start.await();
                            int counter = 0;
                            while (System.currentTimeMillis() < deadline) {
                                cache.put("shared", writerId * 1_000_000 + counter++);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                done.await(durationMs + 5000, TimeUnit.MILLISECONDS);
            } finally {
                pool.shutdownNow();
            }
        });
    }
}
```

- [ ] **Step 2: Run the suite**

Run: `mvn -pl cache-core test -Dtest=LRUCacheConcurrencyTest`
Expected: PASS, all repetitions (50 + 50 + 100 + 20 = 220 total test invocations) green. This run is expected to take several minutes due to the deadlock test's 5-second workload × 20 repetitions.

- [ ] **Step 3: Re-run the full suite two more times to confirm no flakiness**

```bash
for i in 1 2 3; do mvn -pl cache-core test -Dtest=LRUCacheConcurrencyTest; done
```

Expected: identical (all-green) results each of the 3 runs. A single failure in any run means a real race condition — stop and investigate per CLAUDE.md; do not proceed by re-running until green.

- [ ] **Step 4: Commit**

```bash
git add cache-core/src/test/java/com/hpcache/impl/LRUCacheConcurrencyTest.java
git commit -m "test: add threaded concurrency tests for LRUCache"
```

---

### Task 6: jcstress `WriteLockStressTest` — memory-model verification for `get()`

**Files:**
- Create: `cache-stress/src/main/java/com/hpcache/stress/WriteLockStressTest.java`

**Interfaces:**
- Consumes: `LRUCache(int, long)`, `cache.put`, `cache.get`, `cache.size()` from Task 2.

**Context:** This is the jcstress test CLAUDE.md's Critical Correctness Rule #1 exists to catch: if `LRUCache.get()` ever used `readLock` instead of `writeLock`, concurrent `get()` calls would corrupt `LinkedHashMap`'s internal doubly-linked list (since `accessOrder=true` mutates it on every access). This test does not change `LRUCache` — it only proves the existing `writeLock` usage from Phase 1 is correct under jcstress's exhaustive interleaving.

- [ ] **Step 1: Write the jcstress test**

Create `cache-stress/src/main/java/com/hpcache/stress/WriteLockStressTest.java`:

```java
package com.hpcache.stress;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

import com.hpcache.impl.LRUCache;

@JCStressTest
@Outcome(id = "1", expect = Expect.ACCEPTABLE,
    desc = "The single entry survives concurrent get() calls uncorrupted.")
@State
public class WriteLockStressTest {
    private final LRUCache<String, Integer> cache = new LRUCache<>(100, -1);

    public WriteLockStressTest() {
        cache.put("key", 1);
    }

    @Actor
    public void actor1() {
        cache.get("key");
    }

    @Actor
    public void actor2() {
        cache.get("key");
    }

    @Arbiter
    public void arbiter(I_Result r) {
        r.r1 = cache.size();
    }
}
```

- [ ] **Step 2: Build the stress-test jar**

Run: `mvn -pl cache-stress -am package -DskipTests`
Expected: `BUILD SUCCESS`, producing `cache-stress/target/cache-stress.jar`.

- [ ] **Step 3: Run the jcstress test**

Run: `java -jar cache-stress/target/cache-stress.jar -t ".*WriteLockStressTest.*" -m quick`

Expected: the HTML/console report shows only outcome `1` (`size() == 1`) as ACCEPTABLE, with 100% of observed cases matching it. Any other observed `r1` value (0, 2, or higher) means the write-lock invariant is broken — stop and investigate per CLAUDE.md; this is the exact bug Critical Correctness Rule #1 warns about.

- [ ] **Step 4: Commit**

```bash
git add cache-stress/src/main/java/com/hpcache/stress/WriteLockStressTest.java
git commit -m "test: add jcstress WriteLockStressTest for LRUCache.get() write-lock invariant"
```

---

### Task 7: Benchmark baseline — wire `BenchmarkBase` to `LRUCache` via `CacheBuilder`

**Files:**
- Modify: `cache-benchmark/src/main/java/com/hpcache/bench/BenchmarkBase.java`

**Interfaces:**
- Consumes: `CacheBuilder.<K,V>newBuilder().maxSize(int).build(): Cache<K,V>` from Task 3.

**Context:** `BenchmarkBase` currently builds a `ConcurrentHashMapCache` (the Phase 0 stub) and labels every result `"phase": 0`. Per `docs/phase-1-lru-cache.md`, Phase 1's exit gate requires a baseline recorded from `LRUCache` instead, establishing the real Phase 1 throughput number that Phase 2A's striping work must beat by 2x.

- [ ] **Step 1: Switch `buildCache()` to build an `LRUCache` via `CacheBuilder`**

In `cache-benchmark/src/main/java/com/hpcache/bench/BenchmarkBase.java`, replace the import:

```java
import com.hpcache.api.Cache;
import com.hpcache.impl.ConcurrentHashMapCache;
```

with:

```java
import com.hpcache.api.Cache;
import com.hpcache.builder.CacheBuilder;
```

Replace:

```java
    private Cache<String, MarketEvent> buildCache() {
        var c = new ConcurrentHashMapCache<String, MarketEvent>();
        MarketEvent seed = new MarketEvent("SEED", 99.99, 100.01, 0L, System.nanoTime(), 0);
        for (int i = 0; i < INITIAL_CACHE_SIZE; i++) {
            c.put(keyUniverse[i], seed);
        }
        return c;
    }
```

with:

```java
    private Cache<String, MarketEvent> buildCache() {
        Cache<String, MarketEvent> c = CacheBuilder.<String, MarketEvent>newBuilder()
            .maxSize(KEY_UNIVERSE_SIZE)
            .build();
        MarketEvent seed = new MarketEvent("SEED", 99.99, 100.01, 0L, System.nanoTime(), 0);
        for (int i = 0; i < INITIAL_CACHE_SIZE; i++) {
            c.put(keyUniverse[i], seed);
        }
        return c;
    }
```

- [ ] **Step 2: Update `saveResults()` to label output as Phase 1**

Replace:

```java
            Path file = resultsDir.resolve("phase-0-" + getClass().getSimpleName() + "-" + timestamp + ".json");
            String json = """
                {
                  "phase": 0,
```

with:

```java
            Path file = resultsDir.resolve("phase-1-" + getClass().getSimpleName() + "-" + timestamp + ".json");
            String json = """
                {
                  "phase": 1,
```

- [ ] **Step 3: Compile and package the benchmark jar**

Run: `mvn -pl cache-benchmark -am package -DskipTests`
Expected: `BUILD SUCCESS`, producing `cache-benchmark/target/benchmarks.jar`.

- [ ] **Step 4: Run `ReadHeavyBenchmark` to record the Phase 1 baseline**

Run: `java -jar cache-benchmark/target/benchmarks.jar ReadHeavyBenchmark`

Expected: JMH completes 2 forks × (5 warmup + 10 measurement) iterations (this takes several minutes), then prints a latency histogram and `Results saved to .../results/phase-1-ReadHeavyBenchmark-<timestamp>.json`. Per `docs/phase-1-lru-cache.md`'s Benchmark Validation section, expect roughly 1–10M ops/sec on this workload (a large drop from the Phase 0 stub's 80–100M ops/sec is correct — it confirms `writeLock`-per-`get()` overhead is present, not a sign of a bug).

- [ ] **Step 5: Verify the result file**

Run: `ls results/phase-1-ReadHeavyBenchmark-*.json`
Expected: one JSON file listed, containing `"phase": 1`.

- [ ] **Step 6: Commit**

```bash
git add cache-benchmark/src/main/java/com/hpcache/bench/BenchmarkBase.java
git commit -m "feat: benchmark LRUCache via CacheBuilder, establishing the Phase 1 baseline"
```

Note: `results/*.json` is gitignored (see `.gitignore` — "JMH benchmark results... committed selectively"), so the produced baseline file is not part of this commit; it satisfies the exit gate by existing on disk for `/phase-exit-check 1` to find.

---

## After All Tasks: Phase 1 Exit Gate

This plan's tasks produce the code and test coverage the exit gate checks for. After Task 7 is complete and reviewed, run `/phase-exit-check 1` to verify the full gate from `CLAUDE.md`:

- Correctness Gate: `LRUCacheTest`, `CacheBuilderTest`, `CacheStatsTest`, `LRUCacheConcurrencyTest` all green across repeated runs; jcstress `WriteLockStressTest` clean.
- Performance Gate: `results/phase-1-*.json` baseline recorded from Task 7.
- Code Quality Gate: `/concurrency-review`, `/abstraction-check`, `/java-best-practices` report zero MUST FIX findings on all Phase 1 files (`CacheStats.java`, `LRUCache.java`, `CacheBuilder.java`).

Do not mark Phase 1 done, and do not begin Phase 2A (`docs/phase-2a-striped-cache.md`), until `/phase-exit-check 1` passes — per CLAUDE.md, phases are implemented strictly in order.
