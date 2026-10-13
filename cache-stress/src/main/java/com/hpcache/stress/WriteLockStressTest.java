package com.hpcache.stress;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

import com.hpcache.api.Cache;
import com.hpcache.impl.CacheBuilder;

@JCStressTest
@Outcome(id = "1, 0", expect = Expect.ACCEPTABLE,  desc = "entry survives concurrent reads")
@Outcome(              expect = Expect.FORBIDDEN,   desc = "data corruption — entry lost or duplicated")
@State
public class WriteLockStressTest {

    // Static: jcstress pre-allocates O(stride × tests) @State instances per fork.
    // Creating a ScheduledExecutorService per instance OOMs the forked JVM.
    // This test intentionally exercises concurrent access to the *same* cache, so
    // a single shared instance is both correct and necessary.
    private static final Cache<String, Integer> CACHE;
    static {
        CACHE = CacheBuilder.<String, Integer>newBuilder().maxSize(100).build();
        CACHE.put("key", 1);
    }

    @Actor public void actor1() { CACHE.get("key"); }
    @Actor public void actor2() { CACHE.get("key"); }

    @Arbiter public void arbiter(II_Result r) {
        r.r1 = CACHE.size();  // must always be 1
    }
}
