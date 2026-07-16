package com.hpcache.builder;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.hpcache.api.Cache;
import com.hpcache.api.RemovalCause;

import static org.junit.jupiter.api.Assertions.*;

class CacheBuilderTest {

    @Test
    void maxSizeZeroThrowsIllegalStateException() {
        var builder = CacheBuilder.<String, String>newBuilder().maxSize(0);
        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void missingMaxSizeThrowsIllegalStateException() {
        var builder = CacheBuilder.<String, String>newBuilder();
        IllegalStateException e = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(e.getMessage().contains("maxSize is required"));
    }

    @Test
    void zeroTtlThrowsIllegalStateException() {
        var builder = CacheBuilder.<String, String>newBuilder()
                .maxSize(10)
                .ttl(0, TimeUnit.SECONDS);
        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void negativeTtlThrowsIllegalStateException() {
        var builder = CacheBuilder.<String, String>newBuilder()
                .maxSize(10)
                .ttl(-5, TimeUnit.SECONDS);
        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void builtCacheStoresAndRetrievesValues() {
        Cache<String, String> cache = CacheBuilder.<String, String>newBuilder()
                .maxSize(10)
                .build();
        try {
            cache.put("k", "v");
            assertEquals("v", cache.get("k"));
        } finally {
            cache.close();
        }
    }

    @Test
    void removalListenerIsWiredToBuiltCache() {
        AtomicInteger evictions = new AtomicInteger();
        Cache<String, String> cache = CacheBuilder.<String, String>newBuilder()
                .maxSize(1)
                .removalListener((k, v, cause) -> {
                    if (cause == RemovalCause.SIZE) {
                        evictions.incrementAndGet();
                    }
                })
                .build();
        try {
            cache.put("a", "1");
            cache.put("b", "2");
            assertEquals(1, evictions.get());
        } finally {
            cache.close();
        }
    }

    @Test
    void customCleanupIntervalIsAppliedToBuiltCache() throws InterruptedException {
        Cache<String, String> cache = CacheBuilder.<String, String>newBuilder()
                .maxSize(10)
                .ttl(50, TimeUnit.MILLISECONDS)
                .cleanupInterval(50, TimeUnit.MILLISECONDS)
                .build();
        try {
            cache.put("k", "v");
            Thread.sleep(300);
            assertEquals(0, cache.size());
        } finally {
            cache.close();
        }
    }
}