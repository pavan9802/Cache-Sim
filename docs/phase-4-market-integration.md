# Phase 4 — Market Data Integration

## Context & Purpose

Wire the simulator and the cache together into an end-to-end system that resembles a real
trading system component. This phase demonstrates the library working under realistic load
conditions, exercises all the cache features built in Phases 1–3, and validates that
backpressure, staleness detection, and concurrent producer/consumer pipelines work correctly.

The result of this phase is a runnable demo (`cache-demo`) and 4 scenario tests that can
be shown in an interview or on GitHub.

---

## Pre-conditions

- Phase 3C exit gate fully passed (TinyLFUCache at full performance)
- All simulator classes from Phase 0 are working (`MarketEvent`, `MarketDataSimulator`, etc.)
- `Cache<K,V>` interface and `CacheBuilder` finalized

---

## Files to Create

```
cache-simulator/src/main/java/com/hpcache/sim/MarketDataFeedHandler.java
cache-simulator/src/main/java/com/hpcache/sim/PriceService.java
cache-simulator/src/main/java/com/hpcache/sim/scenarios/NormalMarketScenario.java
cache-simulator/src/main/java/com/hpcache/sim/scenarios/MarketOpenScenario.java
cache-simulator/src/main/java/com/hpcache/sim/scenarios/FlashCrashScenario.java
cache-simulator/src/main/java/com/hpcache/sim/scenarios/SymbolChurnScenario.java

cache-demo/src/main/java/com/hpcache/demo/CacheDemo.java
```

---

## Implementation Sequence

1. **`MarketDataFeedHandler` — producer thread only** (generates events, puts into queue)
   → Unit test: producer generates events at the configured rate for 1 second

2. **Add consumer thread** (drains queue, puts events into cache)
   → Unit test: start producer + consumer, verify cache is populated after 1 second

3. **Add backpressure** (drop oldest events when queue is full)
   → Unit test: set queue capacity to 10, produce 100 events rapidly, verify `droppedEventCount > 0`

4. **`PriceService`** — read layer on top of the cache
   → Unit test: put a `MarketEvent`, verify `getMidPrice()` returns the correct mid

5. **`PriceService.isStale()`** — stale detection
   → Unit test: event with old timestamp returns `isStale() == true`

6. **`NormalMarketScenario`** — baseline 60-second run
   → Run manually, verify it completes without exceptions, hit rate > 85%

7. **Remaining scenarios** (MarketOpen, FlashCrash, SymbolChurn)

8. **`CacheDemo.main()`** — wires everything together, runs 60 seconds

---

## Class-by-Class Spec

### `MarketDataFeedHandler`

**Package:** `com.hpcache.sim`

**Purpose:** Decoupled producer/consumer pipeline. The producer generates ticks from the
simulator; the consumer writes them to the cache. They are decoupled via a `BlockingQueue`
so the consumer can absorb bursts without the producer blocking.

**Fields:**
```java
private final Cache<String, MarketEvent> cache;
private final MarketDataSimulator simulator;
private final BurstScheduler scheduler;
private final BlockingQueue<MarketEvent> eventQueue;  // bounded
// Java 21: virtual threads — daemon by default, no thread pool sizing needed
private final ExecutorService producer = Executors.newThreadPerTaskExecutor(
    Thread.ofVirtual().name("market-data-producer").factory()
);
private final ExecutorService consumer = Executors.newThreadPerTaskExecutor(
    Thread.ofVirtual().name("market-data-consumer").factory()
);
private final AtomicLong eventsProduced = new AtomicLong();
private final AtomicLong eventsConsumed = new AtomicLong();
private final AtomicLong eventsDropped = new AtomicLong();
private volatile boolean running = false;
```

**Queue capacity:** 10,000 events. This provides ~100ms of buffering at 100,000 ticks/sec.

**Backpressure — when queue is full:**
```java
// Try to offer with 0 timeout — if rejected, drop and count
if (!eventQueue.offer(event)) {
    eventsDropped.incrementAndGet();
}
```

**Flow:**
```
MarketDataSimulator → [BlockingQueue<MarketEvent> capacity=10,000] → Cache
      (producer thread)                                              (consumer thread)
```

#### Watch Out For

**`volatile boolean running`** must be checked in the producer/consumer loops. Using a
plain `boolean` field without `volatile` means a thread starting `stop()` may never be
seen by the producer thread, causing the loop to run forever.

**Queue `poll()` with timeout in consumer** — use `eventQueue.poll(1, TimeUnit.MILLISECONDS)`
rather than `eventQueue.take()`. `take()` blocks indefinitely; with a timeout, the consumer
loop can check `running` and exit cleanly on shutdown.

**Thread naming** — threads are already named via `Thread.ofVirtual().name("...")` in the
executor factory. Unnamed threads make thread dumps unreadable. Virtual threads also show up
in thread dumps with the name you give them, so this matters even more than with platform
threads.

**`close()`** — must stop both producer and consumer threads and await termination.
Use `ExecutorService.shutdown()` followed by `awaitTermination(5, TimeUnit.SECONDS)`.
If tasks don't stop within 5 seconds, call `shutdownNow()`.

---

### `PriceService`

**Package:** `com.hpcache.sim`

**Purpose:** Read layer — how a trading system would query cached prices. Demonstrates
stale-price detection, which is critical in real trading systems.

**Methods:**
```java
Optional<Double> getMidPrice(String symbol)
Optional<Double> getBidPrice(String symbol)
Optional<Double> getAskPrice(String symbol)
boolean isStale(String symbol, long maxAgeMs)
Map<String, Double> getAllMidPrices()   // snapshot — O(N)
double getSpread(String symbol)         // returns 0.0 if symbol not cached
```

**Stale detection:**
```java
public boolean isStale(String symbol, long maxAgeMs) {
    MarketEvent event = cache.get(symbol);
    if (event == null) return true;   // missing = stale
    long ageMs = (System.nanoTime() - event.timestampNanos()) / 1_000_000L;
    return ageMs > maxAgeMs;
}
```

#### Watch Out For

**`Optional<Double>` is acceptable here** — `PriceService` is read by application code, not
on a hot microbenchmark path. The Optional clearly signals "price may be unavailable" which
is semantically important for trading systems. This is the correct place for Optional.

**`getAllMidPrices()` is O(N)** — it iterates all cache entries. Only use for reporting/monitoring,
not for latency-sensitive pricing queries. Document this in Javadoc.

**Timestamp comparison in `isStale()`** — uses `System.nanoTime()` consistently with how
`MarketEvent.timestampNanos` is recorded. Never mix `nanoTime` and `currentTimeMillis` for
age calculations.

---

### Scenarios

All scenarios extend a common pattern:
1. Build cache via `CacheBuilder`
2. Create `MarketDataFeedHandler` and `PriceService`
3. Start the feed
4. Print stats every 5 seconds via `StatsReporter`
5. Run for the scenario duration
6. Stop feed, print final stats, verify assertions

**`NormalMarketScenario`:**
- 100 symbols, 10,000 ticks/sec, Zipfian distribution, `maxSize=1000`, `ttl=5s`
- Duration: 60 seconds
- Assertions at end: `hitRate > 0.85`, `eventsDropped == 0`, no exceptions

**`MarketOpenScenario`:**
- Starts at 100,000 ticks/sec for 10s, then drops to 5,000 ticks/sec
- Assertions: queue depth stays bounded (< 5,000), p999 latency recorded during burst

**`FlashCrashScenario`:**
- One symbol ("AAPL") gets 50x normal tick rate for 5 seconds
- Assertions: AAPL hit rate stays high, other symbols' hit rates not degraded

**`SymbolChurnScenario`:**
- 500 new symbols added at t=30s
- Assertions: original 100 hot symbols retain > 70% hit rate after churn

---

## Test Strategy

### Unit Tests

| Test | Invariant proved |
|---|---|
| `FeedHandler`: start, produce 100 events, stop — `eventsProduced >= 100` | Producer works |
| `FeedHandler`: queue full, overflow → `eventsDropped > 0` | Backpressure works |
| `PriceService`: cached event → `getMidPrice()` returns correct value | Read layer works |
| `PriceService`: event with 2000ms old timestamp, `isStale(symbol, 1000)` returns true | Staleness detected |
| `PriceService`: uncached symbol → `getMidPrice()` returns `Optional.empty()` | Missing returns empty |

### Scenario Validation (manual run + assertion)

Each scenario must run for its full duration without:
- Any uncaught exception
- `eventsDropped` unexpectedly high (> 1% of total)
- Final hit rate below scenario-specific floor

---

## Exit Gate

### Correctness
- [ ] All unit tests pass: zero failures across `@RepeatedTest(10)`
- [ ] `NormalMarketScenario` runs for 60s without exception, final hit rate > 85%
- [ ] All 4 scenarios run without uncaught exceptions
- [ ] `CacheDemo.main()` runs for 60s, prints stats, exits cleanly

### Performance
- [ ] Market open burst: queue depth stays < 5,000 during 100k ticks/sec burst
- [ ] Flash crash: other-symbol hit rate doesn't degrade > 10% during crash

### Code Quality
- [ ] `/concurrency-review` on `MarketDataFeedHandler.java`: zero findings
- [ ] `/java-best-practices`: zero MUST FIX findings

---

## Open Questions

- [ ] Should `StatsReporter` be wired into scenarios automatically or manually?
      Recommendation: pass it as an optional parameter to each scenario's `run()` method.
- [ ] `CacheDemo.main()` — which cache implementation to use? Recommendation: use
      `TinyLFUCache` (Phase 3) to show the most advanced version in the demo.
