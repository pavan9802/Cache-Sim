# Architecture — Design Decisions & Project Structure

This document is the canonical reference for how this project is structured and why.
It supersedes any conflicting information in `HighPerformanceCache_Plan.md`.

---

## What This Project Is

A Java cache **library** — infrastructure consumed by other JVM applications, not a runnable
service. The mental model is Caffeine or Guava Cache: something you add to a `pom.xml` and use
in your code. `cache-core` is the deliverable. Everything else is tooling.

---

## Module Structure

```
high-performance-cache/          ← Git root, parent POM
├── cache-core/                  ← THE LIBRARY (the only deliverable)
├── cache-simulator/             ← Market data simulation (used by demo + benchmark)
├── cache-benchmark/             ← JMH benchmarks (never shipped)
├── cache-stress/                ← jcstress concurrency stress tests (never shipped)
├── cache-demo/                  ← Thin main() demo (~50 lines, not a deliverable)
├── docs/                        ← Spec documents (this folder)
├── results/                     ← JMH output JSON files (gitignored)
└── scripts/                     ← Benchmark runner, GC parser, flame graph generator
```

### Dependency Graph (enforced — no exceptions)

```
cache-core          ← no dependencies on other modules
    ↑
cache-simulator     ← depends on cache-core only
    ↑
cache-benchmark     ← depends on cache-core + cache-simulator
cache-stress        ← depends on cache-core only
cache-demo          ← depends on cache-core + cache-simulator
```

Never create a cycle. Never let `cache-core` depend on `cache-simulator`.

### Module Responsibilities

| Module | Responsibility | Ships? | Framework deps? |
|---|---|---|---|
| `cache-core` | The cache library — all implementations, interfaces, builder | Yes | None |
| `cache-simulator` | Market data generation for demo and benchmarks | No | None |
| `cache-benchmark` | JMH benchmark suite — measures and compares | No | JMH only |
| `cache-stress` | jcstress memory-model correctness tests | No | jcstress only |
| `cache-demo` | `main()` that demonstrates the library end-to-end | No | None |

### What Goes Where

| Code | Module |
|---|---|
| `Cache<K,V>` interface, `CacheStats`, `RemovalListener` | `cache-core/api` |
| `LRUCache`, `StripedCache`, `StampedCache`, `TinyLFUCache` | `cache-core/impl` |
| `CacheBuilder` | `cache-core/builder` |
| `CacheEntry`, `CountMinSketch`, `ReadBuffer`, eviction policies | `cache-core/internal` |
| `MarketEvent`, `MarketDataSimulator`, `ZipfianSymbolSelector` | `cache-simulator` |
| JMH `@Benchmark` methods, Caffeine/Guava deps | `cache-benchmark` |
| jcstress `@JCStressTest` classes | `cache-stress` |
| `public static void main()` | `cache-demo` |
| Benchmark runner scripts, GC log parser | `scripts/` |

---

## Canonical Package Layout

This layout is the law. The plan (`HighPerformanceCache_Plan.md`) has inconsistencies;
this document resolves them.

```
cache-core/src/main/java/com/hpcache/
  api/
    Cache.java                   # Core cache interface
    LoadingCache.java            # Cache + loader function
    AsyncLoadingCache.java       # Non-blocking loader variant
    CacheStats.java              # Immutable stats snapshot (record)
    RemovalListener.java         # Eviction callback (@FunctionalInterface)
    RemovalCause.java            # Enum: SIZE, EXPIRED, EXPLICIT
    StatsReporter.java           # Periodic console stats printer

  builder/
    CacheBuilder.java            # Fluent builder — only construction path

  impl/
    LRUCache.java                # Phase 1: LinkedHashMap + ReentrantReadWriteLock
    StripedCache.java            # Phase 2A: lock striping
    StampedCache.java            # Phase 2B: StampedLock + optimistic reads
    TinyLFUCache.java            # Phase 3: frequency-based eviction

  internal/                      # package-private — NOT public API
    entry/
      CacheEntry.java            # Value + expiry wrapper (record)
    eviction/
      EvictionPolicy.java        # Interface
      LRUPolicy.java
      TinyLFUPolicy.java
    sketch/
      CountMinSketch.java        # Phase 3: frequency estimation
    buffer/
      ReadBuffer.java            # Phase 3: lock-free read recording
      WriteBuffer.java           # Phase 3: batched write ops

  offheap/
    OffHeapStore.java            # Phase 3F (optional): direct ByteBuffer storage

cache-simulator/src/main/java/com/hpcache/sim/
  MarketEvent.java
  MarketDataSimulator.java
  ZipfianSymbolSelector.java
  BurstScheduler.java
  MarketDataFeedHandler.java
  PriceService.java
  scenarios/
    NormalMarketScenario.java
    MarketOpenScenario.java
    FlashCrashScenario.java
    SymbolChurnScenario.java

cache-benchmark/src/main/java/com/hpcache/bench/
  BenchmarkBase.java
  ReadHeavyBenchmark.java
  WriteHeavyBenchmark.java
  ZipfianBenchmark.java
  ThreadScalingBenchmark.java
  EvictionBenchmark.java
  TTLBenchmark.java
  ComparisonBenchmark.java

cache-stress/src/main/java/com/hpcache/stress/
  WriteLockStressTest.java       # Phase 1: accessOrder writeLock correctness
  ThunderingHerdStressTest.java  # Phase 2C: loader called exactly once
  StampedLockStressTest.java     # Phase 2B: optimistic read correctness
  CounterAtomicityStressTest.java # Phase 3D: VarHandle counter correctness

cache-demo/src/main/java/com/hpcache/demo/
  CacheDemo.java                 # main() only, ~50 lines
```

---

## Design Decisions

### Decision 1: Library, Not an Application

This is a Maven library artifact — not a Spring Boot service, not a CLI tool. Adding Spring
signals you reached for a framework out of habit. A cache needs nothing but the JVM.

On a resume: *"A high-performance local cache library for JVM applications, benchmarked against
Caffeine"* — not *"a cache application."*

### Decision 2: Multi-Module Maven

Four (now five) modules enforce explicit boundaries:
- JMH and Caffeine never leak into `cache-core`'s classpath
- jcstress never leaks into production code
- Module structure is what a senior engineer would expect from a real library

### Decision 3: Zero Framework Dependencies in `cache-core`

`cache-core` depends only on:
- Java 21 standard library
- SLF4J (logging facade only — no implementation; the consumer provides it)
- JUnit 5 (test scope only)

Framework dependencies in a cache library are a design smell. They kill portability and add
latency overhead.

### Decision 4: `cache-demo` Uses `main()` Only

The demo exists to prove the library works and give something runnable for an interview.
It should never exceed ~50 lines. It is not the point of the project.

### Decision 5: Maven over Gradle

Maven is more verbose but more explicit — you always know what is happening and why.
For a learning project where understanding the build is part of the education, Maven's
verbosity is a feature.

### Decision 6: `cache-simulator` Is a Separate Module, Not Test Code

The simulator is used by both `cache-demo` (runtime) and `cache-benchmark`. It is not
test-only infrastructure. Separating it makes the dependency graph explicit.

### Decision 7: `cache-stress` for jcstress (Not JUnit `@RepeatedTest`)

**How to build and run jcstress tests:**
```bash
# Build the stress test JAR (includes cache-core as a dependency)
mvn package -pl cache-stress -am -q

# Run all stress tests (takes several minutes — jcstress runs millions of iterations)
java -jar cache-stress/target/cache-stress.jar

# Run a specific test by class name pattern
java -jar cache-stress/target/cache-stress.jar -t WriteLockStressTest

# Run with more forks for higher confidence (default is usually 1)
java -jar cache-stress/target/cache-stress.jar -t WriteLockStressTest -f 5
```

jcstress generates an HTML report in `jcstress-results/` (gitignored). Open
`jcstress-results/index.html` in a browser to see the full results with outcome tables.
A passing run shows all outcomes as `ACCEPTABLE`. Any `FORBIDDEN` outcome = failed gate.



jcstress is OpenJDK's dedicated concurrency stress testing tool. It runs millions of iterations
under adversarial scheduling and uses the Java Memory Model to detect theoretically forbidden
outcomes. It cannot live inside a JUnit test class — it needs its own runner and module.

Use `@RepeatedTest(50–100)` in JUnit for simple thread-safety checks.
Use jcstress for memory-model assertions (write-lock correctness, thundering herd, VarHandle
ordering).

---

## Critical Correctness Rules

These are non-negotiable. Each one represents a bug category that silently corrupts data or
deadlocks — not an exception that will tell you something went wrong.

| Rule | Why |
|---|---|
| `LRUCache.get()` must use `writeLock` | `LinkedHashMap(accessOrder=true)` mutates node order on every `get()` — this is a write |
| TTL must use `System.nanoTime()` | `currentTimeMillis()` is not monotonic — clock adjustments cause incorrect expiry |
| Background threads must be daemon (`setDaemon(true)`) | Non-daemon threads prevent JVM shutdown |
| `StampedLock` is not reentrant | Calling a lock-acquiring method from within a locked section deadlocks silently |
| CountMinSketch counters saturate at 15 | 4-bit counters packed into longs — overflow into adjacent nibble corrupts unrelated counters |
| Ring buffer size must be a power of two | Required for `index & (size-1)` bitwise-AND indexing |
| `@Contended` requires `--add-opens` | Without `--add-opens java.base/jdk.internal.vm.annotation=ALL-UNNAMED`, runtime crashes |

---

## JVM Flags Reference

## Java 21 Upgrades

Java 21 (LTS) introduces two features used directly in this project:

**Virtual threads (`Thread.ofVirtual()`)** — background threads (cache cleaners, async loaders,
feed handler workers) should use virtual threads instead of manually configured daemon platform
threads. Virtual threads are daemon threads by default and eliminate thread-pool sizing decisions.
Use `Thread.ofVirtual().name("thread-name").factory()` wherever a `ThreadFactory` is needed.
For fire-and-forget async tasks, use `Executors.newVirtualThreadPerTaskExecutor()`.

**Sequenced Collections (`SequencedMap`)** — `LinkedHashMap` now implements `SequencedMap`
(Java 21). Use `map.firstEntry()` and `map.lastEntry()` instead of iterator workarounds when
you need the eldest or newest entry. This applies to `LRUCache` and `TinyLFUPolicy`.

---

## JVM Flags Reference

These flags are needed and must be configured in Maven Surefire (`cache-core/pom.xml`) and
`BenchmarkBase` `@Fork` args (`cache-benchmark`):

```
--add-opens java.base/jdk.internal.vm.annotation=ALL-UNNAMED   (for @Contended, Phase 3E)
-XX:-RestrictContended                                           (to enable @Contended padding)
```

---

## Benchmark Result Naming Convention

```
results/
  phase-0-baseline-<YYYYMMDD_HHMMSS>.json
  phase-1-<YYYYMMDD_HHMMSS>.json
  phase-2a-striped-<YYYYMMDD_HHMMSS>.json
  phase-2b-stamped-<YYYYMMDD_HHMMSS>.json
  ...
```

`results/*.json` is gitignored (machine-specific numbers). The `results/` directory itself
is tracked via `.gitkeep`.

---

## Implementation Order

Strictly: Pre-step → 0 → 1 → 2A → 2B → 2C → 3A → 3B → 3C → 4 → 5 → 6.

Each phase must pass its exit gate (`/phase-exit-check <N>`) before the next begins.
See each phase's spec doc in `docs/` for the complete exit gate criteria.
