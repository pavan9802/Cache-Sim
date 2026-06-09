# Phase 5 — Full Benchmarking & Performance Analysis

## Context & Purpose

Assemble the complete performance story: throughput progression from Phase 1 to Phase 3,
latency distributions, GC behavior, and a side-by-side comparison against Caffeine and Guava.
The output of this phase is the data that fills the README and the resume bullet.

This is also where the `scripts/` directory is built out — automating the benchmark runs,
result comparison, and flame graph generation so the full suite can be re-run reproducibly.

---

## Pre-conditions

- All phases 0–4 exit gates fully passed
- All cache implementations in `cache-core/impl/` have passing test suites
- `results/` directory has baseline JSON files from each completed phase

---

## Files to Create

```
cache-benchmark/src/main/java/com/hpcache/bench/ComparisonBenchmark.java
cache-benchmark/src/main/java/com/hpcache/bench/EvictionBenchmark.java
cache-benchmark/src/main/java/com/hpcache/bench/TTLBenchmark.java

scripts/run_benchmarks.sh
scripts/compare_phases.sh
scripts/generate_flamegraph.sh
scripts/parse_gc_log.py
```

---

## Implementation Sequence

1. **`ComparisonBenchmark`** — single class running all implementations via `@Param`
   → Run, verify all implementations produce valid numbers, no NaN

2. **`EvictionBenchmark`** — cache at capacity, constant eviction pressure
   → Highlights per-eviction cost differences between LRU and TinyLFU

3. **`TTLBenchmark`** — high TTL expiry rate (short TTL + high write rate)
   → Highlights background cleaner overhead

4. **`scripts/run_benchmarks.sh`** — runs full JMH suite, saves all results
   → Test: run the script, verify JSON files are created

5. **`scripts/compare_phases.sh`** — diffs two result JSON files, shows change table
   → Test: run against two existing result files, output is readable

6. **GC analysis runs** — run `ReadHeavyBenchmark` with G1GC and ZGC, save GC logs

7. **`scripts/parse_gc_log.py`** — extracts pause stats from GC logs

8. **`scripts/generate_flamegraph.sh`** — async-profiler wrapper (requires async-profiler installed)
   → Generate flame graphs for Phase 1 and Phase 3

9. **Assemble comparison table** — fill in the table from `HighPerformanceCache_Plan.md`
   with real numbers

---

## Class-by-Class Spec

### `ComparisonBenchmark`

**Purpose:** Single benchmark class comparing all implementations side-by-side. Uses JMH
`@Param` to iterate over implementations automatically.

```java
@Param({"ConcurrentHashMapCache", "LRUCache", "StripedCache", "StampedCache",
        "TinyLFUCache", "Caffeine", "Guava"})
public String cacheImpl;

@Setup
public void setup() {
    cache = CacheFactory.create(cacheImpl, maxSize, ttlMs);
}
```

**`CacheFactory`** — a simple switch-case factory that creates the appropriate cache
implementation given a string name. Lives in `cache-benchmark`.

#### Watch Out For

**Caffeine and Guava have different APIs than `Cache<K,V>`.** `CacheFactory` must wrap them
in thin adapters that implement `Cache<K,V>` so they can be used interchangeably in benchmarks.
Keep adapters minimal — just delegate, no logic.

**`@Param` values must match exactly.** JMH constructs the parameter value via reflection —
a typo silently skips the variant rather than failing fast.

---

### `scripts/run_benchmarks.sh`

```bash
#!/bin/bash
set -euo pipefail
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RESULTS_DIR="results/$TIMESTAMP"
mkdir -p "$RESULTS_DIR"

mvn package -pl cache-benchmark -am -q

java -jar cache-benchmark/target/benchmarks.jar \
    -rf json \
    -rff "$RESULTS_DIR/comparison.json" \
    -wi 5 -i 10 -f 2 \
    ".*ComparisonBenchmark.*" \
    2>&1 | tee "$RESULTS_DIR/run.log"

echo "Results saved to $RESULTS_DIR"
```

#### Watch Out For

**`set -euo pipefail` exits on any error** — if `mvn package` fails, the script stops.
This is intentional: don't run benchmarks against a broken build.

**JMH `@Fork(2)` forks a new JVM** — the fat JAR (`benchmarks.jar`) must be built before
running. The script handles this with `mvn package`.

---

### GC Analysis

**Run with GC logging:**
```bash
java -Xms2g -Xmx2g \
     -Xlog:gc*:file=results/gc-g1.log:time,uptime,level,tags \
     -XX:+UseG1GC \
     -jar cache-benchmark/target/benchmarks.jar ReadHeavyBenchmark -p cacheImpl=TinyLFUCache
```

**`scripts/parse_gc_log.py`** extracts:
- Total GC pause time
- Max single pause duration
- GC frequency (pauses/second)
- GC overhead (% of wall time in GC)

**Expected findings:**
- G1GC: 50–200ms pauses, low frequency
- ZGC: < 1ms pauses, higher frequency, ~5% throughput overhead

---

### Flame Graphs

**`scripts/generate_flamegraph.sh`:**
```bash
#!/bin/bash
IMPL=${1:-TinyLFUCache}  # default to TinyLFUCache
ASYNC_PROFILER=${ASYNC_PROFILER_HOME:-~/async-profiler}

java -agentpath:"$ASYNC_PROFILER/lib/libasyncProfiler.so=start,event=cpu,\
    file=profiles/$IMPL.jfr" \
    -jar cache-benchmark/target/benchmarks.jar ReadHeavyBenchmark \
    -p cacheImpl=$IMPL -wi 3 -i 5 -f 1

"$ASYNC_PROFILER/bin/jfr2flame" profiles/$IMPL.jfr > flamegraphs/$IMPL.html
```

**What to look for in Phase 1 flame graph:**
- `AbstractQueuedSynchronizer` (AQS) — the lock implementation — should be prominent
- `ReentrantReadWriteLock.lock()` / `unlock()` visible in the call tree

**What to look for in Phase 3 flame graph:**
- AQS/lock methods should be a small fraction
- `CountMinSketch.frequency()` may appear — expected
- `LinkedHashMap` internal methods visible — next bottleneck

---

## Comparison Table Template

Fill in with real JMH numbers. This goes in `README.md`.

| Metric | ConcurrentHashMap | LRUCache | StripedCache | StampedCache | TinyLFUCache | Caffeine | Guava |
|---|---|---|---|---|---|---|---|
| Throughput (8T, read-heavy) | ~100M | ~3–5M | ~15–25M | ~20–30M | ~35–50M | ~50–80M | ~5M |
| p50 latency | ~10ns | ~500ns | ~200ns | ~150ns | ~100ns | ~50ns | ~500ns |
| p99 latency | ~50ns | ~5µs | ~1µs | ~800ns | ~500ns | ~200ns | ~2µs |
| Hit rate (Zipfian) | N/A | ~65–75% | ~65–75% | ~65–75% | ~80–90% | ~85–92% | ~65–75% |

---

## Exit Gate

### Correctness
- [ ] All benchmark variants run without JMH errors or NaN values
- [ ] `compare_phases.sh` runs and produces valid output for any two result files
- [ ] GC logs parseable by `parse_gc_log.py`

### Performance — Comparison Table Complete
- [ ] All cells in comparison table filled with real measured numbers
- [ ] `TinyLFUCache` throughput within 2x of Caffeine on `ReadHeavyBenchmark`
- [ ] `TinyLFUCache` hit rate within 5 percentage points of Caffeine on Zipfian workload
- [ ] G1GC vs ZGC pause comparison documented

### Documentation
- [ ] Flame graphs generated for Phase 1 and Phase 3, bottlenecks annotated
- [ ] Throughput scaling chart (1→2→4→8→16 threads) generated for all implementations

---

## Open Questions

- [ ] async-profiler requires a separate download — document the setup steps in README
      or create a `scripts/setup.sh` that downloads it.
- [ ] Should OffHeap GC benchmark (Phase 3F optional) be included in Phase 5 if it was
      implemented? If `OffHeapStore` was built, yes — add a GC comparison to the table.
