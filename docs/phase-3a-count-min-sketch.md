# Phase 3A — Count-Min Sketch

## Context & Purpose

To make smarter eviction decisions, we need to know how frequently each key has been accessed.
The naive solution — a `HashMap<K, Integer>` of counts — uses as much memory as the cache
itself and has worse cache-line behavior. `CountMinSketch` solves this with a fixed-size 2D
array of counters that estimates frequency with controllable error bounds.

The key insight: instead of tracking exact counts, we accept a bounded overestimate. The
"Count-Min" name refers to taking the minimum across 4 independent hash functions — errors
only go up (false inflation), never down. This makes it safe to use for eviction decisions:
you may keep a slightly over-counted entry, but you will never evict a truly hot entry.

`CountMinSketch` is a prerequisite for Phase 3B (`TinyLFUPolicy`). Build and fully test it
here before using it for eviction decisions.

---

## Pre-conditions

- Phase 2 exit gates fully passed (all three: 2A, 2B, 2C)
- No specific dependencies — `CountMinSketch` is a standalone data structure

---

## Files to Create

```
cache-core/src/main/java/com/hpcache/internal/sketch/CountMinSketch.java

cache-core/src/test/java/com/hpcache/internal/sketch/CountMinSketchTest.java
```

---

## Implementation Sequence

1. **`CountMinSketch` with `increment()` and `frequency()` — no aging yet**
   → Unit test: increment key 1000 times, `frequency()` returns value close to 1000

2. **Verify false positive rate** — insert N distinct keys once, check none has frequency > 3
   → Unit test: 1000 distinct keys inserted once each, all frequencies <= 3

3. **Add aging (reset/halving)** — halve all counters when total increments exceed threshold
   → Unit test: after reset, all frequencies halve

4. **Verify aging doesn't corrupt boundaries** — no counter bleeds into adjacent nibble
   → Unit test: all counters remain <= 15 after 10,000 increments with aging

5. **Performance check** — `frequency()` of 1M keys in < 100ms (it's O(1) per call)

---

## Class-by-Class Spec

### `CountMinSketch<K>`

**Package:** `com.hpcache.internal.sketch`

**Purpose:** Probabilistic frequency estimator. Uses a 4-row array of 4-bit packed counters.
Reports the minimum count across all 4 rows — hence "Count-Min."

**Internal structure:**
```java
public class CountMinSketch<K> {
    private static final int ROWS = 4;
    private final long[] table;          // flat array: ROWS * width longs
    private final int width;             // columns, power of two
    private final int[] hashSeeds;       // 4 different seeds, one per row
    private long totalIncrements;
    private final long resetThreshold;   // increment total before aging
```

**Width calculation:**
```java
// Nearest power of two >= capacity * 10
// For a 10,000-entry cache: width = nextPowerOfTwo(100,000) = 131,072
// Each long holds 16 4-bit counters: 131,072 longs × 4 rows = 524,288 longs = ~4MB
private static int computeWidth(int capacity) {
    int target = capacity * 10;
    return Integer.highestOneBit(target - 1) << 1;
}
```

**Hash spreading:**
```java
private static int spread(int h) {
    h ^= (h >>> 17);
    h *= 0xed5ad4bb;
    h ^= (h >>> 11);
    h *= 0xac4c1b51;
    h ^= (h >>> 15);
    return h;
}
```

**`void increment(K key)`:**
```java
public void increment(K key) {
    int hash = spread(key.hashCode());
    for (int i = 0; i < ROWS; i++) {
        int col = (hash >>> (i * 8)) & (width - 1);
        incrementCounter(i, col);
    }
    if (++totalIncrements >= resetThreshold) {
        reset();
    }
}
```

**`int frequency(K key)`:**
```java
public int frequency(K key) {
    int hash = spread(key.hashCode());
    int min = Integer.MAX_VALUE;
    for (int i = 0; i < ROWS; i++) {
        int col = (hash >>> (i * 8)) & (width - 1);
        min = Math.min(min, getCounter(i, col));
    }
    return min;
}
```

**4-bit counter packing — 16 counters per `long`:**
```java
private int getCounter(int row, int col) {
    int index = (row * width + col) >>> 4;          // which long in table[]
    int offset = ((row * width + col) & 0xF) << 2; // bit position within that long
    return (int) ((table[index] >>> offset) & 0xFL);
}

private void incrementCounter(int row, int col) {
    int index = (row * width + col) >>> 4;
    int offset = ((row * width + col) & 0xF) << 2;
    long current = (table[index] >>> offset) & 0xFL;
    if (current < 15) {                // MUST check — see Watch Out For
        table[index] += (1L << offset);
    }
}
```

**Aging (reset) — halve all counters:**
```java
private void reset() {
    for (int i = 0; i < table.length; i++) {
        // Right-shift each 4-bit nibble by 1.
        // Mask 0x7777... clears the high bit of each nibble to prevent
        // the right-shifted bit from appearing in the adjacent lower nibble.
        table[i] = (table[i] >>> 1) & 0x7777777777777777L;
    }
    totalIncrements = 0;
}
```

#### Watch Out For

**The nibble overflow bug — the most important correctness issue in this class.**

Counters are 4-bit values (0–15) packed into 64-bit `long`s. If a counter at value 15 is
incremented without the `if (current < 15)` saturation check, it wraps to 0 and carries
a bit into the next nibble, corrupting an adjacent counter. The corruption is silent and
manifests as incorrect frequency readings for unrelated keys. Always saturate at 15.

**The aging mask `0x7777777777777777L`.**

Binary: `0111 0111 0111 0111 ...` — exactly the pattern that clears the high bit of each
4-bit nibble. After right-shifting all bits by 1, without this mask, the high bit of each
nibble would land in the low bit of the next-higher nibble. The mask prevents this bleed.
If you forget the mask, aging corrupts counters across nibble boundaries.

**`hash >>> (i * 8)` gives at most 32 bits of hash.**

With 4 rows and 8-bit column indices per row, we use bits [0-7], [8-15], [16-23], [24-31]
of the spread hash. If `width > 256`, the column index `col = (hash >>> (i*8)) & (width-1)`
still works because the `& (width-1)` mask takes the low bits of each 8-bit group. This
means different rows may map to similar column neighborhoods for keys with similar hash codes.
This is an acceptable trade-off in the standard implementation. For higher accuracy, use
4 independent hash functions (4 different seeds XORed with the key hash).

**`resetThreshold` sizing.**

Set `resetThreshold = capacity * 10` (same as width). This means aging occurs once per
"full pass" of the sketch. Too low a threshold causes over-aggressive aging (frequencies
approach 0 before they can influence eviction). Too high means old access patterns persist
too long. Caffeine uses the same heuristic.

**Thread safety:** `CountMinSketch` is **not thread-safe**. It will be used inside
`TinyLFUCache` which manages its own synchronization. Do not add synchronization here —
it would be double-locking when used from within the cache's write lock.

---

## Test Strategy

### Unit Tests (`CountMinSketchTest`)

| Test | Invariant proved |
|---|---|
| Increment "key" 1000 times — `frequency("key")` is between 950 and 1100 | Frequency tracking is accurate within 10% |
| Insert 1000 distinct keys once each — no key has `frequency()` > 5 | False positive rate is low |
| After aging (reset), all frequencies halve | Aging correct |
| After 10,000 increments with aging, no counter is > 15 | No nibble overflow |
| After reset, `totalIncrements` is 0 | Reset resets counter |
| Two different keys with the same hash (collision) — neither frequency is 0 | Hash collision doesn't zero out counts |
| Width is always a power of two | Width calculation correct |
| `frequency()` for a key never-inserted returns 0 | Clean initial state |

**Accuracy validation (parameterized test):**
```java
// Insert key K exactly N times. Assert frequency(K) is in [N * 0.9, N * 1.5]
// The overestimate bound is due to hash collisions in the sketch.
// Run for N = 10, 100, 1000, 10000
```

**False positive test:**
```java
// Insert 1000 distinct keys, each exactly once.
// Assert: for every key K, frequency(K) >= 1 (no undercounting)
// Assert: < 5% of keys have frequency(K) > 3 (low overcounting)
```

---

## Exit Gate

### Correctness
- [ ] All unit tests pass, zero failures across `@RepeatedTest(10)`
- [ ] Accuracy test: `frequency()` within 50% of true count for N = 10, 100, 1000, 10,000
- [ ] No nibble overflow: after 10,000 increments of one key, all table entries are <= 15
- [ ] Aging test: all frequencies halve after reset, no boundary corruption

### Performance
- [ ] 1M `increment()` + `frequency()` calls complete in < 500ms (single-threaded)
  (CountMinSketch must be fast — it runs on every cache read/write in Phase 3)

### Code Quality
- [ ] `/java-best-practices` on `CountMinSketch.java`: zero MUST FIX findings
- [ ] No synchronization added — the class is intentionally not thread-safe

---

## Open Questions

- [ ] Should `CountMinSketch` use 4 independent hash seeds (more accurate) or the current
      bit-shift approach (simpler)? Recommendation: use the bit-shift approach for Phase 3A
      and note that accuracy could be improved with independent seeds.
- [ ] Should the sketch be reset-able to zero (for testing)? Add a `clear()` method that
      fills `table` with zeros and resets `totalIncrements`. Useful in tests.
