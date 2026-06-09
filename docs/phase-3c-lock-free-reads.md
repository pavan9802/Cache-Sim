# Phase 3C — Lock-Free Read Path

## Context & Purpose

In Phase 3B, `TinyLFUCache.get()` still holds the `writeLock` because it calls
`policy.access(key)`, which mutates segment membership. This serializes all readers.

Caffeine's key insight: **read-side bookkeeping (frequency updates, access order) does not
need to be synchronous**. Buffer reads per-thread, drain the buffer during writes (which
already hold the lock). Reads proceed lock-free — at the cost of slightly stale eviction
metadata.

This phase implements that insight in three parts:
1. **`ReadBuffer`** — per-thread ring buffer, records reads without acquiring any lock
2. **`VarHandle` counters** — replace `AtomicLong` stat counters with lower-overhead VarHandle
3. **False sharing prevention** — pad counter fields to prevent cache-line thrashing

Together these optimizations should bring `TinyLFUCache` throughput from ~5M to 35–50M ops/sec.

---

## Pre-conditions

- Phase 3B exit gate fully passed (`TinyLFUCache` with correct hit rate)
- `VarHandle` and `@Contended` JVM flags configured in Maven Surefire (from Gap 3 fix)
- Understanding of Java Memory Model acquire/release semantics

---

## Files to Create

```
cache-core/src/main/java/com/hpcache/internal/buffer/ReadBuffer.java
cache-core/src/main/java/com/hpcache/internal/buffer/WriteBuffer.java     (optional)

cache-core/src/test/java/com/hpcache/internal/buffer/ReadBufferTest.java
cache-stress/src/main/java/com/hpcache/stress/CounterAtomicityStressTest.java
```

**Update:**
```
cache-core/src/main/java/com/hpcache/impl/TinyLFUCache.java   ← integrate ReadBuffer + VarHandle
```

---

## Implementation Sequence

1. **`ReadBuffer` — basic ring buffer, single-threaded correctness**
   → Unit test: record 16 reads, drain, verify all 16 processed in order

2. **`ReadBuffer` — verify overflow behavior (buffer size = 16, 17th write drops)**
   → Unit test: 17 records, drain, verify only 16 processed (oldest may be dropped)

3. **Integrate `ReadBuffer` into `TinyLFUCache.get()`**
   → `get()` records the key hash into the thread-local buffer but does NOT call `policy.access()`
   → `put()` drains the buffer and calls `policy.access()` for each buffered read
   → Run all `TinyLFUCacheTest` unit tests — must still pass (correctness preserved)

4. **`get()` now uses `readLock` instead of `writeLock`** (first lock-free improvement step)
   → Run `ReadHeavyBenchmark` — expect significant throughput improvement

5. **Replace `AtomicLong` counters with `VarHandle`** (`hitCount`, `missCount`, `evictionCount`)
   → Run `ReadHeavyBenchmark` again — measure improvement over step 4

6. **Add `@Contended` padding to counter fields**
   → Run counter throughput microbenchmark with 8 threads — measure vs without padding

7. **`CounterAtomicityStressTest`** (jcstress) — verify VarHandle counter semantics are correct

8. **Full benchmark comparison** — Phase 1, Phase 2A, Phase 2B, Phase 3B, Phase 3C, Caffeine

---

## Class-by-Class Spec

### `ReadBuffer`

**Package:** `com.hpcache.internal.buffer`

**Purpose:** Per-thread ring buffer for recording cache reads without acquiring any lock.
Each thread writes to its own buffer — zero contention. Reads are drained in batch during
write operations (which already hold the write lock).

**Design:**
```java
public class ReadBuffer<K> {
    private static final int BUFFER_SIZE = 16;    // power of two
    private static final int BUFFER_MASK = BUFFER_SIZE - 1;

    // One ring buffer per thread — no sharing, no contention
    private final ThreadLocal<K[]> buffer = ThreadLocal.withInitial(
        () -> (K[]) new Object[BUFFER_SIZE]
    );
    private final ThreadLocal<AtomicInteger> tail = ThreadLocal.withInitial(AtomicInteger::new);
```

**Recording a read (called from `get()` — must be near-zero cost):**
```java
public void record(K key) {
    K[] buf = buffer.get();
    AtomicInteger t = tail.get();
    int pos = t.get();
    buf[pos & BUFFER_MASK] = key;
    // CAS to advance tail — if it fails (extremely rare), we drop the event
    // Dropping is acceptable: approximate counts are sufficient for eviction decisions
    t.compareAndSet(pos, pos + 1);
}
```

**Draining (called from `put()` — already holds write lock):**
```java
public void drainTo(Consumer<K> processor) {
    K[] buf = buffer.get();
    AtomicInteger t = tail.get();
    int count = Math.min(t.getAndSet(0), BUFFER_SIZE);
    for (int i = 0; i < count; i++) {
        K key = buf[i];
        buf[i] = null;   // clear reference for GC
        if (key != null) {
            processor.accept(key);
        }
    }
}
```

#### Watch Out For

**BUFFER_SIZE must be a power of two.** The `& BUFFER_MASK` trick only works for powers of
two. Using `% BUFFER_SIZE` is correct but 3–5x slower on the hot path. Never change this
constant without also updating `BUFFER_MASK = BUFFER_SIZE - 1`.

**Why dropping events is acceptable.** The buffer can hold 16 reads. Under a burst of reads
between drains, events beyond 16 are dropped. This means some accesses are not counted in
the frequency sketch. The sketch is a probabilistic approximation anyway — a few missed
increments don't change eviction correctness. Caffeine uses the same bounded-loss approach.

**`drainTo` is NOT called from the same thread that wrote to the buffer.** The write lock
holder calls `drainTo`, which accesses the ThreadLocal of other threads. Wait — this doesn't
work. `ThreadLocal` can only be read by the thread that owns it.

**Resolution:** The `ReadBuffer` must be per-thread-per-cache, and draining happens on
the *current writing thread's own buffer* only. Each `put()` call drains the *calling thread's*
read buffer, not all threads' buffers. This means some reads are deferred until the next write
from that same thread. The sketch is approximate — this is fine. Caffeine uses the same model.

**GC reference clearing.** After draining, set `buf[i] = null`. Without this, the buffer
holds strong references to keys that may have been evicted, preventing GC.

---

### `VarHandle` Counter Replacement

**In `TinyLFUCache.java`:**

Replace:
```java
private final AtomicLong hitCount = new AtomicLong();
private final AtomicLong missCount = new AtomicLong();
private final AtomicLong evictionCount = new AtomicLong();
```

With:
```java
private volatile long hitCount;
private volatile long missCount;
private volatile long evictionCount;

private static final VarHandle HIT_COUNT;
private static final VarHandle MISS_COUNT;
private static final VarHandle EVICTION_COUNT;

static {
    try {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        HIT_COUNT = lookup.findVarHandle(TinyLFUCache.class, "hitCount", long.class);
        MISS_COUNT = lookup.findVarHandle(TinyLFUCache.class, "missCount", long.class);
        EVICTION_COUNT = lookup.findVarHandle(TinyLFUCache.class, "evictionCount", long.class);
    } catch (ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
    }
}
```

**Incrementing (use `getAndAddAcquire` for stats — not full volatile):**
```java
// Stats counters only need acquire/release ordering — not a full volatile fence
HIT_COUNT.getAndAddAcquire(this, 1L);
```

**Memory ordering — know this for interviews:**

| Method | Fence | Use when |
|---|---|---|
| `get()` / `set()` | Full volatile | Visibility required across threads |
| `getAcquire()` / `setRelease()` | One-way fence | Producer/consumer hand-off |
| `getOpaque()` / `setOpaque()` | No ordering | Best-effort (single-writer stats) |
| `getAndAddAcquire` | Acquire on the read | Stats increment with visibility |

For hit/miss/eviction counters: `getAndAddAcquire` is sufficient. The counter will be
visible to threads that acquire the lock after the increment, which is all threads that
read the stats.

#### Watch Out For

**`VarHandle` lookup requires the field to be in the class doing the lookup, or the lookup
must have access.** `lookup()` returns a lookup with the access rights of `TinyLFUCache`.
If you try to use this lookup to access a field in a superclass or another class, it may
fail with `IllegalAccessException`. Keep VarHandles in the same class as the field.

**`static` initializer exceptions.** If the VarHandle lookup fails (e.g., field renamed,
wrong type), the `ExceptionInInitializerError` wraps the cause. The class will fail to load
entirely. This is correct and better than a runtime NPE — but it means a field rename breaks
the class silently at test time. Add a simple unit test that loads `TinyLFUCache.class` to
catch this early.

---

### False Sharing Prevention (`@Contended`)

**Problem:** `hitCount`, `missCount`, and `evictionCount` are adjacent fields, likely on
the same CPU cache line (64 bytes). When 8 threads each increment different counters,
they invalidate each other's cache lines — forcing main memory round-trips.

**Fix using `@Contended` (requires `--add-opens` flag — see `docs/architecture.md`):**
```java
@jdk.internal.vm.annotation.Contended
private volatile long hitCount;

@jdk.internal.vm.annotation.Contended
private volatile long missCount;

@jdk.internal.vm.annotation.Contended
private volatile long evictionCount;
```

**Alternative — manual padding (more portable, no `--add-opens` needed):**
```java
private volatile long hitCount;
private long p1, p2, p3, p4, p5, p6, p7;   // 56 bytes padding = 64-byte cache line
private volatile long missCount;
private long q1, q2, q3, q4, q5, q6, q7;
private volatile long evictionCount;
private long r1, r2, r3, r4, r5, r6, r7;
```

**Expected improvement:** 2–5x throughput improvement on counter increments under 8+ threads
on machines with multiple physical cores. Single-core machines will show no improvement.

#### Watch Out For

**`@Contended` requires `-XX:-RestrictContended` JVM flag AND the `--add-opens` flag.**
Without `-XX:-RestrictContended`, the annotation is ignored (the JVM only respects it for
its own internal classes by default). This flag must be in both Maven Surefire `<argLine>`
and `BenchmarkBase` `@Fork` `jvmArgs`. If you see no improvement from `@Contended` in
benchmarks, check these flags first.

---

## Test Strategy

### Unit Tests (`ReadBufferTest`)

| Test | Invariant proved |
|---|---|
| Record 16 keys, drain — all 16 processed | Buffer correctly stores and returns events |
| Record 17 keys, drain — exactly 16 processed (17th dropped) | Overflow drops events gracefully |
| Record 0 keys, drain — consumer never called | Empty drain is a no-op |
| After drain, buffer state is clean (no stale references) | GC references cleared |
| Two threads each write to their own buffer — no cross-contamination | ThreadLocal isolation |

### jcstress (`CounterAtomicityStressTest`)

Verify that VarHandle `getAndAddAcquire` increments are correctly atomic — no two increments
produce the same value, no increments are lost.

```java
@JCStressTest
@Outcome(id = "2", expect = Expect.ACCEPTABLE, desc = "both increments seen")
@Outcome(id = "0, 1", expect = Expect.FORBIDDEN, desc = "increment lost")
@State
public class CounterAtomicityStressTest {
    TinyLFUCache<String, Integer> cache = /* setup */;
    // 2 actors each call cache.get() on different keys (both miss)
    // Arbiter checks missCount == 2
}
```

### Benchmark Validation

Run `ReadHeavyBenchmark` at each sub-step and record the progression:

| Optimization | Expected throughput (8 threads) |
|---|---|
| Phase 3B baseline (writeLock everywhere) | 5–10M ops/sec |
| After ReadBuffer + readLock on get() | 15–25M ops/sec |
| After VarHandle counters | 20–30M ops/sec |
| After `@Contended` padding | 35–50M ops/sec |
| Caffeine (reference) | 50–80M ops/sec |

---

## Exit Gate

### Correctness
- [ ] All `TinyLFUCacheTest` unit tests still pass after ReadBuffer integration
- [ ] `ReadBufferTest` all pass across `@RepeatedTest(50)`
- [ ] jcstress `CounterAtomicityStressTest`: zero forbidden outcomes
- [ ] Suite re-run 3 times: identical results

### Performance
- [ ] Results recorded: `results/phase-3c-lockfree-<timestamp>.json`
- [ ] **`TinyLFUCache` throughput >= 1.5x Phase 2A `StripedCache`** on `ReadHeavyBenchmark`
- [ ] False sharing benchmark: `@Contended` version >= 1.5x without padding under 8 threads

### Code Quality
- [ ] `/concurrency-review` on `TinyLFUCache.java` and `ReadBuffer.java`: zero findings
- [ ] `/java-best-practices`: zero MUST FIX findings — specifically VarHandle usage correct

---

## Open Questions

- [ ] `WriteBuffer` (mentioned in project structure but not in the plan): should we implement
      a batched write buffer analogous to `ReadBuffer`? Caffeine does this. For Phase 3, the
      benefit is smaller than `ReadBuffer`. Recommendation: skip for Phase 3, add as Phase 3F
      optional extension.
- [ ] The ReadBuffer drain strategy (drain on every `put()`) can cause a write-heavy workload
      to over-drain (every put drains a tiny buffer). Alternative: drain only when buffer
      reaches a threshold. Consider adding a `shouldDrain()` check.
