# Phase 3B — TinyLFU Cache

## Context & Purpose

LRU evicts the least recently *used* entry. But recency is a poor proxy for value — a key
accessed once 5 seconds ago may still be hot (e.g., a one-time batch scan) while a key
accessed 1000 times yesterday is clearly valuable.

TinyLFU uses the `CountMinSketch` frequency estimates to make smarter eviction decisions.
The **Window TinyLFU** architecture (used by Caffeine) adds a small "window" segment that
admits new entries without frequency checks — preventing the sketch from being polluted by
one-time scan patterns.

Expected improvement: 10–30% better hit rate under Zipfian access patterns vs LRU.
This is a *hit rate* improvement, not a throughput improvement. The cache becomes smarter
about what to keep, not faster at keeping it.

---

## Pre-conditions

- Phase 3A exit gate fully passed (`CountMinSketch` accurate and tested)
- Phase 1 `LRUCache` fully working (used as the LRU building block within TinyLFU)
- Phase 2A/2B benchmarks recorded (for baseline comparison)

---

## Files to Create

```
cache-core/src/main/java/com/hpcache/internal/eviction/EvictionPolicy.java
cache-core/src/main/java/com/hpcache/internal/eviction/TinyLFUPolicy.java
cache-core/src/main/java/com/hpcache/impl/TinyLFUCache.java

cache-core/src/test/java/com/hpcache/internal/eviction/TinyLFUPolicyTest.java
cache-core/src/test/java/com/hpcache/impl/TinyLFUCacheTest.java
```

**Update:**
```
cache-core/src/main/java/com/hpcache/builder/CacheBuilder.java  ← add .tinyLFU(boolean) option
```
When `tinyLFU(true)` is set, `build()` returns a `TinyLFUCache`. This is the recommended
default for most workloads after Phase 3 — document this in the builder Javadoc.

---

## Implementation Sequence

1. **`EvictionPolicy` interface** — defines the eviction contract
   → Compile check only

2. **`TinyLFUPolicy` with three segments** — constructor creates Window, Protected, Probationary
   `LinkedHashMap`s with correct size allocations
   → Unit test: `totalSize()` equals sum of three segment sizes

3. **`admit(key, value)`** — new entries enter Window; Window overflow moves candidate to
   compete against Probationary tail; loser is evicted
   → Unit test: fill to capacity, verify size == maxSize, verify the correct entry was evicted

4. **`access(key)`** — moves entry within/between segments on cache hit
   → Unit test: repeated access to a key in Probationary promotes it to Protected

5. **`selectVictim(candidate)`** — sketch-based competition between candidate and
   Probationary tail
   → Unit test: a frequently-accessed key in Probationary survives over a new candidate

6. **`TinyLFUCache` — wrap `TinyLFUPolicy` behind the `Cache<K,V>` interface**
   → All `LRUCacheTest` unit tests must pass (functional equivalence)

7. **Hit rate benchmark** — run `ZipfianBenchmark` and compare hit rate vs LRU

---

## Class-by-Class Spec

### `EvictionPolicy` (interface)

**Package:** `com.hpcache.internal.eviction`

```java
interface EvictionPolicy<K, V> {
    void admit(K key, V value);         // called on every put()
    void access(K key);                 // called on every get() hit
    K evict();                          // returns the key to remove; null if cache not full
    boolean contains(K key);
    int size();
}
```

---

### `TinyLFUPolicy`

**Package:** `com.hpcache.internal.eviction`

**Architecture — three segments:**
```
┌────────────────────────────────────────────────────────────────┐
│  Window LRU (1%)  │  Protected LRU (80%)  │  Probationary (19%) │
└────────────────────────────────────────────────────────────────┘
   new entries →        hot entries               testing ground
```

**Size allocations (given total capacity N):**
```java
int windowSize       = Math.max(1, (int) (capacity * 0.01));   // 1% (minimum 1)
int protectedSize    = (int) (capacity * 0.80);                // 80%
int probationarySize = capacity - windowSize - protectedSize;  // ~19%
```

**How it works:**

1. **New entry:** Enters Window (LRU). Window is small, admits everything.

2. **Window overflow:** The LRU tail of Window is evicted as a *candidate*.
   The candidate competes against the LRU tail of Probationary using `sketch.frequency()`.
   - If `frequency(candidate) > frequency(probationaryTail)`: candidate wins, probationaryTail
     is evicted from cache
   - Otherwise: candidate is rejected (evicted from cache), probationaryTail stays

3. **Cache hit on Probationary:** Entry moves to head of Protected ("promoted").

4. **Protected overflow:** LRU tail of Protected demotes to head of Probationary.

**`selectVictim` implementation:**
```java
K selectVictim(K candidate) {
    K probTail = getEldest(probationary);
    if (probTail == null) return candidate;  // probationary empty, evict candidate
    return sketch.frequency(candidate) > sketch.frequency(probTail)
        ? probTail     // candidate wins — evict probationary tail
        : candidate;   // probationary tail wins — evict candidate
}
```

**`access(K key)` — promote entries on hit:**
```java
void access(K key) {
    sketch.increment(key);   // always update frequency on access
    if (probationary.containsKey(key)) {
        // Promote from Probationary to Protected
        probationary.remove(key);
        V value = /* get value from storage */;
        addToProtected(key, value);
        // Handle Protected overflow
        if (protected_.size() > protectedSize) {
            Map.Entry<K,V> demoted = getAndRemoveEldest(protected_);
            addToProbationary(demoted.getKey(), demoted.getValue());
        }
    } else if (protected_.containsKey(key)) {
        // Already in Protected — move to head (most recently used)
        // LinkedHashMap with accessOrder=true handles this automatically
    }
    // Window entries: update happens via LinkedHashMap accessOrder
}
```

#### Watch Out For

**Why 1% Window matters.** Without the Window segment, every new entry must compete
immediately with Probationary entries. A scan workload (100 new unique keys in quick
succession) would fill the sketch with junk frequency data and evict hot entries. The Window
acts as a "probation period" — entries must survive Window eviction before entering the main
cache. This is what makes TinyLFU resistant to scan pollution.

**The frequency sketch only records `increment` on access, not on initial insert.** When a
new entry enters Window, the sketch records one increment. If it gets evicted from Window
without being accessed again, its frequency is 1 — it will lose against any Probationary
entry accessed more than once. This is correct behavior.

**Three separate `LinkedHashMap` instances.** The policy manages three maps, not one. A key
can exist in at most one segment at any time. When moving a key between segments, always
remove from the old segment before inserting into the new one to prevent a key being in two
segments simultaneously.

**`TinyLFUPolicy` is NOT thread-safe.** It will be called from within `TinyLFUCache`'s
write lock. Do not add synchronization here.

---

### `TinyLFUCache`

**Package:** `com.hpcache.impl`

**Purpose:** Wraps `TinyLFUPolicy` + `CountMinSketch` behind the `Cache<K,V>` interface.
Uses `ReentrantReadWriteLock` for Phase 3B (lock-free reads come in Phase 3C).

**Key difference from `LRUCache`:** Every `get()` hit calls `policy.access(key)` to
update the entry's position and increment its sketch frequency. Every `put()` calls
`policy.admit(key, value)` to route the new entry through the Window.

**Internal structure:**
```java
public class TinyLFUCache<K, V> implements Cache<K, V> {
    private final Map<K, CacheEntry<V>> store;      // primary key → value storage
    private final TinyLFUPolicy<K, V> policy;       // manages segment membership
    private final ReentrantReadWriteLock lock;
    private final int maxSize;
    private final long defaultTtlMs;
    // stat counters (AtomicLong for now, VarHandle in Phase 3D)
```

#### Watch Out For

**Decoupling storage from policy.** `TinyLFUPolicy` manages which keys are in which segment
(Window/Protected/Probationary) but does NOT store values — it only tracks keys for ordering
and eviction decisions. `TinyLFUCache` has a separate `Map<K, CacheEntry<V>> store` for the
actual values. When `policy.evict()` returns a key to remove, `TinyLFUCache` removes it from
`store`.

**`get()` still requires writeLock in Phase 3B.** `policy.access(key)` mutates segment
membership (same problem as `LinkedHashMap` accessOrder). Phase 3C introduces the ReadBuffer
to defer these mutations and allow lock-free reads. For now, use `writeLock` everywhere.

---

## Test Strategy

### Unit Tests

**`TinyLFUPolicyTest`:**

| Test | Invariant proved |
|---|---|
| Fill to capacity — size == maxSize | Capacity respected |
| New entry in Window survives if access > Probationary tail | Window candidate wins when frequent |
| New entry in Window evicted if access < Probationary tail | Probationary entry retained when more frequent |
| Probationary hit promotes to Protected | Promotion works |
| Protected overflow demotes to Probationary | Demotion works |
| 1000 scan entries (each accessed once) don't evict 10 hot entries (accessed 100x each) | Scan resistance works |

**`TinyLFUCacheTest`:** All `LRUCacheTest` tests pass (functional equivalence).

### Benchmark Validation — Hit Rate Focus

Run `ZipfianBenchmark` against `LRUCache` and `TinyLFUCache`:

| Implementation | Expected Zipfian hit rate |
|---|---|
| LRUCache (Phase 1) | 65–75% |
| TinyLFUCache (Phase 3B) | 80–90% |

**This is the primary exit gate for Phase 3B.** A hit rate improvement of >= 10 percentage
points under Zipfian distribution confirms the policy is working correctly.

**Scan resistance test:**
```
// Insert 10 "hot" keys, access each 1000 times
// Insert 1000 "scan" keys, access each once
// Cache maxSize = 100
// Verify: after the scan, all 10 hot keys are still in cache
```

---

## Exit Gate

### Correctness
- [ ] `TinyLFUPolicyTest` all pass: zero failures across `@RepeatedTest(10)`
- [ ] All `LRUCacheTest` tests pass against `TinyLFUCache`
- [ ] Scan resistance test passes: 10 hot keys survive 1000-key scan
- [ ] Suite re-run 3 times: identical results

### Performance
- [ ] Results recorded: `results/phase-3b-tinylfu-<timestamp>.json`
- [ ] **Hit rate under `ZipfianBenchmark` >= 10 percentage points better than `LRUCache`**
- [ ] Throughput: no worse than Phase 1 `LRUCache` (both use writeLock; Phase 3C will improve)

### Code Quality
- [ ] `/concurrency-review` on `TinyLFUCache.java` and `TinyLFUPolicy.java`: zero findings
- [ ] `/java-best-practices`: zero MUST FIX findings

---

## Open Questions

- [ ] Should Window size be configurable via `CacheBuilder`? Default 1% may be too small for
      caches with very small maxSize (e.g., maxSize=10, Window=0). Enforce a minimum of 1.
- [ ] `TinyLFUPolicy` needs to store both the key AND the value across segments for the
      demote/promote flow. But the primary `store` map also has the value. Consider having
      the policy only store keys (not values), and have `TinyLFUCache` look up values in
      `store` when needed. This avoids duplicate value storage.
