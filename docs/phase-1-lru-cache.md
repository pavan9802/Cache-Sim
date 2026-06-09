# Phase 1 — Core Cache: Correct Before Fast

## Context & Purpose

Build a correct, thread-safe LRU cache with TTL expiry. The mandate of this phase is
**correctness over performance**. Do not optimize. Do not use advanced concurrency primitives.
The goal is a working cache that passes all tests — performance improvements come in Phases 2 and 3.

By the end of this phase, `LRUCache` is the benchmark target. Phase 0's stub will be replaced
by `LRUCache` in all benchmark runs, establishing the Phase 1 baseline. Expect a significant
throughput drop vs the stub (3–5M vs 80–100M ops/sec) — that is correct and expected.

---

## Pre-conditions

- Phase 0 exit gate passed (all benchmarks running, baseline recorded)
- `Cache<K,V>` interface already exists from the Phase 0 pre-step
- `ConcurrentHashMapCache` stub compiles and passes smoke tests

---

## Files to Create

```
cache-core/src/main/java/com/hpcache/api/CacheStats.java
cache-core/src/main/java/com/hpcache/api/RemovalCause.java
cache-core/src/main/java/com/hpcache/api/RemovalListener.java
cache-core/src/main/java/com/hpcache/internal/entry/CacheEntry.java
cache-core/src/main/java/com/hpcache/impl/LRUCache.java
cache-core/src/main/java/com/hpcache/builder/CacheBuilder.java

cache-core/src/test/java/com/hpcache/impl/LRUCacheTest.java
cache-core/src/test/java/com/hpcache/impl/LRUCacheConcurrencyTest.java
```

**Update:**
```
cache-core/src/main/java/com/hpcache/api/Cache.java   ← replace placeholder stats() with real CacheStats
```

---

## Implementation Sequence

1. **`CacheStats` record** — no dependencies, pure data
   → Unit test: `hitRate()` = 0.0 when both counts are 0 (no divide-by-zero)

2. **`RemovalCause` enum + `RemovalListener` interface** — no dependencies
   → Compile check only

3. **`CacheEntry` record** (in `com.hpcache.internal.entry`)
   → Unit test: `isExpired()` returns false for `noExpiry()`, true after TTL passes

4. **`LRUCache` — basic put/get only** (no TTL, no background cleaner, no stats yet)
   → Unit test: put 3 items, get all 3, verify null on miss

5. **`LRUCache` — add eviction** (maxSize enforcement via `removeEldestEntry`)
   → Unit test: fill to maxSize+1, verify size == maxSize, verify LRU entry was evicted

6. **`LRUCache` — add TTL expiry** (per-entry TTL via `CacheEntry.withTtl()`)
   → Unit test: put with 100ms TTL, sleep 200ms, verify `get()` returns null

7. **`LRUCache` — add background cleaner** (`ScheduledExecutorService` daemon thread)
   → Unit test: put with 100ms TTL, sleep 2s, verify `size()` decreases (cleaner ran)

8. **`LRUCache` — add stats** (`AtomicLong` hit/miss/eviction counters)
   → Unit test: put 1 item, get it (hit), get missing key (miss), verify `stats().hitRate()`

9. **`LRUCache` — add `RemovalListener`** (fires on size eviction and TTL expiry)
   → Unit test: register listener, cause eviction, verify listener called with correct `RemovalCause`

10. **`CacheBuilder`** (wraps `LRUCache` construction with validation)
    → Unit test: `maxSize(0)` throws `IllegalStateException`, `ttl(-1)` throws, etc.

11. **Update `Cache<K,V>`** — replace `Object stats()` with `CacheStats stats()`

12. **Concurrency tests** — all 4 in `LRUCacheConcurrencyTest`

13. **Run benchmarks** → `results/phase-1-<timestamp>.json`

---

## Class-by-Class Spec

### `CacheStats` (record)

**Package:** `com.hpcache.api`

**Purpose:** Immutable snapshot of cache statistics at a point in time. Returned by
`Cache.stats()`. Intended for logging, monitoring, and the `StatsReporter`.

**Fields (all `final long`):**
```java
long hitCount
long missCount
long evictionCount
long loadCount
long totalLoadTimeNanos
long snapshotTimestampMs   // System.currentTimeMillis() at snapshot time
```

**Computed methods:**
```java
double hitRate()             // hitCount / (hitCount + missCount); 0.0 if both are 0
double missRate()            // 1.0 - hitRate()
double averageLoadTimeMs()   // totalLoadTimeNanos / loadCount / 1_000_000.0
long requestCount()          // hitCount + missCount
String formatted()           // human-readable multi-line string
```

#### Watch Out For
- `hitRate()` must guard against divide-by-zero: `requestCount() == 0 ? 0.0 : ...`
- `averageLoadTimeMs()` must guard against `loadCount == 0`
- This is a `record` — the compact constructor should not validate (stats can legitimately
  have all-zero values on a fresh cache)

---

### `RemovalCause` (enum) + `RemovalListener` (interface)

**Package:** `com.hpcache.api`

```java
public enum RemovalCause { SIZE, EXPIRED, EXPLICIT }

@FunctionalInterface
public interface RemovalListener<K, V> {
    void onRemoval(K key, V value, RemovalCause cause);
}
```

#### Watch Out For
- `RemovalListener` is called synchronously from within a write lock in Phase 1.
  A slow or throwing listener will block all cache operations. Document this clearly
  in Javadoc — users are responsible for keeping listeners fast and non-throwing.
- In Phase 1, if the listener throws, catch and log the exception (do not propagate —
  it would corrupt the lock state).

---

### `CacheEntry` (record)

**Package:** `com.hpcache.internal.entry`

**Purpose:** Internal wrapper pairing a value with its expiry time. Never exposed
in the public API — it is an implementation detail.

```java
record CacheEntry<V>(V value, long expiryNanos) {

    boolean isExpired() {
        return expiryNanos != Long.MAX_VALUE && System.nanoTime() > expiryNanos;
    }

    static <V> CacheEntry<V> noExpiry(V value) {
        return new CacheEntry<>(value, Long.MAX_VALUE);
    }

    static <V> CacheEntry<V> withTtl(V value, long ttlMs) {
        return new CacheEntry<>(value, System.nanoTime() + ttlMs * 1_000_000L);
    }
}
```

#### Watch Out For
- Use `System.nanoTime()` — **never** `System.currentTimeMillis()`. `nanoTime` is
  monotonically increasing and unaffected by NTP clock adjustments. Using `currentTimeMillis`
  means a clock-backward adjustment could make all entries appear unexpired indefinitely.
- `Long.MAX_VALUE` as the sentinel for "no expiry" is intentional. Any reasonable
  `System.nanoTime()` value will never reach `Long.MAX_VALUE` in practice.
- The `ttlMs * 1_000_000L` conversion: note the `L` suffix — without it, `ttlMs * 1_000_000`
  overflows `int` if `ttlMs > 2` seconds.

---

### `LRUCache` (main Phase 1 implementation)

**Package:** `com.hpcache.impl`

**Purpose:** Thread-safe LRU cache with TTL expiry and size-based eviction.
This is the simplest correct implementation — a single `ReentrantReadWriteLock` over
a `LinkedHashMap`. Expect poor concurrent performance (Phase 2 fixes this).

**Internal structure:**
```java
private final LinkedHashMap<K, CacheEntry<V>> map;   // accessOrder=true
private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
private final int maxSize;
private final long defaultTtlMs;
private final RemovalListener<K, V> removalListener;
private final ScheduledExecutorService cleaner;
private final AtomicLong hitCount = new AtomicLong();
private final AtomicLong missCount = new AtomicLong();
private final AtomicLong evictionCount = new AtomicLong();
```

**LinkedHashMap configuration:**
```java
this.map = new LinkedHashMap<>(capacity, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<K, CacheEntry<V>> eldest) {
        if (size() > maxSize) {
            evictionCount.incrementAndGet();
            notifyRemoval(eldest.getKey(), eldest.getValue(), RemovalCause.SIZE);
            return true;
        }
        return false;
    }
};
```

**`get(K key)` — THE MOST IMPORTANT CORRECTNESS DETAIL:**
```java
public V get(K key) {
    lock.writeLock().lock();          // NOT readLock — see Watch Out For
    try {
        CacheEntry<V> entry = map.get(key);
        if (entry == null) {
            missCount.incrementAndGet();
            return null;
        }
        if (entry.isExpired()) {
            map.remove(key);
            evictionCount.incrementAndGet();
            missCount.incrementAndGet();
            notifyRemoval(key, entry.value(), RemovalCause.EXPIRED);
            return null;
        }
        hitCount.incrementAndGet();
        return entry.value();
    } finally {
        lock.writeLock().unlock();
    }
}
```

**Background TTL cleaner:**
```java
private final ScheduledExecutorService cleaner =
    Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cache-cleaner");
        t.setDaemon(true);   // MUST be daemon — see Watch Out For
        return t;
    });

// In constructor:
cleaner.scheduleAtFixedRate(this::removeExpiredEntries, 1, 1, TimeUnit.SECONDS);

private void removeExpiredEntries() {
    lock.writeLock().lock();
    try {
        map.entrySet().removeIf(e -> {
            if (e.getValue().isExpired()) {
                evictionCount.incrementAndGet();
                notifyRemoval(e.getKey(), e.getValue().value(), RemovalCause.EXPIRED);
                return true;
            }
            return false;
        });
    } finally {
        lock.writeLock().unlock();
    }
}
```

**`close()` — must be idempotent:**
```java
private final AtomicBoolean closed = new AtomicBoolean(false);

@Override
public void close() {
    if (closed.compareAndSet(false, true)) {
        cleaner.shutdown();
    }
}
```

#### Watch Out For

**THE CRITICAL BUG: `get()` must use `writeLock`, not `readLock`.**

`LinkedHashMap` with `accessOrder=true` moves the accessed entry to the tail of the
doubly-linked list on every `get()`. This modifies the map's internal structure — it is
a write operation. If you use `readLock`, multiple concurrent `get()` calls will simultaneously
mutate the linked list, causing ConcurrentModificationException or silent data corruption.

This is the most common mistake in naive LRU implementations. It is also the most common
interview question about this exact design. Know this cold.

**Daemon thread:** The cleaner thread must be a daemon thread. If `setDaemon(true)` is not
called, the JVM will not exit after your test finishes — it will hang waiting for the
non-daemon cleaner thread to terminate. This is one of the hardest-to-debug hangs in Java.

**`removeEldestEntry` and the RemovalListener:** `removeEldestEntry` is called while the
map is being mutated (inside `put`). The `RemovalListener` callback fires inside this method.
If the listener throws, it will propagate out of `put()`. Always wrap listener calls in
try-catch.

**`close()` idempotency:** `ScheduledExecutorService.shutdown()` is idempotent by contract,
but calling `close()` from multiple threads without the `AtomicBoolean` guard can cause
confusion about the closed state. The guard makes the semantics explicit.

---

### `CacheBuilder`

**Package:** `com.hpcache.builder`

**Purpose:** Fluent builder — the only way users should construct a cache. Enforces
validation and provides a clean API.

**Full API:**
```java
Cache<String, MarketEvent> cache = CacheBuilder.<String, MarketEvent>newBuilder()
    .maxSize(10_000)
    .ttl(30, TimeUnit.SECONDS)
    .recordStats()
    .removalListener((key, value, cause) -> log.info("Evicted: {}", key))
    .build();
```

**Validation in `build()`:**
- `maxSize` must be > 0 — throw `IllegalStateException("maxSize must be > 0, got: " + maxSize)`
- `ttl` must be > 0 if set — throw `IllegalStateException`
- Missing `maxSize` (never set) — throw `IllegalStateException("maxSize is required")`

#### Watch Out For
- The builder must be generic: `CacheBuilder<K, V>`. The static factory method is:
  `public static <K, V> CacheBuilder<K, V> newBuilder()`. This is awkward Java syntax —
  the type witness `CacheBuilder.<String, MarketEvent>newBuilder()` is required at the
  call site if the compiler can't infer it.
- `recordStats()` is a boolean flag. In Phase 1, stats are always recorded (all LRUCache
  instances have AtomicLong counters). In a future phase, stats recording could be made
  optional to reduce overhead. Keep the flag for API compatibility even if it's a no-op now.

---

## Test Strategy

### Unit Tests (`LRUCacheTest`)

| Test | Invariant proved |
|---|---|
| `get` on empty cache returns null | Null-on-miss contract holds |
| `put` then `get` same key returns value | Basic storage works |
| Cache at maxSize + 1 has size == maxSize | Size enforcement works |
| Least-recently-used entry is evicted first | LRU ordering is correct |
| `put` with 100ms TTL, sleep 200ms, `get` returns null | TTL expiry via get() works |
| `put` with 100ms TTL, sleep 2s, `size()` decreases | Background cleaner runs |
| Hit count increments on hit | Stats tracking correct |
| Miss count increments on miss and expired entry | Stats tracking correct |
| Eviction count increments on size eviction | Stats tracking correct |
| `RemovalListener` called with `SIZE` cause on eviction | Listener fires correctly |
| `RemovalListener` called with `EXPIRED` cause on TTL expiry | Listener fires correctly |
| `close()` twice does not throw | Idempotency |
| `CacheBuilder` with `maxSize(0)` throws | Builder validation |

### Concurrency Tests (`LRUCacheConcurrencyTest`)

| Test | Invariant proved | `@RepeatedTest` | jcstress |
|---|---|---|---|
| 16 threads each writing 10k entries — no exceptions, `size() <= maxSize` | No data corruption under concurrent writes | 50 | N |
| `put` with 100ms TTL, sleep 200ms, `get` from 8 threads all return null | TTL expiry thread-safe | 50 | N |
| Fill to maxSize+1000, verify `evictionCount == 1000` | Eviction count atomic accuracy | 100 | N |
| 8 reader + 4 writer threads on same key for 5s — completes within 10s | No deadlock | 20 (with 10s timeout assertion) | N |
| `get()` writeLock correctness — LinkedHashMap not corrupted under 16 threads | The critical writeLock bug does not exist | 100 | **Y** (jcstress) |

**jcstress test for writeLock (`WriteLockStressTest`):**
```java
@JCStressTest
@Outcome(id = "...", expect = Expect.ACCEPTABLE)
@State
public class WriteLockStressTest {
    LRUCache<String, Integer> cache = new LRUCache<>(100, -1);
    { cache.put("key", 1); }

    @Actor public void actor1() { cache.get("key"); }
    @Actor public void actor2() { cache.get("key"); }
    @Arbiter public void arbiter(II_Result r) {
        r.r1 = cache.size();  // must always be 1
    }
}
```

### Benchmark Validation

After step 13, run `ReadHeavyBenchmark` with `LRUCache`:
- **Expected:** 3–5M ops/sec (8 threads, read-heavy 95/5)
- **If > 10M:** Likely not using writeLock — investigate lock usage
- **If < 1M:** Something pathological — profile with async-profiler

---

## Exit Gate

### Correctness
- [ ] All unit tests in `LRUCacheTest` pass, zero failures across `@RepeatedTest(10)`
- [ ] All concurrency tests pass per the table above (50/100 repetitions, jcstress clean)
- [ ] jcstress `WriteLockStressTest`: zero forbidden outcomes
- [ ] Suite re-run 3 times: identical results each time

### Performance
- [ ] Benchmark baseline recorded to `results/phase-1-<timestamp>.json`
- [ ] No benchmark errors or NaN
- [ ] Throughput is in the 1–10M ops/sec range (confirms LRU overhead is present)

### Code Quality
- [ ] `/concurrency-review` on all Phase 1 files: zero findings
- [ ] `/java-best-practices` on all Phase 1 files: zero MUST FIX findings
- [ ] `/abstraction-check`: zero MUST FIX findings

---

## Open Questions

- [ ] Should `LRUCache` implement `Closeable` (Java interface) so it works in
      try-with-resources? Yes — add to Phase 6 API cleanup checklist. Note it here.
- [ ] Should expired entries be counted as evictions (`evictionCount`) or as a separate
      `expirationCount`? The plan combines them. Consider separating for better observability.
      Decision needed before implementing stats.
- [ ] The background cleaner runs every 1 second. Should the interval be configurable
      via `CacheBuilder`? Recommendation: yes, but with a default of 1s. Add
      `.cleanupInterval(long, TimeUnit)` to the builder.
