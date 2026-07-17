package com.hpcache.stress;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

import com.hpcache.impl.LRUCache;

@JCStressTest
@Outcome(id = "1", expect = Expect.ACCEPTABLE,
    desc = "The single entry survives concurrent get() calls uncorrupted.")
@State
public class WriteLockStressTest {
    // jcstress creates many State instances per run with no teardown hook; LRUCache's
    // constructor unconditionally starts a background cleaner thread with a perpetual
    // scheduled task, so a fresh instance per trial leaks a thread per instantiation
    // and exhausts the forked JVM's heap. Sharing one cache across all trials keeps
    // exactly one cleaner alive for the whole fork while still exercising the same
    // get() write-lock path under concurrent contention.
    private static final LRUCache<String, Integer> cache = new LRUCache<>(100, -1);

    static {
        cache.put("key", 1);
    }

    @Actor
    public void actor1() {
        cache.get("key");
    }

    @Actor
    public void actor2() {
        cache.get("key");
    }

    @Arbiter
    public void arbiter(I_Result r) {
        r.r1 = cache.size();
    }
}
