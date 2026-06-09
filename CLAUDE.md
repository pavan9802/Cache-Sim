# high-performance-cache — Project Rules

Read `HighPerformanceCache_Plan.md` for full implementation detail and context.
Read `docs/` for per-phase spec documents before implementing any phase.

## What This Project Is

A Java cache **library** — not an application. No Spring, no Quarkus, no web framework.
`cache-core` is the deliverable. Everything else is tooling that supports it.

---

## Module Dependency Rules (Non-Negotiable)

```
cache-core        → zero dependencies on other modules; no frameworks; no Caffeine/Guava
cache-simulator   → may depend on cache-core only
cache-benchmark   → may depend on cache-core and cache-simulator
cache-demo        → may depend on cache-core and cache-simulator
```

Never create a circular dependency. Never let `cache-core` depend on `cache-simulator`.
Caffeine and Guava only ever appear in `cache-benchmark`.

---

## Critical Correctness Rules

These are bugs that will silently corrupt data or deadlock. Know them before touching the code.

1. **`LRUCache.get()` must use `writeLock`** — `LinkedHashMap` with `accessOrder=true` mutates
   internal node order on every `get()`. This is a write. Using `readLock` here causes data
   corruption under concurrent access.

2. **TTL must use `System.nanoTime()`** — never `System.currentTimeMillis()`. `nanoTime` is
   monotonic and unaffected by system clock adjustments.

3. **Background threads must be daemon threads** — `thread.setDaemon(true)`. Non-daemon threads
   prevent JVM shutdown.

4. **`StampedLock` is not reentrant** — never call a method that acquires a `StampedLock` from
   within a section that already holds it. This deadlocks silently.

5. **`CountMinSketch` 4-bit counters must saturate at 15** — do not overflow into the adjacent
   nibble. Increment must check `if (current < 15)` before adding. Reset mask must be
   `0x7777777777777777L`.

6. **Ring buffer size must be a power of two** — required for `index & (size - 1)` instead of
   `index % size`. Do not change this without updating the mask.

---

## Phase Exit Gate Framework

Every phase must pass ALL of the following before the next phase begins.
Use `/phase-exit-check <N>` to run this automatically.

### Correctness Gate
- [ ] All unit tests green, zero failures across **50 `@RepeatedTest` runs** (100 for critical concurrency paths)
- [ ] jcstress tests exist and pass for any memory-model-sensitive code
- [ ] No test flakiness: full suite runs 3 times with identical results
- [ ] One failure in N runs = failed gate — investigate before proceeding

### Performance Gate
- [ ] Baseline recorded to `results/phase-N-<timestamp>.json`
- [ ] No regression vs previous phase on `ReadHeavyBenchmark` (same workload, same thread count)
- [ ] Phase improvement floor met (see each phase spec doc for the target ratio)

### Code Quality Gate
- [ ] `/concurrency-review` reports zero findings on phase files
- [ ] `/abstraction-check` reports zero MUST FIX findings
- [ ] `/java-best-practices` reports zero MUST FIX findings

---

## Concurrency Testing Standards

| Test type | Minimum repetitions |
|---|---|
| Thread-safety (no data corruption) | `@RepeatedTest(50)` |
| Critical paths (write-lock, thundering herd) | `@RepeatedTest(100)` |
| Deadlock tests | `@RepeatedTest(20)` + timeout assertion |
| Memory model assertions | jcstress — zero forbidden outcomes |

**Rule:** A single failure in any run is a failed gate. Race conditions are non-deterministic —
one failure means the bug exists even if it doesn't reproduce every time.

---

## Benchmark Rules

Use **relative gates**, not absolute numbers. Absolute throughput is machine-dependent.

| Gate type | Rule |
|---|---|
| Regression | Phase N must not be slower than Phase N-1 on any shared workload |
| Improvement floor | Must achieve at least 50% of the stated expected gain |
| Hit rate sanity | LRU/Zipfian: 65–75%. TinyLFU/Zipfian: 80–90%. Outside range = investigate |

If Phase N is slower than Phase N-1 on the same workload — stop. Do not proceed. This is a bug.

---

## Coding Standards

- Java `record` for immutable data (`MarketEvent`, `CacheStats`, `CacheEntry`)
- `final` on all fields that do not change after construction
- No `Optional<V>` returned from `Cache.get()` — `null` is correct for a cache miss; Optional adds object allocation overhead on the hot path
- No `synchronized(this)` in Phase 2+ — defeats the purpose of learning explicit locks
- No `Collections.synchronizedMap()` — too coarse, incompatible with LRU ordering mutation
- `ThreadLocalRandom`, not `new Random()`, in any concurrent context
- Thread safety documented on every class: `@ThreadSafe` or `@NotThreadSafe`
- Every `lock.lock()` has a corresponding `finally { lock.unlock(); }` block
- No `printStackTrace()` — use SLF4J
- **Java 21 — virtual threads for background/async work:** Use `Thread.ofVirtual().name("...").factory()` wherever a `ThreadFactory` is needed (scheduled executors, background cleaners). Use `Executors.newVirtualThreadPerTaskExecutor()` for fire-and-forget async tasks. Virtual threads are daemon threads by default — no `setDaemon(true)` required.
- **Java 21 — `SequencedMap`:** `LinkedHashMap` implements `SequencedMap`. Use `map.firstEntry()` / `map.lastEntry()` instead of iterator workarounds when accessing eldest or newest entries.

---

## Implementation Order

Phases must be implemented **strictly in order**: 0 → 1 → 2 → 3 → 4 → 5 → 6.
Each phase must pass its exit gate before the next begins.
Do not skip ahead. Phase N builds on Phase N-1.

---

## Available Slash Commands

| Command | Purpose |
|---|---|
| `/concurrency-review` | Scan for race conditions, lock misuse, memory model bugs |
| `/phase-exit-check <N>` | Run full exit gate check before marking a phase done |
| `/abstraction-check` | Module boundary violations, hot-path allocations, over/under-abstraction |
| `/benchmark-check [N] [M]` | Compare phase results, detect regressions, verify improvement floors |
| `/java-best-practices` | Modern Java 21+ usage, VarHandle, records, virtual threads, daemon threads |
