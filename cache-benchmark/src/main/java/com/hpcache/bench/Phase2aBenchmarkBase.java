package com.hpcache.bench;

import com.hpcache.api.Cache;
import com.hpcache.impl.CacheBuilder;
import com.hpcache.sim.MarketEvent;

public class Phase2aBenchmarkBase extends BenchmarkBase {

    @Override
    protected Cache<String, MarketEvent> buildCache() {
        Cache<String, MarketEvent> c = CacheBuilder.<String, MarketEvent>newBuilder()
            .maxSize(INITIAL_CACHE_SIZE)
            .stripeCount(16)
            .build();
        MarketEvent seed = new MarketEvent("SEED", 99.99, 100.01, 0L, System.nanoTime(), 0);
        for (int i = 0; i < INITIAL_CACHE_SIZE; i++) {
            c.put(keyUniverse[i], seed);
        }
        return c;
    }

    @Override
    protected String phaseTag() { return "phase-2a"; }
}
