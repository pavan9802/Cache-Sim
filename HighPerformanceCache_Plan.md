# High-Performance Java Cache — Full Implementation Plan

> **Project goal:** Build a Caffeine-like local cache in Java from scratch, learning concurrency,
> multithreading, GC tuning, and high-performance systems engineering progressively.
> Simulation and benchmarking infrastructure is built **first**, so every improvement is measurable.

---

## Table of Contents

1. [Design Decisions](#design-decisions)
2. [Project Structure](#project-structure)
3. [Module Responsibilities](#module-responsibilities)
4. [Maven Parent POM Setup](#maven-parent-pom-setup)
5. [Technology Stack](#technology-stack)
6. [Phase 0 — Simulation & Benchmarking Infrastructure](#phase-0--simulation--benchmarking-infrastructure)
7. [Phase 1 — Core Cache: Correct Before Fast](#phase-1--core-cache-correct-before-fast)
8. [Phase 2 — Concurrent Access: Reduce Contention](#phase-2--concurrent-access-reduce-contention)
9. [Phase 3 — High Performance: Approach Caffeine](#phase-3--high-performance-approach-caffeine)
10. [Phase 4 — Market Data Integration](#phase-4--market-data-integration)
11. [Phase 5 — Full Benchmarking & Performance Analysis](#phase-5--full-benchmarking--performance-analysis)
12. [Phase 6 — Polish & Portfolio](#phase-6--polish--portfolio)
13. [Timeline](#timeline)
14. [Benchmark Targets by Phase](#benchmark-targets-by-phase)
15. [Key Concepts Reference](#key-concepts-reference)
16. [Coding Agent Instructions](#coding-agent-instructions)

---

## Design Decisions

This section documents the key architectural choices for the project and the reasoning behind
each one. These are intentional decisions — not defaults. Understand them so you can explain
them in an interview.

---

### Decision 1: Library, Not an Application

**What we chose:** A multi-module Maven library with a thin demo runner.

**What we rejected and why:**

| Option | Why Rejected |
|---|---|
| Spring Boot app | Spring adds HTTP, dependency injection, and bean lifecycle management. A cache has no use for any of these. Adding Spring signals you reached for a framework out of habit — a red flag at a trading firm where people care deeply about minimal overhead and understanding what your code actually does. |
| CLI tool | A cache is not a command-line program. You don't "run" a cache from a terminal. The closest thing is a benchmark runner, but JMH already does that better than anything you'd build. |
| Standalone runnable JAR | Fine for demos, but implies the cache is an application with a lifecycle — wrong mental model. A cache is infrastructure consumed by other code. |

**The right mental model:** This project is a **library** — a piece of infrastructure that other
JVM applications would depend on as a Maven artifact. Think of it the way you think of Caffeine,
Guava Cache, or Ehcache: something you add to a `pom.xml` and use in your code.

**The demo module exists for one reason:** To give you something runnable to show in an interview
or on GitHub. It is not the point of the project. It is a consumer of the library, just like a
real application would be.

**How to frame this on your resume:**
> *"A high-performance local cache library for JVM applications, benchmarked against Caffeine"*

Not: *"A cache application"* or *"A Spring Boot service"*.

---

### Decision 2: Multi-Module Maven over Single Module

**What we chose:** Four Maven modules (`cache-core`, `cache-simulator`, `cache-benchmark`,
`cache-demo`) under a parent POM.

**Why not a single Maven module:**

A single module would work, but it creates problems:

- **Dependency pollution:** JMH and Caffeine (benchmark-only dependencies) would be on the
  classpath of the library itself. Anyone depending on your library would transitively pull
  in JMH — which is wrong.
- **Unclear boundaries:** It becomes unclear what is "the library" vs "the test harness" vs
  "the demo". Multi-module makes this explicit.
- **Realistic structure:** Real libraries (including Caffeine) use multi-module builds. This
  structure is what a senior engineer would expect to see.

**Dependency graph between modules:**
```
cache-core          ← no dependencies on other modules (pure library)
    ↑
cache-simulator     ← depends on cache-core
    ↑
cache-benchmark     ← depends on cache-core, cache-simulator
    ↑
cache-demo          ← depends on cache-core, cache-simulator (thin wiring layer)
```

`cache-benchmark` never ships to users. `cache-demo` is never published as an artifact.
Only `cache-core` is the deliverable.

---

### Decision 3: No Framework in `cache-core`

**What we chose:** Zero framework dependencies in the core library module.

`cache-core` depends only on:
- Java 17 standard library
- SLF4J (logging facade only — no implementation)
- JUnit 5 (test scope only)

**Why no Spring, Guice, or other frameworks in core:**

- **Portability:** Any JVM application should be able to use this cache regardless of what
  framework they use. A Spring dependency would exclude non-Spring users.
- **Performance:** Framework abstractions add overhead. The entire point of this library is
  low latency — every nanosecond counts.
- **Clarity:** Dependencies tell a reader what the code needs. A cache needs nothing but
  the JVM. Framework dependencies in a cache library are a design smell.
- **Interview signal:** Walking an interviewer through a zero-dependency core module and
  explaining why shows mature engineering judgment.

---

### Decision 4: `cache-demo` Uses `main()` Only — No Framework

**What we chose:** A plain `public static void main(String[] args)` as the entry point.

```java
public class CacheDemo {
    public static void main(String[] args) throws Exception {
        Cache<String, MarketEvent> cache = CacheBuilder.<String, MarketEvent>newBuilder()
            .maxSize(10_000)
            .ttl(30, TimeUnit.SECONDS)
            .recordStats()
            .build();

        MarketDataFeedHandler feed = new MarketDataFeedHandler(cache);
        StatsReporter reporter = new StatsReporter(cache);

        feed.start();
        reporter.start();

        Thread.sleep(60_000);  // run for 60 seconds

        feed.stop();
        reporter.stop();
        cache.close();
    }
}
```

This is intentionally simple. The demo exists to prove the library works and to give something
runnable to show. It should not grow beyond ~50 lines.

---

### Decision 5: Maven over Gradle

**What we chose:** Maven.

**Why:** Both work. Maven is more verbose but more explicit — you always know exactly what is
happening and why. Gradle's DSL has more magic. For a learning project where understanding
the build is part of the education, Maven's verbosity is a feature, not a bug.

If you already know Gradle well, use it. The module structure is identical.

---

### Decision 6: `cache-simulator` is a Separate Module, Not Test Code

The market data simulator lives in its own module, not in `src/test/java`. This is deliberate:

- The simulator is also used by `cache-demo` at runtime — it is not test-only infrastructure
- It is a meaningful piece of work in its own right and deserves its own module boundary
- Separating it makes the dependency graph explicit: both demo and benchmark consume it

---

## Project Structure

```
high-performance-cache/                  ← Git root, parent POM lives here
├── pom.xml                              ← Parent POM: manages versions, common config
│
├── cache-core/                          ← THE LIBRARY (the deliverable)
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/hpcache/
│       │   ├── api/
│       │   │   ├── Cache.java              # Core interface
│       │   │   ├── LoadingCache.java       # Cache with loader function
│       │   │   ├── AsyncLoadingCache.java  # Async loader variant
│       │   │   ├── CacheStats.java         # Stats snapshot object
│       │   │   ├── RemovalListener.java    # Eviction callback interface
│       │   │   ├── RemovalCause.java       # Enum: SIZE, EXPIRED, EXPLICIT
│       │   │   └── StatsReporter.java      # Periodic console stats printer
│       │   ├── impl/
│       │   │   ├── LRUCache.java           # Phase 1: basic LRU + ReadWriteLock
│       │   │   ├── StripedCache.java       # Phase 2: lock striping
│       │   │   ├── StampedCache.java       # Phase 2: StampedLock + optimistic reads
│       │   │   └── TinyLFUCache.java       # Phase 3: frequency-based eviction
│       │   ├── internal/                   # Package-private — NOT public API
│       │   │   ├── CacheEntry.java         # Value + expiry wrapper
│       │   │   ├── eviction/
│       │   │   │   ├── EvictionPolicy.java
│       │   │   │   ├── LRUPolicy.java
│       │   │   │   └── TinyLFUPolicy.java
│       │   │   ├── sketch/
│       │   │   │   └── CountMinSketch.java # Phase 3: frequency estimation
│       │   │   └── buffer/
│       │   │       ├── ReadBuffer.java     # Phase 3: lock-free read recording
│       │   │       └── WriteBuffer.java    # Phase 3: batched write ops
│       │   ├── offheap/
│       │   │   └── OffHeapStore.java       # Phase 3 extension
│       │   └── builder/
│       │       └── CacheBuilder.java       # Fluent builder — only construction path
│       └── test/java/com/hpcache/
│           ├── impl/
│           │   ├── LRUCacheTest.java
│           │   ├── LRUCacheConcurrencyTest.java
│           │   ├── StripedCacheTest.java
│           │   └── TinyLFUCacheTest.java
│           └── internal/
│               └── sketch/
│                   └── CountMinSketchTest.java
│
├── cache-simulator/                     ← Market data simulation (used by demo + benchmark)
│   ├── pom.xml
│   └── src/main/java/com/hpcache/sim/
│       ├── MarketEvent.java             # Price tick record
│       ├── MarketDataSimulator.java     # GBM price generator
│       ├── ZipfianSymbolSelector.java   # Realistic hot-symbol distribution
│       ├── BurstScheduler.java          # Market open/close tick rate variation
│       ├── MarketDataFeedHandler.java   # Wires simulator → cache via BlockingQueue
│       ├── PriceService.java            # Read layer: cache → price queries
│       └── scenarios/
│           ├── NormalMarketScenario.java
│           ├── MarketOpenScenario.java
│           ├── FlashCrashScenario.java
│           └── SymbolChurnScenario.java
│
├── cache-benchmark/                     ← JMH benchmarks (never shipped)
│   ├── pom.xml
│   └── src/main/java/com/hpcache/bench/
│       ├── BenchmarkBase.java           # Shared JMH state and setup
│       ├── ReadHeavyBenchmark.java      # 95% read / 5% write
│       ├── WriteHeavyBenchmark.java     # 50% read / 50% write
│       ├── ZipfianBenchmark.java        # Zipfian key distribution
│       ├── ThreadScalingBenchmark.java  # 1→2→4→8→16 threads
│       ├── EvictionBenchmark.java       # Cache at capacity, constant churn
│       ├── TTLBenchmark.java            # High expiry rate overhead
│       └── ComparisonBenchmark.java     # Your cache vs Caffeine vs Guava
│
├── cache-demo/                          ← Thin runnable demo (not a deliverable)
│   ├── pom.xml
│   └── src/main/java/com/hpcache/demo/
│       └── CacheDemo.java              # main() — wires everything, runs 60 seconds
│
├── results/                             ← JMH output JSON files (gitignored)
│   ├── phase-0-baseline.json
│   ├── phase-1.json
│   └── ...
│
└── scripts/
    ├── run_benchmarks.sh                # Runs full JMH suite, saves to results/
    ├── compare_phases.sh                # Diffs two result JSON files
    ├── generate_flamegraph.sh           # async-profiler wrapper
    └── parse_gc_log.py                  # Extracts GC pause stats from logs
```

---

## Module Responsibilities

Each module has a single, clear responsibility. If you find yourself adding code that doesn't
fit the description below, it belongs in a different module.

| Module | Responsibility | Ships to users? | Framework deps? |
|---|---|---|---|
| `cache-core` | The cache library itself — all cache implementations, interfaces, builder | Yes (Maven artifact) | None |
| `cache-simulator` | Market data generation for testing and demo | No | None |
| `cache-benchmark` | JMH benchmark suite — measures and compares implementations | No | JMH only |
| `cache-demo` | Thin `main()` that demonstrates the library working end-to-end | No | None |

### What goes where — quick reference

| Code | Module |
|---|---|
| `Cache<K,V>` interface | `cache-core` |
| `LRUCache`, `TinyLFUCache` | `cache-core` |
| `CacheBuilder` | `cache-core` |
| `CountMinSketch` | `cache-core/internal` (not public API) |
| `MarketEvent`, `MarketDataSimulator` | `cache-simulator` |
| `ZipfianSymbolSelector` | `cache-simulator` |
| JMH `@Benchmark` methods | `cache-benchmark` |
| Caffeine/Guava dependencies | `cache-benchmark` only |
| `public static void main()` | `cache-demo` |
| GC log parser | `scripts/` |

---

## Maven Parent POM Setup

The parent POM at the root manages Java version, common dependencies, and plugin versions
so each child module `pom.xml` stays minimal.

**`pom.xml` (root — parent):**
```xml
<project>
  <groupId>com.hpcache</groupId>
  <artifactId>high-performance-cache</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <modules>
    <module>cache-core</module>
    <module>cache-simulator</module>
    <module>cache-benchmark</module>
    <module>cache-demo</module>
  </modules>

  <properties>
    <java.version>17</java.version>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <jmh.version>1.37</jmh.version>
    <caffeine.version>3.1.8</caffeine.version>
    <hdrhistogram.version>2.2.2</hdrhistogram.version>
    <junit.version>5.10.0</junit.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <!-- Inter-module deps — version managed here -->
      <dependency>
        <groupId>com.hpcache</groupId>
        <artifactId>cache-core</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>com.hpcache</groupId>
        <artifactId>cache-simulator</artifactId>
        <version>${project.version}</version>
      </dependency>
      <!-- Third-party -->
      <dependency>
        <groupId>org.openjdk.jmh</groupId>
        <artifactId>jmh-core</artifactId>
        <version>${jmh.version}</version>
      </dependency>
      <dependency>
        <groupId>com.github.ben-manes.caffeine</groupId>
        <artifactId>caffeine</artifactId>
        <version>${caffeine.version}</version>
      </dependency>
      <dependency>
        <groupId>org.hdrhistogram</groupId>
        <artifactId>HdrHistogram</artifactId>
        <version>${hdrhistogram.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
```

**`cache-core/pom.xml` (child — the library):**
```xml
<project>
  <parent>
    <groupId>com.hpcache</groupId>
    <artifactId>high-performance-cache</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>
  <artifactId>cache-core</artifactId>

  <dependencies>
    <!-- Logging facade only — no implementation (consumer provides it) -->
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-api</artifactId>
    </dependency>
    <!-- Test only -->
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

**`cache-benchmark/pom.xml` (child — benchmarks only):**
```xml
<dependencies>
  <dependency>
    <groupId>com.hpcache</groupId>
    <artifactId>cache-core</artifactId>       <!-- our library -->
  </dependency>
  <dependency>
    <groupId>com.hpcache</groupId>
    <artifactId>cache-simulator</artifactId>  <!-- for realistic workloads -->
  </dependency>
  <dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-core</artifactId>         <!-- benchmark framework -->
  </dependency>
  <dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>         <!-- comparison target -->
  </dependency>
  <dependency>
    <groupId>com.google.guava</groupId>
    <artifactId>guava</artifactId>            <!-- comparison target -->
  </dependency>
  <dependency>
    <groupId>org.hdrhistogram</groupId>
    <artifactId>HdrHistogram</artifactId>
  </dependency>
</dependencies>
```

Note: Caffeine and Guava only appear in `cache-benchmark`. They are never in `cache-core`.

---

## Technology Stack

| Dependency | Version | Purpose |
|---|---|---|
| Java | 17+ | VarHandle, records, sealed interfaces |
| Maven / Gradle | Latest | Build tool (either works) |
| JMH | 1.37 | Microbenchmarking |
| HdrHistogram | 2.2.x | High-precision latency histograms |
| async-profiler | 3.x | CPU flame graphs, allocation profiling |
| Caffeine | 3.x | Benchmark comparison target |
| Guava Cache | 32.x | Benchmark comparison target |
| Apache Commons Math | 3.x | Zipfian distribution |
| SLF4J + Logback | Latest | Structured logging |
| JUnit 5 | Latest | Unit and concurrency tests |
| jcstress | Latest | Concurrency correctness testing |

### Maven dependency snippet
```xml
<!-- JMH -->
<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-core</artifactId>
    <version>1.37</version>
</dependency>
<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-generator-annprocess</artifactId>
    <version>1.37</version>
    <scope>provided</scope>
</dependency>

<!-- HdrHistogram -->
<dependency>
    <groupId>org.hdrhistogram</groupId>
    <artifactId>HdrHistogram</artifactId>
    <version>2.2.2</version>
</dependency>

<!-- Caffeine (for comparison) -->
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
    <version>3.1.8</version>
</dependency>
```

---

## Phase 0 — Simulation & Benchmarking Infrastructure

> **Duration:** 1 week
> **Concurrency concepts:** None yet — pure setup
> **Goal:** Have benchmarks running and baseline numbers recorded before writing a single line of cache code.

---

### 0A — `MarketEvent.java`

A record representing a single price tick from the market.

```
File: cache-simulator/src/main/java/com/hpcache/sim/MarketEvent.java
```

**Fields:**
- `String symbol` — ticker symbol e.g. "AAPL"
- `double bidPrice` — best bid
- `double askPrice` — best ask (always > bidPrice)
- `long volume` — number of shares/contracts
- `long timestampNanos` — `System.nanoTime()` at generation time
- `int sequenceNumber` — monotonically increasing per symbol

**Requirements:**
- Must be a Java `record` (immutable, compact constructor)
- `spread()` default method returning `askPrice - bidPrice`
- `midPrice()` default method returning `(bidPrice + askPrice) / 2.0`
- Override `toString()` for readable logging

---

### 0B — `MarketDataSimulator.java`

Generates a continuous stream of realistic price ticks using Geometric Brownian Motion (GBM).

```
File: cache-simulator/src/main/java/com/hpcache/sim/MarketDataSimulator.java
```

**GBM formula:**
```
nextPrice = currentPrice * exp((drift - 0.5 * volatility²) * dt + volatility * sqrt(dt) * Z)
where Z ~ N(0,1) (standard normal random variable)
```

**Fields:**
- `Map<String, Double> currentPrices` — current price per symbol
- `Map<String, Double> volatilities` — per-symbol volatility (e.g. 0.02 = 2%)
- `double drift` — global drift (use 0.0 for zero-drift simulation)
- `double dt` — time step (e.g. 1.0/252 for one trading day)
- `Random random` — use `ThreadLocalRandom` for thread safety
- `int sequenceCounter` — global tick sequence number (AtomicInteger)

**Methods:**
- `MarketDataSimulator(List<String> symbols, double basePrice, double volatility)`
- `MarketEvent nextTick(String symbol)` — generates one tick for given symbol
- `MarketEvent nextTick()` — generates tick for a randomly selected symbol
- `void setVolatility(String symbol, double vol)` — override per-symbol vol
- `Map<String, Double> getCurrentPrices()` — snapshot of all current prices

**Symbol universe:** Default 100 symbols: AAPL, MSFT, GOOGL, AMZN, TSLA, ... generate programmatically as SYM001–SYM097 for the rest.

---

### 0C — `ZipfianSymbolSelector.java`

Selects symbols according to a Zipfian distribution — top symbols get exponentially more traffic, mimicking real market activity.

```
File: cache-simulator/src/main/java/com/hpcache/sim/ZipfianSymbolSelector.java
```

**Fields:**
- `List<String> symbols` — ordered list, index 0 = hottest
- `double[] cumulativeProbabilities` — precomputed CDF of Zipf distribution
- `double skew` — Zipf exponent, default 1.0 (higher = more skewed)

**Methods:**
- `ZipfianSymbolSelector(List<String> symbols, double skew)`
- `String select()` — returns a symbol using binary search on CDF
- `Map<String, Double> getProbabilities()` — returns expected access % per symbol

**Zipf probability formula:**
```
P(rank k) = (1/k^skew) / sum(1/i^skew for i=1..N)
```

**Validation:** Top 10 symbols should receive ~65–80% of all selections when skew=1.0 with 100 symbols.

---

### 0D — `BurstScheduler.java`

Simulates time-of-day tick rate variation: high activity at market open/close, quiet midday.

```
File: cache-simulator/src/main/java/com/hpcache/sim/BurstScheduler.java
```

**Tick rate profile (events/second):**
| Time (simulated) | Rate |
|---|---|
| Market open (first 5 min) | 100,000 |
| Morning (next 25 min) | 10,000 |
| Midday | 1,000 |
| Afternoon (last 25 min) | 10,000 |
| Market close (last 5 min) | 100,000 |

**Methods:**
- `BurstScheduler(long simulatedDayDurationMs)` — compress a full day into given duration
- `int getCurrentTickRate()` — returns target ticks/sec at current simulated time
- `void reset()` — restart the simulation day

---

### 0E — `BenchmarkBase.java`

Shared JMH state class all benchmarks extend from. Sets up cache, simulator, and histogram.

```
File: cache-benchmark/src/main/java/com/hpcache/bench/BenchmarkBase.java
```

**JMH State fields (annotated `@State(Scope.Benchmark)`):**
- `Cache<String, MarketEvent> cache` — the cache under test
- `MarketDataSimulator simulator` — event generator
- `ZipfianSymbolSelector selector` — key selector
- `Histogram latencyHistogram` — HdrHistogram instance (max 10 seconds, 3 sig figures)
- `AtomicLong operationCount` — total ops counter
- `String[] keyUniverse` — pre-generated array of 10,000 keys

**JMH annotations to use:**
```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgs = {"-Xms2g", "-Xmx2g", "-XX:+UseG1GC"})
@Threads(8)  // override per benchmark class
```

**Setup method `@Setup(Level.Trial)`:**
1. Build cache with `maxSize=10_000`, `ttl=30s`
2. Pre-populate cache to 80% capacity
3. Initialise HdrHistogram
4. Pre-generate `keyUniverse` array

**Teardown method `@TearDown(Level.Trial)`:**
1. Print HdrHistogram percentiles: p50, p75, p95, p99, p999
2. Print hit rate from cache stats
3. Save results to `results/phase-X-timestamp.json`

---

### 0F — `ReadHeavyBenchmark.java`

```
File: cache-benchmark/src/main/java/com/hpcache/bench/ReadHeavyBenchmark.java
```

**Workload:** 95% `get`, 5% `put`. Uniform key distribution.

**Implementation:**
```java
@Benchmark
public Object readHeavy(BenchmarkState state, Blackhole bh) {
    // Use ThreadLocalRandom to decide read vs write
    if (ThreadLocalRandom.current().nextInt(100) < 95) {
        String key = state.keyUniverse[ThreadLocalRandom.current().nextInt(state.keyUniverse.length)];
        bh.consume(state.cache.get(key));  // Blackhole prevents dead code elimination
    } else {
        String key = state.keyUniverse[ThreadLocalRandom.current().nextInt(state.keyUniverse.length)];
        state.cache.put(key, state.simulator.nextTick(key));
    }
    return null;
}
```

**Why `Blackhole.consume()`:** Without it, the JIT eliminates the get() call since the result is unused — producing meaningless benchmark numbers.

---

### 0G — `WriteHeavyBenchmark.java`

**Workload:** 50% `get`, 50% `put`. Uniform key distribution. Stresses lock contention.

Same structure as `ReadHeavyBenchmark` but with 50/50 split.

---

### 0H — `ZipfianBenchmark.java`

**Workload:** 95% `get`, 5% `put`. **Zipfian key distribution** — top 10 keys get ~70% of traffic.

Replace `keyUniverse[random index]` with `state.selector.select()` for key selection.

---

### 0I — `ThreadScalingBenchmark.java`

Run the same read-heavy workload at thread counts: 1, 2, 4, 8, 16.

**JMH approach:** Create 5 separate `@Benchmark` methods or use a parameterised runner. The recommended approach is a separate benchmark class per thread count using `@Threads(N)` annotation.

**What to look for:**
- Throughput should scale linearly up to the number of physical cores
- Throughput flattening or dropping indicates lock contention
- Record the "contention cliff" thread count — this is what Phase 2 improves

---

### 0J — `StatsReporter.java`

Periodic console reporter that prints cache stats during long-running tests.

```
File: cache-core/src/main/java/com/hpcache/api/StatsReporter.java
```

**Output format (printed every 5 seconds):**
```
[14:32:05] Cache Stats
  Hit Rate:      87.3%
  Throughput:    4,231,847 ops/sec
  p50 latency:   412 ns
  p99 latency:   2,140 ns
  p999 latency:  18,430 ns
  Evictions:     1,204/sec
  Size:          9,847 / 10,000
```

**Implementation:** Uses `ScheduledExecutorService` with a daemon thread. Takes a `Cache` reference and a `Histogram` reference.

---

### Phase 0 Deliverables Checklist

- [ ] `MarketEvent` record compiles and `spread()` / `midPrice()` work correctly
- [ ] `MarketDataSimulator` generates positive prices that random-walk realistically
- [ ] `ZipfianSymbolSelector` — verified top 10 symbols get >60% of selections over 1M draws
- [ ] `BurstScheduler` — tick rate changes correctly over simulated day
- [ ] JMH benchmarks compile and run (even with a stub `HashMap`-backed cache)
- [ ] Baseline numbers recorded and saved to `results/phase-0-baseline.json`
- [ ] `StatsReporter` prints readable output every 5 seconds

---

## Phase 1 — Core Cache: Correct Before Fast

> **Duration:** 2–3 weeks
> **Concurrency concepts:** `ReentrantReadWriteLock`, `volatile`, `AtomicLong`, `ScheduledExecutorService`
> **Goal:** A correct, thread-safe LRU cache with TTL expiry. Run benchmarks after every feature. Do NOT optimise yet.

---

### 1A — `Cache.java` (Interface)

```
File: cache-core/src/main/java/com/hpcache/api/Cache.java
```

**Methods:**
```java
V get(K key);
void put(K key, V value);
void put(K key, V value, long ttlMs);         // per-entry TTL override
void invalidate(K key);
void invalidateAll();
boolean containsKey(K key);
int size();
CacheStats stats();
void close();                                  // shuts down background threads
```

**Design notes:**
- Generic `<K, V>` — keys must implement `hashCode()` and `equals()`
- `get()` returns `null` on miss (not Optional — performance critical code avoids Optional)
- `close()` must be idempotent — safe to call multiple times

---

### 1B — `CacheStats.java`

Immutable snapshot of cache statistics at a point in time.

```
File: cache-core/src/main/java/com/hpcache/api/CacheStats.java
```

**Fields (all `final long`):**
- `hitCount`
- `missCount`
- `evictionCount`
- `loadCount`
- `totalLoadTimeNanos`
- `snapshotTimestampMs`

**Computed methods:**
- `double hitRate()` — `hitCount / (hitCount + missCount)`
- `double missRate()` — `1.0 - hitRate()`
- `double averageLoadTimeMs()` — `totalLoadTimeNanos / loadTimeCount / 1_000_000.0`
- `long requestCount()` — `hitCount + missCount`
- `String formatted()` — human-readable multi-line string

---

### 1C — `CacheEntry.java`

Internal wrapper storing a value with its expiry time.

```
File: cache-core/src/main/java/com/hpcache/impl/CacheEntry.java
```

```java
record CacheEntry<V>(V value, long expiryNanos) {
    // expiryNanos = Long.MAX_VALUE means no expiry
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

**Why nanoTime not currentTimeMillis:**
`System.nanoTime()` is monotonically increasing and not affected by system clock adjustments — critical for TTL correctness.

---

### 1D — `LRUCache.java`

The core Phase 1 implementation.

```
File: cache-core/src/main/java/com/hpcache/impl/LRUCache.java
```

**Internal data structure:**
```java
private final LinkedHashMap<K, CacheEntry<V>> map;
private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
private final int maxSize;
private final long defaultTtlMs;
```

**LinkedHashMap configuration:**
```java
this.map = new LinkedHashMap<>(capacity, 0.75f, true) {  // accessOrder=true
    @Override
    protected boolean removeEldestEntry(Map.Entry<K, CacheEntry<V>> eldest) {
        if (size() > maxSize) {
            evictionCount.incrementAndGet();
            removalListener.accept(eldest.getKey(), eldest.getValue().value());
            return true;
        }
        return false;
    }
};
```

**`get(K key)` — CRITICAL implementation detail:**

`accessOrder=true` means `LinkedHashMap.get()` mutates internal node order. This is a **write** operation on the map. Therefore `get()` must use `writeLock`, not `readLock`:

```java
public V get(K key) {
    lock.writeLock().lock();  // NOT readLock — accessOrder mutation requires writeLock
    try {
        CacheEntry<V> entry = map.get(key);  // moves entry to tail (most recently used)
        if (entry == null) {
            missCount.incrementAndGet();
            return null;
        }
        if (entry.isExpired()) {
            map.remove(key);
            evictionCount.incrementAndGet();
            missCount.incrementAndGet();
            return null;
        }
        hitCount.incrementAndGet();
        return entry.value();
    } finally {
        lock.writeLock().unlock();
    }
}
```

**`put(K key, V value)`:**
```java
public void put(K key, V value) {
    put(key, value, defaultTtlMs);
}

public void put(K key, V value, long ttlMs) {
    lock.writeLock().lock();
    try {
        CacheEntry<V> entry = ttlMs <= 0
            ? CacheEntry.noExpiry(value)
            : CacheEntry.withTtl(value, ttlMs);
        map.put(key, entry);  // removeEldestEntry fires here if over capacity
    } finally {
        lock.writeLock().unlock();
    }
}
```

**Background TTL cleanup thread:**
```java
private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "cache-cleaner");
    t.setDaemon(true);  // does not prevent JVM shutdown
    return t;
});

// In constructor:
cleaner.scheduleAtFixedRate(this::removeExpiredEntries, 1, 1, TimeUnit.SECONDS);

private void removeExpiredEntries() {
    lock.writeLock().lock();
    try {
        map.entrySet().removeIf(e -> e.getValue().isExpired());
    } finally {
        lock.writeLock().unlock();
    }
}
```

**Atomic counters (declared at class level):**
```java
private final AtomicLong hitCount = new AtomicLong();
private final AtomicLong missCount = new AtomicLong();
private final AtomicLong evictionCount = new AtomicLong();
```

---

### 1E — `RemovalListener.java`

Callback interface invoked on eviction or TTL expiry.

```java
@FunctionalInterface
public interface RemovalListener<K, V> {
    void onRemoval(K key, V value, RemovalCause cause);
}

enum RemovalCause { SIZE, EXPIRED, EXPLICIT }
```

---

### 1F — `CacheBuilder.java`

Fluent builder. This is the **only** way users should construct a cache.

```
File: cache-core/src/main/java/com/hpcache/builder/CacheBuilder.java
```

**Full builder API:**
```java
Cache<String, MarketEvent> cache = CacheBuilder.<String, MarketEvent>newBuilder()
    .maxSize(10_000)
    .ttl(30, TimeUnit.SECONDS)
    .recordStats()
    .removalListener((key, value, cause) -> log.info("Evicted: {}", key))
    .build();
```

**Validation in `build()`:**
- `maxSize` must be > 0
- `ttl` must be > 0 if set
- Throw `IllegalStateException` with clear message on invalid config

---

### 1G — Concurrency Tests

Tests that verify thread safety — not just functional correctness.

```
File: cache-core/src/test/java/com/hpcache/impl/LRUCacheConcurrencyTest.java
```

**Test 1 — No data corruption under concurrent writes:**
```java
// 16 threads each writing 10,000 entries
// After completion: verify cache is consistent (no exceptions, size <= maxSize)
CountDownLatch start = new CountDownLatch(1);
CountDownLatch done = new CountDownLatch(16);
// ... launch threads, release latch, await done, assert
```

**Test 2 — No stale reads after TTL:**
```java
// Put entry with 100ms TTL
// Sleep 200ms
// Assert get() returns null
```

**Test 3 — Eviction count accuracy:**
```java
// Fill cache to exactly maxSize + 1000
// Assert evictionCount == 1000
```

**Test 4 — Concurrent get+put on same key (no deadlock):**
```java
// 8 reader threads + 4 writer threads hammering the same key for 5 seconds
// Assert no deadlock (test completes within 10 seconds)
```

---

### Phase 1 Deliverables Checklist

- [ ] `Cache<K,V>` interface defined with all methods
- [ ] `CacheStats` immutable record with `hitRate()`, `missRate()`, `formatted()`
- [ ] `CacheEntry` record with `isExpired()`, `noExpiry()`, `withTtl()` factories
- [ ] `LRUCache` compiles and passes all unit tests
- [ ] `get()` correctly uses `writeLock` (not readLock) due to accessOrder mutation
- [ ] TTL expiry works: expired entries return null and are removed
- [ ] Background cleaner runs every second and removes stale entries
- [ ] `RemovalListener` fires on size eviction and TTL expiry with correct `RemovalCause`
- [ ] `CacheBuilder` fluent API with validation
- [ ] All 4 concurrency tests pass
- [ ] JMH benchmarks run against `LRUCache` — baseline numbers saved to `results/phase-1.json`
- [ ] `StatsReporter` shows realistic hit rates against simulator workload

---

## Phase 2 — Concurrent Access: Reduce Contention

> **Duration:** 2–3 weeks
> **Concurrency concepts:** Lock striping, `StampedLock`, optimistic reads, `CompletableFuture`, per-key locking
> **Goal:** Throughput scales with thread count. Every sub-phase produces measurable improvement.

---

### 2A — `StripedCache.java`

Divide the cache into N independent segments. Keys hash to segments — threads on different segments never contend.

```
File: cache-core/src/main/java/com/hpcache/impl/StripedCache.java
```

**Internal structure:**
```java
private final LRUCache<K, V>[] segments;
private final int stripeCount;
private final int stripeMask;  // stripeCount - 1 (requires power-of-two stripeCount)
```

**Stripe selection:**
```java
private int stripeFor(K key) {
    int hash = key.hashCode();
    // Wang hash — spreads clustered hashCodes
    hash = ((hash >>> 16) ^ hash) * 0x45d9f3b;
    hash = ((hash >>> 16) ^ hash) * 0x45d9f3b;
    hash = (hash >>> 16) ^ hash;
    return hash & stripeMask;
}
```

**Configuration:**
- Default stripe count: `Runtime.getRuntime().availableProcessors() * 2`, rounded up to next power of two
- Each segment gets `maxSize / stripeCount` capacity
- Stats are aggregated across all segments

**Why power of two:** Allows `hash & (N-1)` instead of `hash % N` — avoids expensive division on the hot path.

**Benchmark expectation:** Throughput should scale near-linearly up to `stripeCount` threads. Beyond that it flattens (each segment is still a single lock).

---

### 2B — `StampedCache.java`

Replace `ReentrantReadWriteLock` with `StampedLock` and use optimistic reads for the get path.

```
File: cache-core/src/main/java/com/hpcache/impl/StampedCache.java
```

**How optimistic reads work:**
```java
public V get(K key) {
    // Attempt 1: optimistic read (no lock acquired)
    long stamp = lock.tryOptimisticRead();
    CacheEntry<V> entry = map.get(key);  // may see torn state if concurrent write
    
    if (!lock.validate(stamp)) {
        // Concurrent write happened — fall back to full read lock
        stamp = lock.readLock();
        try {
            entry = map.get(key);
        } finally {
            lock.unlockRead(stamp);
        }
    }
    
    // ... check expiry, update stats
}
```

**Important:** `StampedLock` is **not reentrant**. Never call a method that acquires the lock from within a locked section — this will deadlock.

**When optimistic reads win:** When write rate is low (< 10%) and reads are short. Under high write rates, optimistic reads fail frequently and the retry overhead makes things worse.

**Benchmark this:** Run `ReadHeavyBenchmark` (95/5) and `WriteHeavyBenchmark` (50/50) against both `StampedCache` and the Phase 1 `LRUCache`. Document the crossover point.

---

### 2C — `LoadingCache.java`

Extends `Cache<K,V>` with a loader function and thundering herd prevention.

```
File: cache-core/src/main/java/com/hpcache/api/LoadingCache.java
```

**Interface additions:**
```java
V get(K key, Function<K, V> loader);   // load on miss using provided function
V getOrLoad(K key);                     // load on miss using configured loader
void refresh(K key);                    // force reload even if present
```

**Thundering herd prevention — `LoadingCacheImpl.java`:**

```java
// In-flight loads: key → future being computed
private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

public V get(K key, Function<K, V> loader) {
    V cached = super.get(key);
    if (cached != null) return cached;

    // computeIfAbsent is atomic — only ONE thread creates the future
    CompletableFuture<V> future = inFlight.computeIfAbsent(key, k ->
        CompletableFuture.supplyAsync(() -> {
            V value = loader.apply(k);    // only one thread runs this
            put(k, value);
            inFlight.remove(k);
            return value;
        })
    );

    try {
        return future.get();  // all other threads wait on the same future
    } catch (Exception e) {
        inFlight.remove(key);
        throw new CacheLoadException(key, e);
    }
}
```

**Test:** Launch 16 threads simultaneously requesting an expired hot key. Assert the loader function is called exactly once (not 16 times).

---

### 2D — `AsyncLoadingCache.java`

Non-blocking variant. Returns `CompletableFuture<V>` immediately.

```
File: cache-core/src/main/java/com/hpcache/api/AsyncLoadingCache.java
```

**Interface:**
```java
CompletableFuture<V> getAsync(K key);
CompletableFuture<V> getAsync(K key, Function<K, CompletableFuture<V>> asyncLoader);
```

**Refresh-ahead:** When a cached entry is within `refreshThreshold` of expiry, trigger a background reload. The current (slightly stale) value is returned immediately — no caller ever waits.

```java
private void maybeRefreshAhead(K key, CacheEntry<V> entry) {
    long remainingNanos = entry.expiryNanos() - System.nanoTime();
    if (remainingNanos < refreshThresholdNanos && !inFlight.containsKey(key)) {
        // Trigger background refresh — don't wait for it
        inFlight.computeIfAbsent(key, k ->
            CompletableFuture.supplyAsync(() -> { /* reload */ }, refreshExecutor)
        );
    }
}
```

---

### 2E — Concurrency Tests for Phase 2

**Test: Stripe distribution uniformity**
```
// Insert 100,000 keys and verify each stripe holds roughly capacity/stripeCount entries
// Max deviation from expected: 20%
```

**Test: No thundering herd**
```
// 16 threads, cold cache, all request same key simultaneously
// Assert loader called exactly 1 time
// Assert all 16 threads return same correct value
```

**Test: StampedLock optimistic read under concurrent writes**
```
// 8 reader threads + 4 writer threads, 10 seconds
// Assert zero incorrect reads (all reads are either valid or null)
// Assert no StampedLock validation failures cause crashes
```

---

### Phase 2 Deliverables Checklist

- [ ] `StripedCache` compiles with configurable stripe count (power of two)
- [ ] Stripe selection uses Wang hash — verified uniform distribution
- [ ] JMH thread scaling benchmark shows near-linear scaling up to stripe count
- [ ] `StampedCache` implemented with optimistic read + fallback to read lock
- [ ] Benchmark comparison documented: StampedLock vs ReadWriteLock under 95/5 and 50/50
- [ ] `LoadingCache` with `ConcurrentHashMap<K, CompletableFuture>` for in-flight deduplication
- [ ] Thundering herd test passes — loader called exactly once for 16 concurrent misses
- [ ] `AsyncLoadingCache` with refresh-ahead that never blocks callers
- [ ] `results/phase-2.json` saved — shows improvement over phase-1

---

## Phase 3 — High Performance: Approach Caffeine

> **Duration:** 3–4 weeks
> **Concurrency concepts:** `VarHandle`, CAS operations, lock-free data structures, Java Memory Model, false sharing
> **Goal:** Understand the techniques behind Caffeine. Match 60–80% of its throughput.

---

### 3A — `CountMinSketch.java`

Probabilistic frequency counter. Estimates how many times a key has been accessed using a compact 2D array of counters. Uses much less memory than a full HashMap while providing good accuracy.

```
File: cache-core/src/main/java/com/hpcache/sketch/CountMinSketch.java
```

**Internal structure:**
```java
private final long[] table;       // flat array: rows * width longs
private final int width;          // columns, power of two for fast mod
private final int[] hashSeeds;    // 4 different hash seeds, one per row
private static final int ROWS = 4;
```

**Width calculation:**
```java
// Nearest power of two >= capacity * 10
int width = Integer.highestOneBit(capacity * 10 - 1) << 1;
```

**`void increment(K key)`:**
```java
// Increment counter in each of 4 rows at different column positions
// Each counter is 4-bit (values 0–15) packed into 64-bit longs (16 counters per long)
int hash = spread(key.hashCode());
for (int i = 0; i < ROWS; i++) {
    int col = (hash >>> (i * 8)) & (width - 1);
    incrementCounter(i, col);
}
// Periodically halve all counters (aging) when total increments exceed threshold
if (++totalIncrements >= resetThreshold) reset();
```

**`int frequency(K key)`:**
```java
// Return MINIMUM across all 4 rows (hence "Count-Min")
int hash = spread(key.hashCode());
int min = Integer.MAX_VALUE;
for (int i = 0; i < ROWS; i++) {
    int col = (hash >>> (i * 8)) & (width - 1);
    min = Math.min(min, getCounter(i, col));
}
return min;
```

**4-bit counter packing (16 counters per long):**
```java
private void incrementCounter(int row, int col) {
    int index = (row * width + col) >>> 4;    // which long
    int offset = ((row * width + col) & 0xF) << 2;  // bit position within long
    long current = (table[index] >>> offset) & 0xFL;
    if (current < 15) {  // saturate at 15, don't overflow into neighbour
        table[index] += (1L << offset);
    }
}
```

**Aging (reset):**
```java
// Halve all counters every `resetThreshold` increments
// This ages out old frequency data so recent access patterns dominate
private void reset() {
    for (int i = 0; i < table.length; i++) {
        // Right-shift each 4-bit counter by 1, preserving nibble boundaries
        table[i] = (table[i] >>> 1) & 0x7777777777777777L;
    }
    totalIncrements = 0;
}
```

**Tests for CountMinSketch:**
- Insert key 1000 times, assert `frequency()` returns value close to 1000 (within 10%)
- Insert N distinct keys once each, assert no key has frequency > 5 (low false positives)
- Verify aging: after reset, frequencies halve

---

### 3B — `TinyLFUPolicy.java` — Window TinyLFU

Uses the CountMinSketch to make eviction decisions: evict the entry with lower estimated frequency.

```
File: cache-core/src/main/java/com/hpcache/eviction/TinyLFUPolicy.java
```

**Window TinyLFU architecture (three segments):**
```
┌─────────────────────────────────────────────────────┐
│  Window LRU (1%)  │  Protected LRU (80%)  │  Probationary LRU (20%)  │
└─────────────────────────────────────────────────────┘
```

**How it works:**
1. New entries enter the **Window** segment (small, admits everything)
2. On Window eviction, candidate competes with the tail of **Probationary** using the frequency sketch
3. Winner enters **Protected**; loser is evicted from the cache
4. Protected entries demoted to Probationary when Protected overflows

**Why three segments?**
- Window prevents one-hit wonders from polluting the main cache
- Protected keeps hot entries safe from eviction
- Probationary is the testing ground — entries prove their value to move to Protected

**Implementation:**
```java
public class TinyLFUPolicy<K, V> implements EvictionPolicy<K, V> {
    private final LinkedHashMap<K, V> window;
    private final LinkedHashMap<K, V> probationary;
    private final LinkedHashMap<K, V> protected_;
    private final CountMinSketch<K> sketch;
    private final int windowSize;
    private final int protectedSize;
    private final int probationarySize;
    
    public K selectVictim(K candidate) {
        K probationaryTail = getEldest(probationary);
        // Keep the one with higher frequency
        return sketch.frequency(candidate) > sketch.frequency(probationaryTail)
            ? probationaryTail   // evict probationary tail
            : candidate;         // reject candidate, evict candidate
    }
}
```

**Benchmark hit rate comparison:**
Run `ZipfianBenchmark` against LRU and TinyLFU. Expected: TinyLFU has 10–30% better hit rate under realistic Zipfian workloads.

---

### 3C — `ReadBuffer.java` — Lock-Free Read Recording

Caffeine's key insight: reads need to update access order and frequency, but these updates don't need to be synchronous. Buffer them per-thread and drain in batches during writes.

```
File: cache-core/src/main/java/com/hpcache/buffer/ReadBuffer.java
```

**Ring buffer per thread:**
```java
private static final int BUFFER_SIZE = 16;  // power of two
private static final int BUFFER_MASK = BUFFER_SIZE - 1;

// One ring buffer per thread — no sharing, no contention
private final ThreadLocal<long[]> readEvents = ThreadLocal.withInitial(() -> new long[BUFFER_SIZE]);
private final ThreadLocal<AtomicInteger> tail = ThreadLocal.withInitial(AtomicInteger::new);
```

**Recording a read (called from `get()` — must be near-zero cost):**
```java
public void recordRead(long keyHash) {
    long[] buffer = readEvents.get();
    int t = tail.get().get();
    buffer[t & BUFFER_MASK] = keyHash;
    // CAS to advance tail — if it fails, we just drop the event (acceptable for a cache)
    tail.get().compareAndSet(t, t + 1);
}
```

**Draining (called from `put()` — already holds write lock):**
```java
public void drainTo(Consumer<Long> processor) {
    long[] buffer = readEvents.get();
    int t = tail.get().getAndSet(0);  // atomically claim all events
    for (int i = 0; i < Math.min(t, BUFFER_SIZE); i++) {
        if (buffer[i] != 0) {
            processor.accept(buffer[i]);
            buffer[i] = 0;
        }
    }
}
```

**Why this works:**
- `get()` does one CAS — nanoseconds, no blocking
- Frequency updates are applied lazily during writes
- Occasional dropped events are fine — approximate counts are sufficient for eviction decisions

---

### 3D — `VarHandle` Replacement for `AtomicLong`

Replace `AtomicLong` stat counters with `VarHandle` on primitive `long` fields for lower overhead.

```
File: cache-core/src/main/java/com/hpcache/impl/TinyLFUCache.java
```

```java
// Declaration
private volatile long hitCount;
private volatile long missCount;

// VarHandle lookup (once, at class load time)
private static final VarHandle HIT_COUNT;
private static final VarHandle MISS_COUNT;
static {
    try {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        HIT_COUNT = lookup.findVarHandle(TinyLFUCache.class, "hitCount", long.class);
        MISS_COUNT = lookup.findVarHandle(TinyLFUCache.class, "missCount", long.class);
    } catch (Exception e) {
        throw new ExceptionInInitializerError(e);
    }
}

// Usage — no boxing, no object allocation
HIT_COUNT.getAndAdd(this, 1L);  // equivalent to hitCount.incrementAndGet()
```

**Memory ordering options (know these for interview):**
| Method | Ordering | Use When |
|---|---|---|
| `get()` / `set()` | volatile (full fence) | Visibility required |
| `getAcquire()` / `setRelease()` | acquire/release | One-way fence, faster |
| `getOpaque()` / `setOpaque()` | no ordering | Stats counters — approximate ok |
| `compareAndSet()` | volatile | CAS operations |

For hit/miss counters: use `getAndAddAcquire` — stats don't need full volatile ordering.

---

### 3E — False Sharing Prevention

False sharing occurs when two fields on different threads share a CPU cache line (64 bytes). Writing to one invalidates the cache line for all threads — causing invisible performance loss.

**Identify the problem:**
- `hitCount` and `missCount` are adjacent fields — likely on the same cache line
- 8 reader threads updating `hitCount` simultaneously invalidate each other's caches

**Fix with padding:**
```java
// Pad each counter to occupy its own 64-byte cache line
@jdk.internal.vm.annotation.Contended  // JVM annotation (requires --add-opens)
private volatile long hitCount;

@jdk.internal.vm.annotation.Contended
private volatile long missCount;
```

**Or manual padding (more portable):**
```java
// Pad with unused fields to push to next cache line
private volatile long hitCount;
private long p1, p2, p3, p4, p5, p6, p7;  // 7 * 8 = 56 bytes padding
private volatile long missCount;
private long q1, q2, q3, q4, q5, q6, q7;
```

**Benchmark:** Measure counter increment throughput with and without padding under 8 threads. Expected improvement: 2–5x on machines with > 4 cores.

---

### 3F — `OffHeapStore.java` (Optional Extension)

Store values off the Java heap in direct `ByteBuffer` memory. GC never scans or moves these objects.

```
File: cache-core/src/main/java/com/hpcache/offheap/OffHeapStore.java
```

**When to use:** When cached values are large (> 1KB) and GC pauses are a problem. Not worth it for small primitive values.

**Implementation sketch:**
```java
public class OffHeapStore<V> {
    private final ByteBuffer memory;  // allocateDirect — lives outside heap
    private final Serializer<V> serializer;
    private final Map<K, Long> index;  // key → offset in ByteBuffer (on-heap, small)
    
    public OffHeapStore(long capacityBytes, Serializer<V> serializer) {
        this.memory = ByteBuffer.allocateDirect((int) capacityBytes);
        this.serializer = serializer;
        this.index = new ConcurrentHashMap<>();
    }
    
    public void put(K key, V value) {
        byte[] bytes = serializer.serialize(value);
        long offset = allocate(bytes.length);
        memory.position((int) offset);
        memory.put(bytes);
        index.put(key, offset);
    }
    
    public V get(K key) {
        Long offset = index.get(key);
        if (offset == null) return null;
        // ... read bytes from memory at offset, deserialize
    }
}
```

**GC benchmark:** Run 10 minutes of sustained load against heap-stored cache and off-heap cache. Compare GC log pause times. Expected: off-heap shows near-zero GC pauses.

---

### Phase 3 Deliverables Checklist

- [ ] `CountMinSketch` implemented with 4-row, 4-bit packed counters
- [ ] `CountMinSketch` aging (reset) implemented and tested
- [ ] `CountMinSketch` frequency test: 1000 inserts → frequency within 10% of 1000
- [ ] `TinyLFUPolicy` with Window / Protected / Probationary segments
- [ ] `TinyLFUCache` uses sketch for eviction decisions
- [ ] Hit rate benchmark: TinyLFU beats LRU by >10% under Zipfian distribution
- [ ] `ReadBuffer` with per-thread ring buffer — verified no locks on read path
- [ ] Read buffer drains correctly during write operations
- [ ] `VarHandle` replacing `AtomicLong` on stat counters
- [ ] False sharing test: counter throughput measured with and without `@Contended`
- [ ] `OffHeapStore` (optional): GC pause comparison documented
- [ ] `results/phase-3.json` saved — comparison table vs Phase 1, Phase 2, and Caffeine complete

---

## Phase 4 — Market Data Integration

> **Duration:** 1 week
> **Goal:** Wire the simulator into the cache end-to-end. Demonstrate a realistic trading system component.

---

### 4A — `MarketDataFeedHandler.java`

Consumes events from the simulator and writes them to the cache.

```
File: cache-simulator/src/main/java/com/hpcache/sim/MarketDataFeedHandler.java
```

**Fields:**
- `Cache<String, MarketEvent> cache` — the cache to write into
- `MarketDataSimulator simulator` — event source
- `BurstScheduler scheduler` — controls tick rate
- `BlockingQueue<MarketEvent> eventQueue` — decouples producer from consumer
- `ExecutorService producer` — generates events
- `ExecutorService consumer` — writes events to cache

**Flow:**
```
MarketDataSimulator → BlockingQueue (bounded, capacity=10_000) → Cache
```

**Backpressure:** If queue is full (consumer too slow), drop oldest events and increment `droppedEventCount`.

**Metrics to track:**
- `eventsProduced` — total ticks generated
- `eventsConsumed` — total ticks written to cache
- `eventsDropped` — dropped due to queue full
- `queueDepth` — current queue size (sampled periodically)

---

### 4B — `PriceService.java`

Read layer on top of the cache. Simulates how a trading system would query prices.

```
File: cache-simulator/src/main/java/com/hpcache/sim/PriceService.java
```

**Methods:**
```java
Optional<Double> getMidPrice(String symbol);        // returns midPrice or empty if not cached
Optional<Double> getBidPrice(String symbol);
Optional<Double> getAskPrice(String symbol);
boolean isStale(String symbol, long maxAgeMs);       // true if cached price is too old
Map<String, Double> getAllMidPrices();               // snapshot of all cached prices
double getSpread(String symbol);                     // ask - bid
```

**Stale detection:**
```java
public boolean isStale(String symbol, long maxAgeMs) {
    MarketEvent event = cache.get(symbol);
    if (event == null) return true;
    long ageMs = (System.nanoTime() - event.timestampNanos()) / 1_000_000L;
    return ageMs > maxAgeMs;
}
```

---

### 4C — End-to-End Scenarios

Runnable demos that show the system behaving correctly under different market conditions.

```
File: cache-simulator/src/main/java/com/hpcache/sim/scenarios/
```

**Scenario 1 — Normal market (`NormalMarketScenario.java`):**
- 100 symbols, 10,000 ticks/sec, Zipfian distribution
- Run 60 seconds, print stats every 5 seconds
- Expected: hit rate > 85%, stale rate < 1%

**Scenario 2 — Market open burst (`MarketOpenScenario.java`):**
- Start at 100,000 ticks/sec for 10 seconds, drop to 5,000 ticks/sec
- Monitor queue depth — does backpressure work?
- Monitor p999 latency — does it spike at burst start?

**Scenario 3 — Flash crash (`FlashCrashScenario.java`):**
- One symbol gets 50x normal tick rate for 5 seconds
- Verify cache handles hot key without degrading other symbols
- Monitor per-symbol hit rates during and after crash

**Scenario 4 — Symbol churn (`SymbolChurnScenario.java`):**
- Add 500 new symbols mid-run
- Verify old hot symbols retain high hit rate
- Measure time for new symbols to "warm up" in cache

---

### Phase 4 Deliverables Checklist

- [ ] `MarketDataFeedHandler` runs producer/consumer pipeline with `BlockingQueue`
- [ ] Backpressure works: queue overflow drops events, increments `droppedEventCount`
- [ ] `PriceService` reads from cache, detects stale prices correctly
- [ ] All 4 scenarios run without exceptions for 60+ seconds
- [ ] Stats printed during runs show expected hit rates and latencies
- [ ] Market open burst scenario: queue depth stays bounded (backpressure working)

---

## Phase 5 — Full Benchmarking & Performance Analysis

> **Duration:** 1–2 weeks
> **Goal:** Rigorous, documented performance story. Phase 1→3 improvement graph with flame graphs explaining why.

---

### 5A — Full JMH Suite

Run all benchmarks against all cache implementations and save results.

**`run_benchmarks.sh`:**
```bash
#!/bin/bash
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RESULTS_DIR="results/$TIMESTAMP"
mkdir -p $RESULTS_DIR

# Run all benchmarks for each implementation
for IMPL in LRUCache StripedCache StampedCache TinyLFUCache Caffeine Guava; do
    java -jar benchmarks.jar \
        -p cacheImpl=$IMPL \
        -rf json \
        -rff "$RESULTS_DIR/$IMPL.json" \
        -wi 5 -i 10 -f 2 \
        2>&1 | tee "$RESULTS_DIR/$IMPL.log"
done

echo "Results saved to $RESULTS_DIR"
```

---

### 5B — `ComparisonBenchmark.java`

Single benchmark class that runs the same workload against all implementations for a side-by-side table.

```java
@Param({"LRUCache", "StripedCache", "StampedCache", "TinyLFUCache", "Caffeine", "Guava"})
private String cacheImpl;

@Setup
public void setup() {
    cache = CacheFactory.create(cacheImpl, 10_000, 30_000);
}
```

**Target comparison table (fill in with your real numbers):**

| Metric | LRUCache | StripedCache | StampedCache | TinyLFUCache | Caffeine | Guava |
|---|---|---|---|---|---|---|
| Throughput (8T, read-heavy) | TBD | TBD | TBD | TBD | ~50M/s | ~5M/s |
| p50 latency | TBD | TBD | TBD | TBD | ~50ns | ~500ns |
| p99 latency | TBD | TBD | TBD | TBD | ~200ns | ~2µs |
| p999 latency | TBD | TBD | TBD | TBD | ~1µs | ~10µs |
| Hit rate (Zipfian) | TBD | TBD | TBD | TBD | ~90% | ~70% |
| GC pauses (G1, 10min) | TBD | TBD | TBD | TBD | minimal | minimal |

---

### 5C — GC Analysis

**Run with GC logging enabled:**
```bash
java -Xms2g -Xmx2g \
     -Xlog:gc*:file=gc-g1.log:time,uptime,level,tags \
     -XX:+UseG1GC \
     -jar benchmarks.jar ReadHeavyBenchmark
```

**Repeat with ZGC:**
```bash
java -Xms2g -Xmx2g \
     -Xlog:gc*:file=gc-zgc.log:time,uptime,level,tags \
     -XX:+UseZGC \
     -jar benchmarks.jar ReadHeavyBenchmark
```

**`parse_gc_log.py`:** Parse GC logs and extract:
- Total GC pause time
- Max single pause duration
- GC pause frequency (pauses/second)
- GC overhead (% of wall time spent in GC)

**Expected findings:**
- G1GC: 50–200ms pauses, low frequency
- ZGC: < 1ms pauses, higher frequency, slight throughput overhead
- Off-heap cache: near-zero GC pauses regardless of collector

---

### 5D — Flame Graph Generation

**`generate_flamegraph.sh`:**
```bash
#!/bin/bash
IMPL=$1  # e.g. "LRUCache"

# Start benchmark with async-profiler attached
java -agentpath:$ASYNC_PROFILER_HOME/build/libasyncProfiler.so=start,event=cpu,file=profiles/$IMPL.jfr \
     -jar benchmarks.jar ReadHeavyBenchmark -p cacheImpl=$IMPL

# Convert to flame graph
$ASYNC_PROFILER_HOME/bin/jfr2flame profiles/$IMPL.jfr > flamegraphs/$IMPL.html
```

**What to look for in Phase 1 flame graph:**
- `ReentrantReadWriteLock.lock()` / `unlock()` should dominate — this is the bottleneck
- Large fraction of CPU in `AbstractQueuedSynchronizer` (lock contention queue)

**What to look for in Phase 3 flame graph:**
- Lock methods should be much smaller
- `CountMinSketch.frequency()` may appear — this is expected
- `LinkedHashMap` internal methods prominent — this is the remaining bottleneck

---

### 5E — Throughput Scaling Chart

Plot throughput vs thread count for each implementation. This tells the concurrency story visually.

**Data to collect:**

| Threads | LRUCache | StripedCache | TinyLFUCache | Caffeine |
|---|---|---|---|---|
| 1 | TBD | TBD | TBD | TBD |
| 2 | TBD | TBD | TBD | TBD |
| 4 | TBD | TBD | TBD | TBD |
| 8 | TBD | TBD | TBD | TBD |
| 16 | TBD | TBD | TBD | TBD |

**LRUCache expected:** Flattens or drops after 2 threads (single lock)
**StripedCache expected:** Near-linear up to stripe count, then flattens
**TinyLFUCache expected:** Better scaling than StripedCache due to lock-free reads
**Caffeine expected:** Near-linear up to ~16 threads

---

### Phase 5 Deliverables Checklist

- [ ] Full JMH suite runs against all implementations without errors
- [ ] `results/` directory populated with JSON results for all phases
- [ ] `compare_phases.sh` produces readable diff between any two result files
- [ ] Comparison table completed with real numbers
- [ ] GC analysis run with G1GC and ZGC — pause stats documented
- [ ] Flame graphs generated for Phase 1 and Phase 3 — bottlenecks annotated
- [ ] Throughput vs thread count data collected and charted
- [ ] Off-heap GC benchmark (if implemented): pause times documented

---

## Phase 6 — Polish & Portfolio

> **Duration:** 3–5 days
> **Goal:** Make the project presentable to an interviewer or on GitHub.

---

### 6A — API Cleanup

- [ ] Javadoc on every public method in `Cache.java`, `CacheBuilder.java`, `CacheStats.java`
- [ ] Package-private internal classes — only the public API is exposed
- [ ] `Cache` implements `Closeable` — works in try-with-resources
- [ ] All exceptions have meaningful messages that include the key and context
- [ ] Remove any TODO comments, dead code, debug print statements

---

### 6B — README.md Structure

```markdown
# high-performance-cache

> A Caffeine-inspired local cache in Java, built to learn concurrency and
> high-performance systems. Achieves Xns p99 latency at Y threads.

## Architecture
[Diagram of cache layers, read/write paths, buffer drain]

## Performance Results
[Your actual benchmark table]

## What I Learned
[3–4 paragraphs: concurrency progression, GC insights, what surprised you]

## Design Decisions
[Why StampedLock over ReadWriteLock, why TinyLFU over LRU, etc.]

## How to Run
[Maven build, run benchmarks, run simulator]

## Phase-by-Phase Improvement
[Graph or table showing throughput per phase]
```

---

### 6C — Resume Bullet (fill in your real numbers)

```
Built a high-performance in-memory cache in Java achieving [X]M ops/sec at p99 [Y]ns
under Zipfian access patterns with [N] threads, implementing TinyLFU eviction via
Count-Min Sketch, lock-free read buffering, and StampedLock optimistic reads.
Benchmarked with JMH and profiled with async-profiler across G1, ZGC, and Shenandoah;
documented [Z]x throughput improvement from Phase 1 (ReadWriteLock) to Phase 3
(lock-free).
```

---

## Timeline

| Week | Phase | Focus | Deliverable |
|---|---|---|---|
| 1 | 0 | Simulator, JMH, HdrHistogram setup | Benchmarks running, baseline saved |
| 2–3 | 1A–1C | LRU + TTL + CacheEntry | Thread-safe cache, expiry working |
| 4 | 1D–1G | Stats, builder, concurrency tests | Clean API, all tests passing |
| 5–6 | 2A–2B | Striping + StampedLock | Measurable throughput improvement |
| 7–8 | 2C–2D | Thundering herd + async loading | LoadingCache, Phase 2 benchmarks |
| 9–10 | 3A–3B | CountMinSketch + TinyLFU | Better hit rate under Zipfian |
| 11–12 | 3C–3E | Lock-free buffer + VarHandle + padding | Phase 3 benchmarks, Caffeine comparison |
| 13 | 4 | Market data integration | End-to-end demo, all 4 scenarios |
| 14 | 5 | Full benchmark suite + GC + flame graphs | Complete performance story |
| 15 | 6 | Polish + README + resume | GitHub-ready, interview-ready |

---

## Benchmark Targets by Phase

### Throughput (ops/sec, read-heavy 95/5, 8 threads)

| Phase | Expected | Notes |
|---|---|---|
| Phase 0 baseline (HashMap stub) | 80–100M | Uncontended, no eviction |
| Phase 1 (LRUCache, ReadWriteLock) | 3–5M | Single lock bottleneck |
| Phase 2 (StripedCache) | 15–25M | Linear scaling up to stripe count |
| Phase 2 (StampedCache) | 20–30M | Optimistic reads win under low write rate |
| Phase 3 (TinyLFUCache) | 35–50M | Lock-free reads, amortised bookkeeping |
| Caffeine (reference) | 50–80M | Years of optimisation |

### Latency (p99, read-heavy, 8 threads)

| Phase | Expected p99 |
|---|---|
| Phase 1 | 2–10µs |
| Phase 2 | 500ns–2µs |
| Phase 3 | 100–500ns |
| Caffeine | 100–300ns |

### Hit Rate (Zipfian distribution, 10,000 entry cache, 100 symbols)

| Eviction Policy | Expected Hit Rate |
|---|---|
| LRU | 65–75% |
| LFU (exact) | 75–85% |
| TinyLFU (approximate) | 80–90% |
| Caffeine W-TinyLFU | 85–92% |

---

## Key Concepts Reference

### Java Memory Model — Know Cold for Interview

| Concept | What It Means | Where Used in This Project |
|---|---|---|
| happens-before | If A hb B, B sees all writes A made | Lock release→acquire, volatile write→read |
| volatile | All threads see the latest write immediately | Cache state flags, counter fields |
| synchronized | Mutual exclusion + happens-before | Phase 1 basics |
| AtomicLong | Lock-free increment via hardware CAS | Hit/miss counters Phase 1 |
| VarHandle | Low-level atomic field access | Phase 3 counter replacement |
| acquire/release | One-way memory fence (weaker than volatile) | Phase 3 counter ordering |

### Lock Type Decision Guide

| Lock | Use When | Avoid When |
|---|---|---|
| `synchronized` | Simple, low-contention, < 5µs critical section | High-throughput hot paths |
| `ReentrantLock` | Need tryLock, timed, or interruptible | Simple cases (overhead not worth it) |
| `ReentrantReadWriteLock` | Many readers, rare writers | Write rate > 20% (reader starvation risk) |
| `StampedLock` | Read-heavy with optimistic validation safe | Need reentrancy — StampedLock is NOT reentrant |
| `VarHandle CAS` | Single-field state transitions | Multi-field atomicity (use lock instead) |
| Lock-free ring buffer | Recording events on hot path | Complex state requiring multiple field updates |

### GC Algorithm Reference

| GC | Max Pause | Throughput | Use For |
|---|---|---|---|
| G1GC (default) | 10–200ms | High | General purpose, heap < 32GB |
| ZGC | < 1ms | Slightly lower | Low-latency, any heap size |
| Shenandoah | < 1ms | Slightly lower | Low-latency, smaller heaps |
| Off-heap (manual) | ~0ms | Highest | Extreme latency requirements, accept complexity |

### Common Interview Questions This Project Answers

1. **"Explain a race condition you fixed."** → The `get()` write-lock bug in Phase 1
2. **"What is the thundering herd problem?"** → Phase 2C LoadingCache
3. **"How would you reduce lock contention?"** → Lock striping (Phase 2A), StampedLock (2B), lock-free reads (Phase 3C)
4. **"What is false sharing?"** → Phase 3E counter padding
5. **"How do you measure Java performance correctly?"** → JMH, HdrHistogram, async-profiler (Phase 0)
6. **"Explain the Java Memory Model."** → VarHandle memory ordering (Phase 3D)
7. **"When would you use off-heap memory?"** → Phase 3F OffHeapStore, GC pressure

---

## Coding Agent Instructions

> This section is written for AI coding assistants. If you are helping implement this project,
> read this before writing any code.

### Project Context

This is a Java high-performance cache project built for learning purposes. The owner is learning
Java concurrency from beginner to advanced through incremental implementation. **Correctness
comes before performance** in early phases — do not introduce advanced concurrency patterns
before their designated phase.

Read the **Design Decisions** section at the top of this file before writing any code.
The key rules are:
- This is a **library**, not an application. Do not add Spring, Quarkus, or any web framework.
- `cache-core` has **zero framework dependencies** — only the JVM standard library and SLF4J.
- Caffeine and Guava only appear in `cache-benchmark`. Never add them to `cache-core`.
- `cache-demo` is a thin `main()` only — it should never exceed ~50 lines.
- Internal implementation classes go in `com.hpcache.internal` — they are not public API.

### Build Environment

- Java 17+
- Maven multi-module project (parent POM at root)
- Module structure: `cache-core`, `cache-simulator`, `cache-benchmark`, `cache-demo`
- Library source: `cache-core/src/main/java/com/hpcache/`
- Library tests: `cache-core/src/test/java/com/hpcache/`
- Simulator source: `cache-simulator/src/main/java/com/hpcache/sim/`
- Benchmark source: `cache-benchmark/src/main/java/com/hpcache/bench/`
- Demo source: `cache-demo/src/main/java/com/hpcache/demo/`

### Module Dependency Rules (Enforced)

```
cache-core        → no dependencies on other modules
cache-simulator   → may depend on cache-core only
cache-benchmark   → may depend on cache-core and cache-simulator
cache-demo        → may depend on cache-core and cache-simulator
```

Never create a circular dependency. Never let `cache-core` depend on `cache-simulator`.

### Coding Standards

- Use Java `record` for immutable data (MarketEvent, CacheStats, CacheEntry)
- Use `final` on all fields that don't change after construction
- All public methods must have Javadoc explaining **why**, not just **what**
- No raw types — always use generics (`Cache<K,V>` not `Cache`)
- Thread safety must be documented on every class: `@ThreadSafe` or `@NotThreadSafe`
- Every lock acquisition must have a corresponding `finally { lock.unlock(); }` block

### Implementation Order

Implement phases strictly in order: 0 → 1 → 2 → 3 → 4 → 5 → 6.
Do not skip ahead. Phase N builds on Phase N-1.

### Critical Correctness Rules

1. **Phase 1 `get()` must use `writeLock`** — `LinkedHashMap` with `accessOrder=true`
   mutates internal node order on every `get()`. This is a write operation. Using `readLock`
   here causes data corruption under concurrent access.

2. **TTL must use `System.nanoTime()`** — not `System.currentTimeMillis()`.
   `nanoTime` is monotonic and unaffected by system clock changes.

3. **Background cleaner thread must be a daemon thread** — set `thread.setDaemon(true)`.
   Non-daemon threads prevent JVM shutdown.

4. **`StampedLock` is not reentrant** — never call a method that acquires a `StampedLock`
   from within a section that already holds it. This deadlocks silently.

5. **`CountMinSketch` counters must saturate at 15, not overflow** — 4-bit counters packed
   into longs. Overflow into the next nibble corrupts adjacent counters.

6. **Ring buffer size must be a power of two** — enables `index & (size-1)` instead of
   `index % size`. Do not change this without updating the mask.

### When Asked to Implement a Specific Class

1. Read this entire plan file first — the class may have dependencies or interactions
   described in adjacent sections.
2. Implement exactly the fields and methods specified — do not add extra abstraction layers.
3. Write the concurrency tests described in the checklist section for that phase.
4. Run the JMH benchmark after implementation and report the throughput number.
5. If a design decision here conflicts with a better approach you know, flag it with a
   comment `// NOTE: alternative approach is X — kept as specified for learning purposes`

### Common Mistakes to Avoid

- Do not use `synchronized(this)` in Phase 2+ — defeats the purpose of learning explicit locks
- Do not use `Collections.synchronizedMap()` — too coarse, doesn't help with LRU ordering
- Do not use `Executors.newFixedThreadPool()` for the cache cleaner — use `newSingleThreadScheduledExecutor`
- Do not allocate objects on the hot path in Phase 3 — allocation causes GC pressure
- Do not use `Optional<V>` as a return type from `Cache.get()` — performance-critical code avoids Optional overhead
- Do not call `System.currentTimeMillis()` for TTL — use `System.nanoTime()`

### If You Get Stuck

1. Check the `Key Concepts Reference` section — it covers the theory behind each phase
2. Read the Caffeine source code: https://github.com/ben-manes/caffeine — the implementation
   here is a simplified version of Caffeine's design
3. For JMH questions: https://github.com/openjdk/jmh/tree/master/jmh-samples
4. For VarHandle: see `java.lang.invoke.VarHandle` Javadoc — the `getAndAdd` and
   `compareAndSet` methods are most relevant
