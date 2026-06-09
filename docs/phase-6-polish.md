# Phase 6 — Polish & Portfolio

## Context & Purpose

Make the project presentable for a GitHub portfolio and interview demonstration. This phase
does not add new features — it improves the quality of what already exists: Javadoc on public
API, removing internal artifacts that should never have been public, ensuring the demo works
cleanly end-to-end, and writing a README that tells the performance story.

The output of this phase is a project you can walk an interviewer through in 10 minutes.

---

## Pre-conditions

- All phases 0–5 exit gates fully passed
- Comparison table filled with real benchmark numbers
- Flame graphs and GC analysis complete

---

## Files to Modify

```
cache-core/src/main/java/com/hpcache/api/Cache.java            ← full Javadoc
cache-core/src/main/java/com/hpcache/api/CacheStats.java       ← full Javadoc
cache-core/src/main/java/com/hpcache/api/LoadingCache.java     ← full Javadoc
cache-core/src/main/java/com/hpcache/api/AsyncLoadingCache.java ← full Javadoc
cache-core/src/main/java/com/hpcache/builder/CacheBuilder.java ← full Javadoc
```

**Remove:**
```
cache-core/src/main/java/com/hpcache/impl/ConcurrentHashMapCache.java  ← delete (Phase 0 stub)
```

**Create:**
```
README.md
```

---

## Implementation Sequence

1. **API cleanup** — ensure all internal types are truly package-private
   → Run `/abstraction-check` on `cache-core`, fix all MUST FIX findings

2. **Add `Closeable` to `Cache<K,V>`** — enables try-with-resources
   → Update all implementations to extend `Closeable`
   → Unit test: `try (Cache<K,V> cache = ...) { }` compiles and `close()` is called

3. **Delete `ConcurrentHashMapCache`** — it's a Phase 0 artifact, not part of the library
   → Update benchmarks to remove it from `@Param` list
   → Confirm the stub is not referenced anywhere outside benchmark code

4. **Javadoc all public API** — `Cache.java`, `CacheStats.java`, `CacheBuilder.java`,
   `LoadingCache.java`, `AsyncLoadingCache.java`, `RemovalListener.java`
   → Run `mvn javadoc:javadoc`, zero errors

5. **Exception messages** — every thrown exception must include the key/value and context
   → Review all `IllegalStateException`, `CacheLoadException` throws

6. **Remove dead code** — TODO comments, debug print statements, unused fields
   → Run `/java-best-practices` on all of `cache-core`

7. **Write `README.md`** — see template below

8. **Final full test suite run** across all modules

---

## API Cleanup Checklist

- [ ] `Cache<K,V>` implements `Closeable` (for try-with-resources)
- [ ] All classes in `com.hpcache.internal.*` are package-private (no `public` modifier)
- [ ] `CacheEntry` is not visible in any public method signature
- [ ] `EvictionPolicy`, `CountMinSketch`, `ReadBuffer` are not public
- [ ] `ConcurrentHashMapCache` deleted
- [ ] No `System.out.println()` in any production class (all logging via SLF4J)
- [ ] No TODO comments remaining
- [ ] All public methods in `api/` and `builder/` have complete Javadoc

---

## Javadoc Standards

Javadoc must explain the **contract** (thread safety, null behavior, idempotency) — not just
restate the method name.

**Example of bad Javadoc:**
```java
/** Gets a value from the cache. */
V get(K key);
```

**Example of good Javadoc:**
```java
/**
 * Returns the cached value for {@code key}, or {@code null} if the key is not present
 * or its entry has expired.
 *
 * <p>This method is thread-safe. Multiple threads may call {@code get} concurrently.
 *
 * <p>This method returns {@code null}, not {@code Optional}, to minimize allocation
 * overhead on the hot path. Callers should check for {@code null} explicitly.
 *
 * @param key the key to look up; must not be {@code null}
 * @return the cached value, or {@code null} on miss or expiry
 */
V get(K key);
```

---

## README Structure

```markdown
# high-performance-cache

> A Caffeine-inspired local cache library for JVM applications.
> Achieves [X]M ops/sec at p99 [Y]ns under Zipfian access with [N] threads.

## Architecture
[Diagram showing the three cache segments (Window/Protected/Probationary), the read buffer
drain path, and the module dependency graph]

## Performance Results
[The filled-in comparison table from Phase 5]

## Phase-by-Phase Throughput
[Chart or table: LRUCache → StripedCache → StampedCache → TinyLFUCache → Caffeine]

## What I Built (and Why)
3–4 paragraphs covering:
- Why simulation and benchmarks came first
- The concurrency progression: single lock → striping → optimistic reads → lock-free
- TinyLFU hit rate improvement under Zipfian patterns
- GC behavior: G1 vs ZGC trade-offs

## Design Decisions
[Summary of each decision from docs/architecture.md — WHY each choice was made]

## How to Run
mvn package
java -jar cache-benchmark/target/benchmarks.jar       # full suite
java -cp cache-demo/target/... com.hpcache.demo.CacheDemo  # 60-second demo

## Building
mvn compile       # compile all modules
mvn test          # run all tests
mvn package       # build benchmark JAR
```

---

## Resume Bullet (fill in real numbers)

```
Built a high-performance in-memory cache library in Java achieving [X]M ops/sec at p99 [Y]ns
under Zipfian access with [N] threads — [Z]x improvement from Phase 1 (ReadWriteLock) to
Phase 3 (lock-free reads). Implemented TinyLFU eviction via Count-Min Sketch achieving [A]%
hit rate vs [B]% for LRU under Zipfian distribution. Benchmarked with JMH and profiled with
async-profiler; documented GC behavior across G1, ZGC, and Shenandoah.
```

---

## Exit Gate

### Correctness
- [ ] `mvn test` from root: all tests pass across all modules
- [ ] `mvn javadoc:javadoc` from root: zero errors
- [ ] `/phase-exit-check 6` passes all gates
- [ ] `CacheDemo.main()` runs for 60s and exits cleanly

### API Quality
- [ ] `/abstraction-check` on `cache-core`: zero findings (no internal types exposed)
- [ ] `/java-best-practices` on all `cache-core/api/` files: zero MUST FIX findings
- [ ] `ConcurrentHashMapCache` is deleted; no broken references remain

### Portfolio Quality
- [ ] README.md pushed to GitHub, renders correctly
- [ ] Comparison table has real numbers (no TBD entries)
- [ ] Flame graphs committed to `flamegraphs/` directory
- [ ] Resume bullet has real numbers filled in

---

## Open Questions

- [ ] Should the project be published as a Maven artifact to Maven Central? Out of scope
      for this project — it's a learning portfolio, not a production library. Note this
      in the README if it comes up in interviews.
- [ ] `@ThreadSafe` annotation — this is from the `net.jcip.annotations` library, not the
      JDK. Add it as a provided/optional dependency, or use a custom `@ThreadSafe` annotation
      defined in `cache-core`. Recommendation: define a simple package-private annotation
      rather than adding a dependency.
