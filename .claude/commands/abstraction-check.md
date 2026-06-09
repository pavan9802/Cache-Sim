Review Java files for abstraction violations, module boundary breaches, and structural issues.

If $ARGUMENTS specifies file paths or a directory, review those.
If no argument is given, review all Java files changed since the last git commit.
If the working tree is clean, review all files under `cache-core/src/` and `cache-simulator/src/`.

For each finding: `file:line — [SEVERITY] [CATEGORY] description`
Severity is either MUST FIX or CONSIDER.

---

## Module Boundary Violations (always MUST FIX)

- `cache-core` imports anything from `com.hpcache.sim`, `com.hpcache.bench`, or `com.hpcache.demo`
- `cache-core` imports Caffeine (`com.github.ben-manes.caffeine`), Guava (`com.google.common`),
  or JMH (`org.openjdk.jmh`)
- `cache-core` imports any Spring, Quarkus, or framework class
- `cache-simulator` imports from `cache-benchmark` or `cache-demo`
- `cache-demo` imports JMH benchmark classes
- Any module importing a class that creates a cycle in the dependency graph

## API Boundary Violations (MUST FIX)

- Internal/package-private types (`CacheEntry`, `EvictionPolicy`, `CountMinSketch`, etc.)
  exposed in public method signatures of the public API
- `Optional<V>` as a return type from `Cache.get()` or any loading cache get method
  (adds allocation overhead on the hot path; `null` is the correct contract)
- Public constructors on classes that should only be created via `CacheBuilder`
  (bypasses validation and breaks the builder contract)

## Hot Path Allocations (MUST FIX in Phase 3+ files; CONSIDER in earlier phases)

Phase 3+ files are: `TinyLFUCache.java`, `ReadBuffer.java`, any class in `com.hpcache.internal`
- Object instantiation inside `get()` or `put()` beyond what the contract requires
  (e.g., creating wrapper objects, defensive copies, intermediate collections)
- Autoboxing: primitive `int`/`long` being boxed to `Integer`/`Long` on the critical path
- `String.format()` or string concatenation in a hot-path log statement without an
  `if (log.isDebugEnabled())` guard

## Over-Engineering (CONSIDER)

- Abstract class or interface with exactly one implementation and no documented extension plan
- Factory/registry class for objects simple enough to construct directly
- Utility class of only static methods wrapping trivial one-liners
- Design pattern applied where a simpler data structure would read more clearly

## Under-Abstraction (CONSIDER)

- Lock acquire/release boilerplate repeated in 3+ methods that could be extracted
- TTL calculation logic duplicated in multiple places
- The same `CacheEntry` null-check + expiry pattern repeated without extraction

---

## Output Format

Group findings by category:

```
MODULE BOUNDARIES
  [none] / file:line — [MUST FIX] description

API BOUNDARIES
  [none] / file:line — [MUST FIX] description

HOT PATH ALLOCATIONS
  [none] / file:line — [MUST FIX / CONSIDER] description

OVER-ENGINEERING
  [none] / file:line — [CONSIDER] description

UNDER-ABSTRACTION
  [none] / file:line — [CONSIDER] description
```

End with:
```
SUMMARY
  MUST FIX:  N findings  ← gate fails if > 0
  CONSIDER:  N findings  ← informational only
```
