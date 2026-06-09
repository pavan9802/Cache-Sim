# Phase 2C — Loading Cache & Thundering Herd Prevention

## Context & Purpose

When a cache entry expires and many threads simultaneously request the same key, they all
see a miss and all try to recompute or reload the value. This is the **thundering herd
problem**: N threads each independently do expensive work (DB query, HTTP call, computation)
to produce the same value. For a hot key with a short TTL, this can saturate your backend
with redundant requests exactly when it's already under load.

`LoadingCache` solves this by ensuring that for any given key, exactly one thread performs
the load while all other threads wait for that future to complete. This phase also introduces
`AsyncLoadingCache` — a non-blocking variant that returns `CompletableFuture<V>` so the caller
is never blocked.

---

## Pre-conditions

- Phase 2A and 2B exit gates fully passed
- `LRUCache` is the foundation (LoadingCache wraps LRU logic)
- `CompletableFuture` and `ConcurrentHashMap` available in Java 17 stdlib

---

## Files to Create

```
cache-core/src/main/java/com/hpcache/api/LoadingCache.java
cache-core/src/main/java/com/hpcache/api/AsyncLoadingCache.java
cache-core/src/impl/LoadingCacheImpl.java
cache-core/src/impl/AsyncLoadingCacheImpl.java

cache-core/src/test/java/com/hpcache/impl/LoadingCacheTest.java
cache-core/src/test/java/com/hpcache/impl/LoadingCacheConcurrencyTest.java
cache-stress/src/main/java/com/hpcache/stress/ThunderingHerdStressTest.java
```

**Update:**
```
cache-core/src/main/java/com/hpcache/builder/CacheBuilder.java  ← add loader() method
```

---

## Implementation Sequence

1. **`LoadingCache` interface** — extends `Cache<K,V>` with loader methods
   → Compile check only

2. **`LoadingCacheImpl` — basic loader without thundering herd prevention**
   → Unit test: on miss, loader is called; on hit, loader is NOT called

3. **Add thundering herd prevention** — `ConcurrentHashMap<K, CompletableFuture<V>> inFlight`
   → Unit test (non-concurrent): single-threaded loader still called once per miss

4. **Thundering herd concurrency test** — 16 threads simultaneous miss, loader called once
   → This is the key correctness test for this entire phase

5. **`AsyncLoadingCache` interface + `AsyncLoadingCacheImpl`** — non-blocking variant

6. **Refresh-ahead** in `AsyncLoadingCacheImpl`

7. **`CacheBuilder.loader(Function<K,V>)` method** — wire up builder support

8. **jcstress stress test** (`ThunderingHerdStressTest`)

9. **Run benchmarks**

---

## Class-by-Class Spec

### `LoadingCache` (interface)

**Package:** `com.hpcache.api`

```java
public interface LoadingCache<K, V> extends Cache<K, V> {
    V get(K key, Function<K, V> loader);    // load on miss using provided function
    V getOrLoad(K key);                      // load on miss using configured loader
    void refresh(K key);                     // force reload even if present
}
```

---

### `LoadingCacheImpl`

**Package:** `com.hpcache.impl`

**Purpose:** Wraps an `LRUCache` and adds thundering herd prevention. The key mechanism:
`ConcurrentHashMap.computeIfAbsent()` is atomic — only ONE thread creates the
`CompletableFuture` for a given key. All other threads receive the same future and block on
`future.get()`. Only the thread that created the future runs the loader.

**Thundering herd prevention implementation:**
```java
// In-flight loads: key → future being computed by exactly one thread
private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

@Override
public V get(K key, Function<K, V> loader) {
    // Fast path: value already in cache
    V cached = delegate.get(key);
    if (cached != null) return cached;

    // computeIfAbsent is atomic — only ONE thread creates the CompletableFuture
    CompletableFuture<V> future = inFlight.computeIfAbsent(key, k ->
        CompletableFuture.supplyAsync(() -> {
            V value = loader.apply(k);   // only ONE thread runs this
            delegate.put(k, value);
            inFlight.remove(k);
            return value;
        })
    );

    try {
        return future.get();   // all other threads block here on the same future
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CacheLoadException(key, e);
    } catch (ExecutionException e) {
        inFlight.remove(key);  // remove failed future so next request retries
        throw new CacheLoadException(key, e.getCause());
    }
}
```

#### Watch Out For

**`computeIfAbsent` atomicity.** `ConcurrentHashMap.computeIfAbsent()` is only atomic with
respect to the key — the mapping function may run concurrently for different keys. This is
exactly what we want. But the mapping function must NOT call `computeIfAbsent` on the same
map for the same key — that would deadlock (documented in `ConcurrentHashMap` Javadoc).

**Removing the in-flight future.** There are two places the future is removed from `inFlight`:
1. On success: inside `supplyAsync` after `put()` completes
2. On failure: in the `catch (ExecutionException e)` block

If the success path runs `inFlight.remove(k)` *before* the future's `get()` returns in
waiting threads, a new request for the same key may see no in-flight future and start another
load. This is a minor issue (double-load is possible in a race window) but not a correctness
bug — both loads produce the same value and one overwrites the other. Document this.

**`future.get()` blocks the calling thread.** All threads that called `get()` for the same
key during the load are now blocked. In a read-heavy, Zipfian workload, a hot key expiry
causes a brief latency spike for all threads requesting it. This is correct and expected —
it is always better than N simultaneous backend requests. Document the trade-off.

**Exception handling.** If the loader throws, the `ExecutionException` wrapper must be
unwrapped before rethrowing. Define `CacheLoadException extends RuntimeException` that
wraps the original cause. This keeps the API unchecked (no `throws` on `get()`).

---

### `AsyncLoadingCache` (interface)

**Package:** `com.hpcache.api`

```java
public interface AsyncLoadingCache<K, V> extends Cache<K, V> {
    CompletableFuture<V> getAsync(K key);
    CompletableFuture<V> getAsync(K key, Function<K, CompletableFuture<V>> asyncLoader);
}
```

---

### `AsyncLoadingCacheImpl` — refresh-ahead

**Purpose:** Non-blocking variant. Returns `CompletableFuture<V>` immediately. If the entry
is near expiry, triggers a background reload without blocking any caller.

**Refresh-ahead logic:**
```java
private void maybeRefreshAhead(K key, CacheEntry<V> entry) {
    long remainingNanos = entry.expiryNanos() - System.nanoTime();
    if (remainingNanos < refreshThresholdNanos && !inFlight.containsKey(key)) {
        // Trigger background refresh — no caller waits for this
        inFlight.computeIfAbsent(key, k ->
            CompletableFuture.supplyAsync(() -> {
                V value = loader.apply(k);
                delegate.put(k, value);
                inFlight.remove(k);
                return value;
            }, refreshExecutor)
        );
    }
}
```

**`refreshExecutor`:** A dedicated `ScheduledExecutorService` with daemon threads.
Never use `ForkJoinPool.commonPool()` for refresh-ahead — it is shared and can be
saturated by other application code, causing missed refreshes.

#### Watch Out For
- The refresh-ahead check `!inFlight.containsKey(key)` is a non-atomic read-check-act
  sequence. Two threads may both see no in-flight entry and both call `computeIfAbsent`.
  `computeIfAbsent` handles this correctly (only one wins) — the check is just an
  optimization to avoid the computeIfAbsent overhead on the common case.
- `refreshThresholdNanos` should default to 20% of the TTL. If TTL is 30s, start
  refreshing when < 6s remain. Configure via `CacheBuilder.refreshAheadRatio(double)`.

---

## Test Strategy

### Unit Tests (`LoadingCacheTest`)

| Test | Invariant proved |
|---|---|
| On miss, loader is called once and value is cached | Basic loading works |
| On hit, loader is NOT called | Cached values are served without loading |
| Loader result is cached — second `get()` does NOT call loader | Caching of loaded values |
| Loader throws — exception propagates as `CacheLoadException` | Exception handling |
| `refresh(key)` causes loader to be called even when value present | Force reload works |

### Concurrency Tests (`LoadingCacheConcurrencyTest`)

| Test | Invariant proved | `@RepeatedTest` | jcstress |
|---|---|---|---|
| 16 threads simultaneously miss same key — loader called **exactly once** | Thundering herd prevention works | 100 | **Y** |
| 16 threads simultaneously miss same key — all 16 receive same correct value | No thread gets null despite the miss | 100 | N |
| Loader throws for one key — all 16 waiting threads receive `CacheLoadException` | Exception broadcast works | 50 | N |
| Different keys load concurrently — loader called once per key | Per-key isolation works | 50 | N |

**jcstress `ThunderingHerdStressTest` — the definitive test:**
```java
@JCStressTest
@Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "loader called exactly once")
@Outcome(id = "2, 3, ...", expect = Expect.FORBIDDEN, desc = "loader called multiple times")
@State
public class ThunderingHerdStressTest {
    AtomicInteger loaderCallCount = new AtomicInteger(0);
    LoadingCacheImpl<String, Integer> cache = /* setup with maxSize=10 */;

    @Actor public void actor1() { cache.get("key", k -> { loaderCallCount.incrementAndGet(); return 1; }); }
    @Actor public void actor2() { cache.get("key", k -> { loaderCallCount.incrementAndGet(); return 1; }); }
    @Arbiter public void arbiter(I_Result r) { r.r1 = loaderCallCount.get(); }
}
```

### Benchmark Validation

`LoadingCacheImpl` adds `CompletableFuture` overhead on the miss path. Hits should be
similar to `LRUCache`. Benchmark the **hit rate impact** — does thundering herd prevention
improve observed hit rates under bursty workloads?

---

## Exit Gate

### Correctness
- [ ] All `LoadingCacheTest` unit tests pass, zero failures across `@RepeatedTest(10)`
- [ ] Thundering herd test passes: loader called **exactly 1 time** across 100 runs
- [ ] jcstress `ThunderingHerdStressTest`: zero forbidden outcomes
- [ ] Suite re-run 3 times: identical results

### Performance
- [ ] Results recorded: `results/phase-2c-loading-<timestamp>.json`
- [ ] Hit path latency: `LoadingCacheImpl` within 10% of `LRUCache` on `ReadHeavyBenchmark`
  (hits should not be slower — the CompletableFuture only matters on misses)

### Code Quality
- [ ] `/concurrency-review` on `LoadingCacheImpl.java`: zero findings
- [ ] `/java-best-practices`: zero MUST FIX findings

---

## Open Questions

- [ ] Should `LoadingCacheImpl` be a subclass of `LRUCache` or a wrapper (composition)?
      Recommendation: composition — `LoadingCacheImpl` holds a `delegate: Cache<K,V>` field.
      This allows it to work with `StripedCache` or any other implementation, not just LRU.
- [ ] `future.get()` blocks the calling thread indefinitely if the loader hangs. Should
      there be a timeout? Recommendation: yes — add `loaderTimeout(long, TimeUnit)` to
      `CacheBuilder`. Default: no timeout (matches Caffeine's default behavior). If the
      loader is expected to be fast, a timeout of 5s is reasonable.
