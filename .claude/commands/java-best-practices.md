Review Java files for modern language usage, Java 17+ features, and JVM best practices
specific to a high-performance cache project.

If $ARGUMENTS specifies file paths or a directory, review those.
If no argument is given, review all Java files changed since the last git commit.
If the working tree is clean, review all files under `cache-core/src/`.

For each finding: `file:line — [SEVERITY] [CATEGORY] description`
MUST FIX = correctness or significant performance issue. CONSIDER = improvement opportunity.

---

## Modern Java 17+ Features

- **Records**: A class that is immutable, has only field accessors, and no mutable state
  should be a `record`. Check: `MarketEvent`, `CacheStats`, `CacheEntry`, config objects.
  (MUST FIX if the class is explicitly specified as a record in the phase spec)

- **Pattern matching instanceof**: `if (x instanceof Foo) { Foo f = (Foo) x; }` should be
  `if (x instanceof Foo f)` — eliminates the redundant cast (CONSIDER)

- **Switch expressions**: Multi-branch switch statements assigning to a variable should use
  switch expressions with `->` syntax (CONSIDER)

- **Text blocks**: Multi-line string literals (e.g., in test assertions or log templates)
  that span 3+ lines should use `"""` text blocks (CONSIDER)

- **`var` for local variables**: Long generic type names on the left of assignments where
  the type is obvious from the right side (CONSIDER — do not use in public API signatures)

## Concurrency Primitives

- **VarHandle vs AtomicLong** (MUST FIX in Phase 3+ files):
  Stats counters (`hitCount`, `missCount`, `evictionCount`) in `TinyLFUCache` and any Phase 3
  class should use `VarHandle` with `getAndAddAcquire`, not `AtomicLong.incrementAndGet()`.
  `AtomicLong` boxes the value and imposes a full volatile fence; VarHandle avoids both.

- **`new Random()` in concurrent code** (MUST FIX):
  Any `new Random()` or `new Random(seed)` used from code that may run on multiple threads.
  Must be `ThreadLocalRandom.current()` — `Random` uses synchronization internally.

- **`wait()`/`notify()` usage** (MUST FIX):
  Low-level `Object.wait()` / `Object.notify()` should be replaced with `Lock` + `Condition`,
  `CountDownLatch`, `Semaphore`, or `CompletableFuture` as appropriate.

- **`volatile` on a field that needs atomic compare-and-set** (MUST FIX):
  A `volatile` field that is read-modify-written (e.g., `count++`) without CAS is a race condition.
  Use `VarHandle.compareAndSet()` or an `AtomicX` type.

## Memory and Performance

- **Non-`final` fields that never change after construction** (MUST FIX):
  Any field assigned only in the constructor and never mutated should be `final`.
  `final` enables JIT optimizations and communicates intent.

- **Protected fields that could be private** (CONSIDER):
  Fields in non-subclassed classes declared `protected` without a documented extension plan.

- **`System.currentTimeMillis()` for duration/expiry** (MUST FIX):
  Any use of `currentTimeMillis()` for computing TTL, expiry time, or measuring elapsed duration.
  Must be `System.nanoTime()` — wall clock time is not monotonic.

- **Non-daemon background threads** (MUST FIX):
  Any `new Thread(...)` or executor thread factory that does not call `setDaemon(true)`.
  Non-daemon threads prevent JVM shutdown.

- **`printStackTrace()`** (MUST FIX):
  Replace with `log.error("message", e)` using SLF4J. Stack traces to stdout are lost in
  production environments and bypass log aggregation.

## API Design

- **Missing `@FunctionalInterface`** (MUST FIX):
  Any interface with exactly one abstract method intended for lambda use
  (e.g., `RemovalListener`, loaders, serializers).

- **Boolean parameters on public methods** (CONSIDER):
  `void configure(boolean enableStats, boolean enableExpiry)` — callers can't read the intent
  at the call site. Prefer separate methods or a config object.

- **Methods with > 4 parameters** (CONSIDER):
  Candidate for a config/options object or builder pattern.

- **Missing Javadoc on public API** (MUST FIX for Phase 6; CONSIDER for earlier phases):
  Public methods in `Cache.java`, `CacheBuilder.java`, `CacheStats.java`, `LoadingCache.java`.
  Javadoc must explain the contract (what it guarantees) and thread safety, not just restate
  the method name.

## Generics and Type Safety

- **Raw types** (MUST FIX): `Cache` instead of `Cache<K,V>`, `List` instead of `List<String>`, etc.

- **Unchecked casts** (MUST FIX): Any `@SuppressWarnings("unchecked")` that is not accompanied
  by a comment explaining why the cast is safe.

---

## Output Format

Group findings by category:

```
MODERN JAVA 17+
  [none] / file:line — [SEVERITY] description

CONCURRENCY PRIMITIVES
  [none] / file:line — [SEVERITY] description

MEMORY AND PERFORMANCE
  [none] / file:line — [SEVERITY] description

API DESIGN
  [none] / file:line — [SEVERITY] description

GENERICS AND TYPE SAFETY
  [none] / file:line — [SEVERITY] description
```

End with:
```
SUMMARY
  MUST FIX:  N findings  ← exit gate fails if > 0
  CONSIDER:  N findings  ← informational, address before Phase 6
```
