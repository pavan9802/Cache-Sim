package com.hpcache.stress;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

import com.hpcache.api.LoadingCache;
import com.hpcache.impl.CacheBuilder;

/**
 * jcstress test: two actors simultaneously miss the same key.
 *
 * <p>The only ACCEPTABLE outcome is {@code 1} — loader called exactly once.
 * {@code 2} means both actors ran the loader, which is the thundering herd bug.
 *
 * <p>A single shared cache is used across all @State instances to avoid creating
 * thousands of ScheduledExecutorService background-cleaner threads (one per @State).
 * Each @State instance uses a unique key so iterations don't interfere with each other.
 *
 * <p>Run: {@code java -jar cache-stress.jar -t ThunderingHerdStressTest}
 */
@JCStressTest
@Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "loader called exactly once — thundering herd prevention works")
@Outcome(id = "2", expect = Expect.FORBIDDEN,  desc = "loader called twice — thundering herd prevention failed")
@Outcome(          expect = Expect.FORBIDDEN,  desc = "any other count is a bug")
@State
public class ThunderingHerdStressTest {

    // Shared across all @State instances — created once, no per-iteration executor leak.
    // maxSize is large so LRU eviction doesn't interfere with in-progress loads.
    private static final LoadingCache<Long, Integer> CACHE = CacheBuilder.<Long, Integer>newBuilder()
        .maxSize(100_000)
        .buildLoadingCache();

    private static final AtomicLong KEY_SEQ = new AtomicLong(0);

    final AtomicInteger loaderCallCount = new AtomicInteger(0);
    // Each @State instance gets a unique key — guarantees a cold miss on every iteration.
    final long key = KEY_SEQ.incrementAndGet();

    @Actor
    public void actor1() {
        CACHE.get(key, k -> { loaderCallCount.incrementAndGet(); return 1; });
    }

    @Actor
    public void actor2() {
        CACHE.get(key, k -> { loaderCallCount.incrementAndGet(); return 1; });
    }

    @Arbiter
    public void arbiter(I_Result r) {
        r.r1 = loaderCallCount.get();
    }
}
