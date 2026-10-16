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
@Outcome(id = "-1, 1", expect = Expect.ACCEPTABLE,  desc = "reader ran before writer, entry present after ")
@Outcome(id = "42, 1", expect = Expect.ACCEPTABLE,  desc = "reader ran after writer, read the correct value ")
@Outcome(              expect = Expect.FORBIDDEN,   desc = "data corruption — entry lost or duplicated")
@State
public class StampedLockStressTest {
    private final Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder()
        .maxSize(100)
        .optimisticReads(true)
        .build();

    @Actor 
    public void writer(){
        cache.put("key", 42); 
    }
    @Actor 
    public void reader(II_Result r){
        Integer v = cache.get("key"); 
        r.r1 = v == null ? -1 : v; 
    }
    @Arbiter 
    public void arbiter(II_Result r){
        r.r2 = cache.size(); 
    }

}
