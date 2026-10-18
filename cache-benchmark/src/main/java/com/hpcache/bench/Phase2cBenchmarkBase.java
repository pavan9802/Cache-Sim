package com.hpcache.bench;

import com.hpcache.api.Cache;
import com.hpcache.impl.CacheBuilder;
import com.hpcache.sim.MarketEvent;

/**
 * Benchmark base for Phase 2C: uses {@code LoadingCacheImpl} wrapping an LRU delegate.
 *
 * <p>The cache is pre-filled to 80% capacity so the benchmark exercises the hit path.
 * Hit-path calls go through one extra delegation layer vs plain {@link com.hpcache.impl.LRUCache};
 * the gate requires this overhead stays within 10%.
 */
public class Phase2cBenchmarkBase extends BenchmarkBase {

    @Override
    protected Cache<String, MarketEvent> buildCache() {
        var c = CacheBuilder.<String, MarketEvent>newBuilder()
            .maxSize(INITIAL_CACHE_SIZE)
            .loader(key -> new MarketEvent(key, 99.99, 100.01, 0L, System.nanoTime(), 0))
            .buildLoadingCache();

        MarketEvent seed = new MarketEvent("SEED", 99.99, 100.01, 0L, System.nanoTime(), 0);
        for (int i = 0; i < INITIAL_CACHE_SIZE; i++) {
            c.put(keyUniverse[i], seed);
        }
        return c;
    }

    @Override
    protected String phaseTag() { return "phase-2c"; }
}
