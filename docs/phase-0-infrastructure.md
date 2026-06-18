# Phase 0 — Simulation & Benchmarking Infrastructure

## Context & Purpose

Build the measurement and simulation infrastructure **before** writing a single line of cache
logic. This ensures every improvement is measurable and every regression is caught immediately.
By the end of Phase 0, JMH benchmarks are running against a stub cache, and baseline numbers
are recorded. All subsequent phases will be judged against these numbers.

This phase also includes a **Pre-step** that must be completed before Phase 0 can begin:
defining the `Cache<K,V>` interface and a `ConcurrentHashMapCache` stub. The stub is needed
so benchmarks have something to compile against.

---

## Pre-conditions

None — this is the first phase. Java 21+ and Maven must be installed.
Run `mvn -version` and `java -version` to confirm before starting.

---

## Files to Create

### Pre-step (cache-core — must exist before benchmarks can compile)
```
cache-core/src/main/java/com/hpcache/api/Cache.java
cache-core/src/main/java/com/hpcache/impl/ConcurrentHashMapCache.java
```

### Phase 0 (cache-simulator)
```
cache-simulator/src/main/java/com/hpcache/sim/MarketEvent.java
cache-simulator/src/main/java/com/hpcache/sim/MarketDataSimulator.java
cache-simulator/src/main/java/com/hpcache/sim/ZipfianSymbolSelector.java
cache-simulator/src/main/java/com/hpcache/sim/BurstScheduler.java
```

### Phase 0 (cache-benchmark)
```
cache-benchmark/src/main/java/com/hpcache/bench/BenchmarkBase.java
cache-benchmark/src/main/java/com/hpcache/bench/ReadHeavyBenchmark.java
cache-benchmark/src/main/java/com/hpcache/bench/WriteHeavyBenchmark.java
cache-benchmark/src/main/java/com/hpcache/bench/ZipfianBenchmark.java
cache-benchmark/src/main/java/com/hpcache/bench/ThreadScalingBenchmark.java
```

### Phase 0 (cache-core)
```
cache-core/src/main/java/com/hpcache/api/StatsReporter.java
```

---

## Implementation Sequence

Implement in this order so every step is immediately testable:

1. **Pre-step A**: Define `Cache<K,V>` interface (just the method signatures, no impl)
   → `mvn compile -pl cache-core` must pass

2. **Pre-step B**: `ConcurrentHashMapCache` stub wrapping `ConcurrentHashMap`
   → Write a simple `main()` in a scratch class: `put("a", 1)`, assert `get("a") == 1`

3. **`MarketEvent` record**
   → Verify `spread()` and `midPrice()` return correct values in a unit test

4. **`MarketDataSimulator`**
   → Verify over 1000 generated ticks: all prices > 0, bid < ask, sequence numbers increment

5. **`ZipfianSymbolSelector`**
   → Unit test: over 100,000 selections, top-10 symbols receive > 60% of all selections

6. **`BenchmarkBase` + `ReadHeavyBenchmark`** (against `ConcurrentHashMapCache` stub)
   → `mvn package -pl cache-benchmark && java -jar benchmarks.jar ReadHeavyBenchmark`
   → JMH must run without error; numbers will be ~80–100M ops/sec

7. **Record Phase 0 baseline** → save to `results/phase-0-baseline-<timestamp>.json`

8. **Remaining benchmarks**: WriteHeavy, Zipfian, ThreadScaling

9. **`BurstScheduler`** → verify tick rate changes correctly across simulated day periods

10. **`StatsReporter`** → verify output prints every 5 seconds during a 15-second test run

11. **Phase exit gate**: run `/phase-exit-check 0`

---

## Class-by-Class Spec

### Pre-step: `Cache<K,V>` (interface)

**Purpose:** The contract all cache implementations must satisfy. Defined now so benchmarks
can compile against an interface rather than a concrete class.

**Methods:**
```java
V get(K key);
void put(K key, V value);
void put(K key, V value, long ttlMs);
void invalidate(K key);
void invalidateAll();
boolean containsKey(K key);
int size();
CacheStats stats();
void close();
```

**Design constraints:**
- `get()` returns `null` on miss — never `Optional<V>` (allocation overhead on hot path)
- `close()` must be idempotent — safe to call multiple times without error
- `K` must implement `hashCode()` and `equals()` (document this in Javadoc)

#### Watch Out For
- Do not add `default` methods here yet — keep the interface minimal. Additions in Phase 1.
- `CacheStats` doesn't exist yet in the pre-step. Use a placeholder `Object stats()` and
  replace it in Phase 1A when `CacheStats` is defined.

---

### Pre-step: `ConcurrentHashMapCache`

**Purpose:** Stub implementation for Phase 0 benchmarks. Not a real cache — no eviction,
no TTL, no stats. Exists only to give benchmarks something to compile and run against.

**Implementation:**
```java
@ThreadSafe
public class ConcurrentHashMapCache<K, V> implements Cache<K, V> {
    private final ConcurrentHashMap<K, V> map = new ConcurrentHashMap<>();

    @Override public V get(K key) { return map.get(key); }
    @Override public void put(K key, V value) { map.put(key, value); }
    @Override public void put(K key, V value, long ttlMs) { map.put(key, value); }
    @Override public void invalidate(K key) { map.remove(key); }
    @Override public void invalidateAll() { map.clear(); }
    @Override public boolean containsKey(K key) { return map.containsKey(key); }
    @Override public int size() { return map.size(); }
    @Override public Object stats() { return null; }  // placeholder until Phase 1
    @Override public void close() {}  // nothing to close
}
```

**Expected benchmark numbers:** 80–100M ops/sec read-heavy, 8 threads.
This is the theoretical ceiling — every real cache implementation will be slower due to
eviction bookkeeping.

#### Watch Out For
- This class is **not** in `cache-core/internal`. It lives in `cache-core/impl` and is
  intentionally public — it is a legitimate (if trivial) implementation of the interface.
- Delete this class in Phase 6 polish — it has no place in the final library.

---

### `MarketEvent` (record)

**Purpose:** Immutable value type representing one price tick from the market data feed.

**Fields:**
```java
String symbol          // ticker: "AAPL", "MSFT", etc.
double bidPrice        // best bid
double askPrice        // best ask (always > bidPrice)
long volume            // shares/contracts in tick
long timestampNanos    // System.nanoTime() at generation
int sequenceNumber     // monotonically increasing per symbol
```

**Default methods:**
```java
double spread()    // askPrice - bidPrice
double midPrice()  // (bidPrice + askPrice) / 2.0
```

#### Watch Out For
- `bidPrice` must always be < `askPrice`. Add a compact constructor that validates this
  and throws `IllegalArgumentException` if violated. Realistic simulations never produce
  inverted spreads; if you see one it's a simulator bug, not a valid edge case.
- `timestampNanos` uses `System.nanoTime()` — it is not wall-clock time and cannot be
  compared across JVM instances.

---

### `MarketDataSimulator`

**Purpose:** Generates a continuous stream of realistic price ticks using Geometric Brownian
Motion (GBM). Prices follow a random walk — they look like real market data.

**GBM formula:**
```
nextPrice = currentPrice * exp((drift - 0.5 * vol²) * dt + vol * sqrt(dt) * Z)
where Z ~ N(0,1)
```

**Key fields:**
```java
Map<String, Double> currentPrices    // mutable — updated each tick
Map<String, Double> volatilities     // per-symbol, default ~0.02 (2%)
double drift                         // use 0.0 for zero-drift
double dt                            // 1.0/252 (one trading day)
int sequenceCounter                  // per-instance tick sequence (not shared)
```

**Symbol universe (100 total):**
- First 3: AAPL, MSFT, GOOGL
- Remaining 97: generated as SYM004–SYM100
- Base price: 100.0 for all symbols
- Spread: 0.01 (1 cent) added symmetrically around mid

**Methods:**
```java
MarketDataSimulator(List<String> symbols, double basePrice, double volatility)
MarketEvent nextTick(String symbol)     // one tick for given symbol
MarketEvent nextTick()                  // tick for randomly selected symbol
void setVolatility(String symbol, double vol)
Map<String, Double> getCurrentPrices()  // snapshot
```

#### Watch Out For
- Use `ThreadLocalRandom` (not `new Random()`) — `Random` has internal synchronization.
- GBM can theoretically produce negative prices given extreme parameters. Add a floor:
  `Math.max(nextPrice, 0.01)`. This won't happen in practice with realistic parameters
  but prevents divide-by-zero in spread calculations.
- `sequenceCounter` is a plain `int` — this class is `@NotThreadSafe` and must never be
  shared across threads. Declare it in a `@State(Scope.Thread)` in benchmarks so each
  thread gets its own instance.

---

### `ZipfianSymbolSelector`

**Purpose:** Selects symbols with a Zipfian distribution — top symbols get exponentially
more traffic, mimicking real market activity where AAPL trades far more than SYM097.

**Zipf probability formula:**
```
P(rank k) = (1/k^skew) / sum(1/i^skew for i=1..N)
```

**Fields:**
```java
List<String> symbols                   // ordered: index 0 = hottest symbol
double[] cumulativeProbabilities       // precomputed CDF — built once in constructor
double skew                            // Zipf exponent, default 1.0
```

**Methods:**
```java
ZipfianSymbolSelector(List<String> symbols, double skew)
String select()                        // binary search on CDF — O(log N)
Map<String, Double> getProbabilities() // expected access % per symbol
```

**Validation target:** With `skew=1.0` and 100 symbols, top-10 symbols should receive
65–80% of all selections over 1M draws.

#### Watch Out For
- The CDF must be precomputed in the constructor, not on each `select()` call. Computing
  it per-call would make selection O(N) instead of O(log N) and destroy benchmark throughput.
- Binary search on the CDF using `Arrays.binarySearch()`. If the random value exactly
  equals a CDF boundary, `binarySearch` may return a negative insertion point — handle
  this: `int idx = result < 0 ? -result - 1 : result`.
- Use `ThreadLocalRandom.current().nextDouble()` for the random draw, not `Math.random()`.

---

### `BurstScheduler`

**Purpose:** Simulates time-of-day tick rate variation — market open/close have 100x more
activity than midday. Used by the demo to vary load realistically.

**Tick rate profile:**
| Simulated period | Rate (ticks/sec) |
|---|---|
| Market open (first 5 min) | 100,000 |
| Morning (next 25 min) | 10,000 |
| Midday | 1,000 |
| Afternoon (last 25 min) | 10,000 |
| Market close (last 5 min) | 100,000 |

**Methods:**
```java
BurstScheduler(long simulatedDayDurationMs)  // compress full day into given duration
int getCurrentTickRate()                     // ticks/sec at current simulated time
void reset()                                 // restart simulation day
```

#### Watch Out For
- Use `System.nanoTime()` to track elapsed simulated time, not `System.currentTimeMillis()`.
- `getCurrentTickRate()` is called on every tick from benchmark threads — must be O(1) with
  no allocation. Precompute the time boundaries in the constructor.

---

### `BenchmarkBase`

**Purpose:** Shared JMH `@State` class. Sets up cache, simulator, key universe, and histogram
so all benchmark subclasses start from a consistent, pre-warmed state.

**JMH annotations (on every benchmark class):**
```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgs = {
    "-Xms2g", "-Xmx2g", "-XX:+UseG1GC",
    "--add-opens", "java.base/jdk.internal.vm.annotation=ALL-UNNAMED",
    "-XX:-RestrictContended"
})
@Threads(8)
```

**`@State(Scope.Benchmark)` fields** (shared across all threads):
```java
Cache<String, MarketEvent> cache
ZipfianSymbolSelector selector
Histogram latencyHistogram           // HdrHistogram: max 10s, 3 sig figs
AtomicLong operationCount
String[] keyUniverse                 // pre-generated 10,000 price bar keys: "SYMBOL:1m:BUCKET"
                                     // 100 symbols × 100 time buckets, ordered symbol-first
```

**`@State(Scope.Thread)` fields** (one instance per thread — in a separate state class):
```java
MarketDataSimulator simulator        // @NotThreadSafe — must not be shared
```

**`@Setup(Level.Trial)`:**
1. Pre-generate `keyUniverse` — 10,000 price bar identifiers in "SYMBOL:1m:BUCKET" format
   (100 symbols × 100 1-minute time buckets, ordered symbol-first so keys 0–99 = all AAPL bars)
2. Initialize `selector` as `ZipfianSymbolSelector(Arrays.asList(keyUniverse), 1.0)` — must come after `keyUniverse` is built
3. Build cache with `maxSize=10_000`, `ttl=30s`
4. Pre-populate to 80% capacity (8,000 entries)
5. Initialize HdrHistogram

**`@TearDown(Level.Trial)`:**
1. Print HdrHistogram p50/p75/p95/p99/p999
2. Print cache hit rate
3. Save results JSON to `results/`

#### Watch Out For
- `keyUniverse` must be pre-generated, not computed during the benchmark method. Any
  allocation inside `@Benchmark` methods biases results.
- `Histogram` from HdrHistogram is **not thread-safe**. Use `ConcurrentHistogram` or
  record per-thread and merge in teardown.

---

### Benchmark Classes

**`ReadHeavyBenchmark`:** 95% `get`, 5% `put`. Uniform key distribution.
Uses `Blackhole.consume()` on get result to prevent dead-code elimination.

**`WriteHeavyBenchmark`:** 50% `get`, 50% `put`. Stresses lock contention.

**`ZipfianBenchmark`:** 95% `get`, 5% `put`. Zipfian key distribution via `selector.select()`.
`selector` is initialized over all 10,000 price bar keys, making the most-accessed entries the
earliest time buckets of the most popular symbols. This concentrates reads on a hot subset of bars
that fits in LRU but not with optimal frequency-based admission, producing the expected 65–75%
(LRU) vs 80–90% (TinyLFU) hit rate divergence.

**`ThreadScalingBenchmark`:** Read-heavy workload at `@Threads(1)`, `@Threads(2)`,
`@Threads(4)`, `@Threads(8)`, `@Threads(16)`. Implemented as 5 separate `@Benchmark`
methods in one class (or 5 subclasses).

#### Watch Out For
- Always use `Blackhole.consume(result)` on `get()` return values. Without it, the JIT
  eliminates the call entirely since the result is unused — numbers become meaningless.
- Never put `ThreadLocalRandom.current()` in a field — it must be called inline each time
  or it defeats its purpose.

---

### `StatsReporter`

**Purpose:** Periodic console reporter — prints cache stats every 5 seconds during long runs.

**Output format:**
```
[14:32:05] Cache Stats
  Hit Rate:      87.3%
  Throughput:    4,231,847 ops/sec
  p50 latency:   412 ns
  p99 latency:   2,140 ns
  Evictions:     1,204/sec
  Size:          9,847 / 10,000
```

**Implementation:** `ScheduledExecutorService` with a daemon thread. Constructor takes a
`Cache` reference and a `Histogram` reference. `close()` shuts down the executor.

---

## Test Strategy

### Unit Tests

| Test | File | Invariant proved |
|---|---|---|
| `MarketEvent.spread()` returns `ask - bid` | `MarketEventTest` | Basic record correctness |
| `MarketEvent` rejects `bid >= ask` | `MarketEventTest` | Constructor validation works |
| Simulator generates 1000 ticks, all prices > 0 | `MarketDataSimulatorTest` | GBM never goes negative under normal params |
| Simulator prices random-walk (no two consecutive identical) | `MarketDataSimulatorTest` | Randomness is working |
| Zipfian top-10 > 60% over 100k draws, skew=1.0 | `ZipfianSymbolSelectorTest` | Distribution is correct |
| Zipfian top-10 > 85% over 100k draws, skew=2.0 | `ZipfianSymbolSelectorTest` | Higher skew = more concentrated |
| BurstScheduler returns 100k at t=0, 1k at midday | `BurstSchedulerTest` | Rate profile correct |

### Benchmark Validation

After step 7 (baseline recorded):
- `ConcurrentHashMapCache` throughput: expect **80–100M ops/sec** (read-heavy, 8 threads)
- This is the theoretical ceiling for all future phases
- If < 50M ops/sec, something is wrong with JMH setup (warmup, heap size, etc.)

---

## Exit Gate

### Correctness
- [ ] All unit tests green, zero failures across `@RepeatedTest(10)` (no concurrency in Phase 0)
- [ ] Simulator: 1000 consecutive ticks have no negative prices and no bid >= ask
- [ ] Zipfian: validated over exactly 1,000,000 draws (not just 100k)
- [ ] JMH benchmarks compile to `benchmarks.jar` with `mvn package -pl cache-benchmark`

### Performance
- [ ] Baseline recorded to `results/phase-0-baseline-<timestamp>.json`
- [ ] Stub cache throughput >= 50M ops/sec (if lower, JMH setup has a problem)
- [ ] No benchmark errors or NaN values in output

### Code Quality
- [ ] `/java-best-practices` on all Phase 0 files: zero MUST FIX findings
- [ ] `/abstraction-check` on all Phase 0 files: zero MUST FIX findings

---

## Open Questions

- [ ] Should `MarketDataSimulator` support per-symbol sequence counters (more realistic) or
      a single global counter (simpler)? The plan says global — confirm before implementing.
- [ ] `StatsReporter` takes a `Cache` reference. But in Phase 0, `stats()` returns a
      placeholder `Object`. How should the reporter handle a null stats object gracefully?
      Recommendation: skip stats printing if `cache.stats()` returns null.
