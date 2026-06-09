Review Java files for concurrency bugs specific to this cache project.

If $ARGUMENTS specifies file paths or a directory, review those.
If no argument is given, review all Java files changed since the last git commit (`git diff --name-only HEAD`).
If the working tree is clean, review all files in `cache-core/src/`.

For each finding, report: `file:line — [CATEGORY] description — correct fix`

---

## Lock Correctness

- `LinkedHashMap` with `accessOrder=true`: any `get()` that uses `readLock` instead of `writeLock`
  (accessOrder mutation is a write — using readLock causes silent data corruption under concurrency)
- Any `lock.lock()` call not followed by `finally { lock.unlock(); }` in the same method
- `StampedLock` used in a reentrant pattern: a method that acquires the lock calling another method
  that also acquires it (StampedLock is NOT reentrant — this deadlocks)
- `synchronized(this)` in any Phase 2+ implementation class (StripedCache, StampedCache, TinyLFUCache)

## Thread Safety

- Non-`volatile`, non-atomic, non-locked shared mutable fields accessed from multiple threads
- Background thread created without `setDaemon(true)` — prevents JVM shutdown
- `Executors.newFixedThreadPool()` used for cache cleaner (must be `newSingleThreadScheduledExecutor`)
- `new Random()` in a concurrent context (must be `ThreadLocalRandom.current()`)
- `CompletableFuture` result used without `.exceptionally()` or try/catch — unhandled async exceptions

## CountMinSketch Integrity

- 4-bit counter increment that does not check `if (current < 15)` before adding (nibble overflow
  corrupts adjacent counters)
- Reset/aging operation where the bitmask is not `0x7777777777777777L` (must preserve nibble boundaries)
- Width calculation that does not produce a power of two (required for fast modulo via bitwise AND)

## Ring Buffer / ReadBuffer

- Buffer size that is not a power of two
- Index wrapping using `%` instead of `& (size - 1)` (modulo is expensive on the hot path)
- `ThreadLocal` buffer not initialized with `withInitial()` (lazy init causes null on first access)

## Time and TTL

- `System.currentTimeMillis()` used to compute TTL, expiry, or duration (must be `System.nanoTime()`)
- TTL math using milliseconds without converting to nanoseconds when storing against `nanoTime()`
  (off-by-1000x staleness bug)

## Memory Model (Phase 3+ files)

- `AtomicLong` / `AtomicInteger` used for stats counters in Phase 3+ code where `VarHandle` with
  acquire/release ordering is sufficient and lower overhead
- `VarHandle` operations using full volatile ordering (`getAndAdd`) for stats counters that only
  need `getAndAddAcquire` (unnecessarily expensive)
- `volatile` field read inside a locked section without a clear reason (redundant fence)

---

## Output Format

Group findings by category. For each:
```
file:line — [CATEGORY] finding description
  Fix: what to change and why
```

End with a summary:
```
SUMMARY
  Lock Correctness:    N findings
  Thread Safety:       N findings
  CountMinSketch:      N findings
  Ring Buffer:         N findings
  Time/TTL:            N findings
  Memory Model:        N findings
  Total:               N findings
```

If a category has zero findings, state that explicitly. Zero findings in all categories = CLEAN.
