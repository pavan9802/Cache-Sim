# Phase 2A — Striped Cache

## Context & Purpose

Phase 1's `LRUCache` uses a single `ReentrantReadWriteLock` covering the entire map. Every
thread — reader or writer — must acquire this lock. At 8 threads, they all queue up waiting
for each other. This is the contention cliff.

Lock striping solves this by dividing the cache into N independent segments. Keys hash to
segments — threads on different segments never contend. This is the same technique used by
`ConcurrentHashMap` before Java 8. The goal is near-linear throughput scaling up to N threads.

---

## Pre-conditions

- Phase 1 exit gate fully passed
- `LRUCache` passes all unit and concurrency tests
- Phase 1 benchmark baseline recorded in `results/`

---

## Files to Create

```
cache-core/src/main/java/com/hpcache/impl/StripedCache.java

cache-core/src/test/java/com/hpcache/impl/StripedCacheTest.java
cache-core/src/test/java/com/hpcache/impl/StripedCacheConcurrencyTest.java
```

**Update:**
```
cache-core/src/main/java/com/hpcache/builder/CacheBuilder.java  ← add .stripeCount(int) option
```
When `stripeCount > 1`, `build()` returns a `StripedCache` instead of `LRUCache`. Default
(no call to `stripeCount()`) continues to return `LRUCache`.

---

## Implementation Sequence

1. **`StripedCache` skeleton** — constructor creates N `LRUCache` segments, `stripeFor(key)`
   routes to the correct segment, `get/put/invalidate` delegate to the segment
   → Unit test: put 1000 items, get all 1000 — verify none are lost

2. **Wang hash in `stripeFor()`** — replace naive `hashCode() % N` with Wang hash
   → Unit test: distribution uniformity (see test strategy)

3. **Aggregate stats across segments** — `stats()` sums hitCount/missCount/evictionCount
   across all segments and returns a combined `CacheStats`
   → Unit test: put items that hit/miss across multiple segments, verify combined stats correct

4. **`size()` aggregation** — sum `segment.size()` across all segments
   → Verify at capacity: total size never exceeds `maxSize`

5. **`invalidateAll()`** — call `invalidateAll()` on each segment
   → Unit test: fill cache, invalidateAll, verify size == 0

6. **`close()`** — close all segments (shuts down each cleaner thread)
   → Verify JVM exits cleanly after close

7. **Concurrency tests**

8. **Run benchmarks** — compare vs Phase 1

---

## Class-by-Class Spec

### `StripedCache`

**Package:** `com.hpcache.impl`

**Purpose:** Wraps `N` independent `LRUCache` instances. Keys are deterministically routed
to segments via hash. No global lock — threads on different segments run completely in
parallel.

**Internal structure:**
```java
@ThreadSafe
public class StripedCache<K, V> implements Cache<K, V> {
    private final LRUCache<K, V>[] segments;
    private final int stripeCount;
    private final int stripeMask;   // stripeCount - 1 (requires power-of-two stripeCount)
```

**Constructor:**
```java
public StripedCache(int maxSize, long defaultTtlMs, int stripeCount,
                    RemovalListener<K, V> removalListener) {
    // stripeCount MUST be a power of two
    this.stripeCount = nextPowerOfTwo(stripeCount);
    this.stripeMask = this.stripeCount - 1;
    int segmentSize = Math.max(1, maxSize / this.stripeCount);

    this.segments = new LRUCache[this.stripeCount];
    for (int i = 0; i < this.stripeCount; i++) {
        segments[i] = new LRUCache<>(segmentSize, defaultTtlMs, removalListener);
    }
}
```

**Default stripe count:**
```java
private static int defaultStripeCount() {
    int cores = Runtime.getRuntime().availableProcessors();
    return nextPowerOfTwo(cores * 2);
}
```

**Stripe selection (Wang hash):**
```java
private int stripeFor(K key) {
    int h = key.hashCode();
    h = ((h >>> 16) ^ h) * 0x45d9f3b;
    h = ((h >>> 16) ^ h) * 0x45d9f3b;
    h = (h >>> 16) ^ h;
    return h & stripeMask;
}
```

**All operations delegate to the correct segment:**
```java
@Override
public V get(K key) {
    return segments[stripeFor(key)].get(key);
}

@Override
public void put(K key, V value) {
    segments[stripeFor(key)].put(key, value);
}
```

#### Watch Out For

**Power of two requirement.** `stripeMask = stripeCount - 1` only works if `stripeCount`
is a power of two. Using `h & stripeMask` is equivalent to `h % stripeCount` only in that
case. If a non-power-of-two is passed, silently round up to the next power of two in the
constructor (do not throw). Document this behavior.

**`nextPowerOfTwo` implementation:**
```java
private static int nextPowerOfTwo(int n) {
    return n <= 1 ? 1 : Integer.highestOneBit(n - 1) << 1;
}
```

**Segment size imbalance.** `maxSize / stripeCount` truncates. If `maxSize = 10_001` and
`stripeCount = 16`, each segment gets `625` entries, and the total capacity is `10_000`
(one entry lost to integer division). This is acceptable — document it. Do not attempt
to compensate with a "remainder" segment.

**Stats aggregation is a snapshot, not atomic.** When `stats()` reads each segment's
counters and sums them, a concurrent write to segment 5 may have incremented `hitCount`
between reading segment 4 and segment 5. This is fine — stats are always approximate in
a concurrent system. Do not lock all segments to get an "exact" snapshot (the overhead
defeats the purpose).

**`Wang hash and `null` keys.** `key.hashCode()` NPEs on null keys. Do not support null
keys — throw `NullPointerException` with a clear message in `get()` and `put()`.

---

## Test Strategy

### Unit Tests (`StripedCacheTest`)

| Test | Invariant proved |
|---|---|
| Put 1000 items, get all 1000 — none lost | Stripe routing is deterministic and covers all keys |
| Put same key twice — second value wins | Key update works correctly across stripe routing |
| Fill to maxSize+1000 — `size() <= maxSize` | Per-segment eviction respects total capacity limit |
| `invalidateAll()` — size becomes 0 | All segments cleared |
| Stats aggregation: hits/misses from multiple segments sum correctly | Stats aggregation correct |
| Default stripe count is a power of two | Constructor normalizes stripe count |
| `null` key throws NullPointerException | Null key handling |

### Concurrency Tests (`StripedCacheConcurrencyTest`)

| Test | Invariant proved | `@RepeatedTest` | jcstress |
|---|---|---|---|
| 16 threads writing 10k entries each — no exceptions, size ≤ maxSize | No cross-segment interference | 50 | N |
| Stripe distribution: 100k inserts — each stripe holds between 40% and 160% of expected `maxSize/N` | Wang hash distributes uniformly | 10 | N |
| 8 reader threads + 4 writer threads on different key ranges for 10s — throughput > 2x Phase 1 | Parallel execution on different stripes actually scales | 5 | N |

**Distribution uniformity test detail:**
```java
// Insert 100_000 unique keys
// Check: no single stripe has > 160% of (100_000 / stripeCount) keys
// This catches degenerate hash distributions
```

### Benchmark Validation

After step 8, run `ThreadScalingBenchmark` comparing `StripedCache` vs `LRUCache`:
- **Expected at 8 threads:** 15–25M ops/sec (3–5x improvement over Phase 1)
- **Expected scaling:** near-linear from 1 to N threads (where N = stripe count)
- **Improvement floor (exit gate):** Phase 2A must achieve **>= 2x Phase 1** throughput at 8 threads

---

## Exit Gate

### Correctness
- [ ] All unit tests pass, zero failures across `@RepeatedTest(10)`
- [ ] Distribution uniformity test: no stripe > 160% of expected share
- [ ] All concurrency tests pass per the table (50 repetitions)
- [ ] Suite re-run 3 times: identical results

### Performance
- [ ] Results recorded to `results/phase-2a-striped-<timestamp>.json`
- [ ] No regression vs Phase 1 on single-threaded workload
- [ ] **>= 2x Phase 1 throughput at 8 threads** on `ReadHeavyBenchmark`
- [ ] Throughput scaling chart: linear from 1 to stripe-count threads

### Code Quality
- [ ] `/concurrency-review` on `StripedCache.java`: zero findings
- [ ] `/java-best-practices` on `StripedCache.java`: zero MUST FIX findings
- [ ] `/abstraction-check`: zero MUST FIX findings

---

## Open Questions

- [ ] Should `StripedCache` be constructed via `CacheBuilder` or directly? Recommendation:
      add a `stripeCount(int)` option to `CacheBuilder` that selects `StripedCache` vs
      `LRUCache` automatically. If `stripeCount > 1`, return `StripedCache`.
- [ ] Per-segment TTL cleanup threads: each segment has its own daemon cleaner, meaning
      `stripeCount` daemon threads. At `stripeCount=16`, that's 16 daemon threads for cleanup.
      Is this acceptable? Recommendation: yes for Phase 2A, optimize in Phase 3 if needed.
