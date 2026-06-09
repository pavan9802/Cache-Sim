# Phase 2B — Stamped Cache (Optimistic Reads)

## Context & Purpose

`StripedCache` improves throughput by reducing lock contention across key ranges. But each
segment still uses a `ReentrantReadWriteLock` where even reads are blocking. Under a read-heavy
workload (95% reads), readers still queue up behind each other waiting for the write lock to
release.

`StampedLock` with optimistic reads changes the model: reads attempt to proceed *without
acquiring any lock at all*. If a concurrent write happened during the read, the validation
fails and the read falls back to a real read lock. Under low write rates, most reads succeed
without ever acquiring a lock — this is the throughput gain.

This is the technique used by `ConcurrentHashMap` in Java 8+ for its internal rehashing check.

---

## Pre-conditions

- Phase 2A exit gate fully passed
- `StripedCache` benchmarks recorded in `results/`
- Phase 1 baseline available for comparison

---

## Files to Create

```
cache-core/src/main/java/com/hpcache/impl/StampedCache.java

cache-core/src/test/java/com/hpcache/impl/StampedCacheTest.java
cache-core/src/test/java/com/hpcache/impl/StampedCacheConcurrencyTest.java
cache-stress/src/main/java/com/hpcache/stress/StampedLockStressTest.java
```

**Update:**
```
cache-core/src/main/java/com/hpcache/builder/CacheBuilder.java  ← add .optimisticReads(boolean) option
```
When `optimisticReads(true)` is set, `build()` returns a `StampedCache`. Combine with
`stripeCount()` (from Phase 2A) for a striped + optimistic configuration.

---

## Implementation Sequence

1. **`StampedCache` skeleton** — same structure as `LRUCache` but swap
   `ReentrantReadWriteLock` for `StampedLock`
   → Unit tests from `LRUCacheTest` all pass (functional correctness before concurrency work)

2. **Optimistic read path in `get()`** — add `tryOptimisticRead()` + `validate()` pattern
   → Unit test: still returns correct values under single-threaded access

3. **Fallback to read lock in `get()`** — when `validate()` returns false
   → Verify via a stress test that reads never return corrupted values

4. **Concurrency tests**

5. **jcstress stress test** (`StampedLockStressTest`)

6. **Run benchmarks** — compare vs Phase 1 and Phase 2A under both 95/5 and 50/50 workloads

---

## Class-by-Class Spec

### `StampedCache`

**Package:** `com.hpcache.impl`

**Purpose:** LRU cache using `StampedLock` with optimistic reads on the read path.
Functionally identical to `LRUCache` but with different locking semantics.

**Key structural difference from `LRUCache`:**
```java
private final StampedLock lock = new StampedLock();
// (not ReentrantReadWriteLock)
```

**`get(K key)` — optimistic read pattern:**
```java
@Override
public V get(K key) {
    // Attempt 1: optimistic read (no lock acquired at all)
    long stamp = lock.tryOptimisticRead();
    CacheEntry<V> entry = map.get(key);  // may see torn state if concurrent write

    if (!lock.validate(stamp)) {
        // A concurrent write happened — the read may be invalid. Fall back to real read lock.
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
```

**`put(K key, V value, long ttlMs)` — write path (unchanged from LRUCache logic):**
```java
@Override
public void put(K key, V value, long ttlMs) {
    long stamp = lock.writeLock();
    try {
        CacheEntry<V> e = ttlMs <= 0 ? CacheEntry.noExpiry(value) : CacheEntry.withTtl(value, ttlMs);
        map.put(key, e);
    } finally {
        lock.unlockWrite(stamp);
    }
}
```

#### Watch Out For

**`StampedLock` is NOT reentrant.** This is the single most important rule for this class.
If any method that acquires a `StampedLock` calls another method that also acquires the same
lock, it will deadlock. There is no exception, no error message — it just hangs.

To prevent this: never call `get()` from `put()`, never call any public method from within
a locked section. Keep locked sections minimal and ensure they call only private helpers
that do NOT acquire the lock.

**`accessOrder=true` and optimistic reads.** `LinkedHashMap` with `accessOrder=true` mutates
node order on every `get()`. With `StampedCache`, we have a conflict:
- Optimistic reads do not hold any lock, but `LinkedHashMap.get()` still mutates node order
- Under concurrent optimistic reads, two threads may both mutate the linked list simultaneously

**Resolution:** `StampedCache` cannot use `accessOrder=true` with optimistic reads safely.
Switch to `accessOrder=false` and implement LRU ordering manually (or accept that `StampedCache`
is an LFU-approximation rather than strict LRU). For Phase 2B, use `accessOrder=false` with
a documented note. Strict LRU ordering requires a write lock on every read — the optimistic
read benefit would be lost.

**When optimistic reads WIN vs LOSE:**
- Win: read-heavy workloads (< 10% writes), short read critical sections
- Lose: write-heavy workloads (> 30% writes) — `validate()` fails frequently, retry overhead
  cancels out the benefit
- This is why the benchmark must test BOTH 95/5 (ReadHeavy) and 50/50 (WriteHeavy) workloads

**`StampedLock` stamps are not locks.** The stamp returned by `readLock()` is an opaque token,
not a lock object. It must be passed back to `unlockRead(stamp)`. Storing stamps in fields is
dangerous — they expire. Always use them as local variables with try/finally.

---

## Test Strategy

### Unit Tests (`StampedCacheTest`)

All tests from `LRUCacheTest` should pass unchanged — `StampedCache` is functionally
equivalent. Copy the test class and change the cache instantiation. Additionally:

| Test | Invariant proved |
|---|---|
| All `LRUCacheTest` tests pass | Functional equivalence |
| `get()` returns correct value after 100 concurrent puts of the same key | Optimistic read falls back correctly under high write rate |
| `close()` twice does not throw | Idempotency |

### Concurrency Tests (`StampedCacheConcurrencyTest`)

| Test | Invariant proved | `@RepeatedTest` | jcstress |
|---|---|---|---|
| 8 reader + 4 writer threads for 10s — all reads return valid values (null or a value that was put) | Optimistic read fallback is correct | 100 | **Y** |
| No deadlock: method that holds writeLock does not call any method that acquires StampedLock | Non-reentrancy contract holds | 20 (with 10s timeout) | N |

**jcstress `StampedLockStressTest`:**
Verify that no read ever returns a value that was never put — i.e., the optimistic read
either returns a valid value or null, never a partially-written or corrupted value.

### Benchmark Validation

Run **both** workloads against `StampedCache` vs `LRUCache` and `StripedCache`:

| Workload | Expected StampedCache result |
|---|---|
| ReadHeavy (95/5, 8 threads) | >= 1.5x Phase 1 LRUCache (optimistic reads help significantly) |
| WriteHeavy (50/50, 8 threads) | May be similar to or slightly worse than Phase 1 (validate fails often) |

Document the **crossover point**: the write rate at which StampedCache stops being better
than LRUCache. This is a key interview talking point.

---

## Exit Gate

### Correctness
- [ ] All `LRUCacheTest` unit tests pass against `StampedCache`
- [ ] Concurrency tests pass (100 repetitions)
- [ ] jcstress `StampedLockStressTest`: zero forbidden outcomes
- [ ] Suite re-run 3 times: identical results

### Performance
- [ ] Results recorded: `results/phase-2b-stamped-<timestamp>.json`
- [ ] `ReadHeavyBenchmark`: `StampedCache` >= 1.5x `LRUCache` throughput at 8 threads
- [ ] `WriteHeavyBenchmark`: regression vs Phase 1 is < 20% (acceptable under this workload)
- [ ] Crossover write-rate documented in benchmark comments

### Code Quality
- [ ] `/concurrency-review` on `StampedCache.java`: zero findings
- [ ] `/java-best-practices`: zero MUST FIX findings

---

## Open Questions

- [ ] Since `StampedCache` cannot use `accessOrder=true` safely with optimistic reads,
      it is not a strict LRU cache. Should it be named `StampedApproximateCache` or
      documented clearly as "approximate LRU"? The benchmark will show whether this matters
      for hit rate.
- [ ] Should `StampedCache` be a standalone implementation or layer on top of `StripedCache`
      (striped + optimistic reads)? Phase 3's `TinyLFUCache` will combine the best of all
      phases — for now, keep them separate for educational clarity.
