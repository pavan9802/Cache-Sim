package com.hpcache.impl;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hpcache.api.CacheLoadException;
import com.hpcache.api.LoadingCache;

/**
 * Unit tests for {@link LoadingCacheImpl}.
 *
 * <p>Covers the basic loading contract: loader invocation on miss, caching of loaded
 * values, exception propagation, {@code getOrLoad}, {@code refresh}, and null-key guards.
 */
class LoadingCacheTest {

    private LoadingCache<String, String> cache;

    @BeforeEach
    void setUp() {
        cache = CacheBuilder.<String, String>newBuilder()
            .maxSize(10)
            .buildLoadingCache();
    }

    @AfterEach
    void tearDown() {
        cache.close();
    }

    // -------------------------------------------------------------------------
    // Basic loading
    // -------------------------------------------------------------------------

    @Test
    void onMissLoaderIsCalledOnceAndValueIsCached() {
        AtomicInteger calls = new AtomicInteger(0);

        String result = cache.get("key", k -> { calls.incrementAndGet(); return "loaded"; });

        assertEquals("loaded", result);
        assertEquals(1, calls.get());
        assertEquals("loaded", cache.get("key"), "loaded value must be in cache for subsequent plain get");
    }

    @Test
    void onHitLoaderIsNotCalled() {
        cache.put("key", "existing");
        AtomicInteger calls = new AtomicInteger(0);

        String result = cache.get("key", k -> { calls.incrementAndGet(); return "loaded"; });

        assertEquals("existing", result);
        assertEquals(0, calls.get(), "loader must not be invoked on a cache hit");
    }

    @Test
    void loaderResultIsCachedForSubsequentGets() {
        AtomicInteger calls = new AtomicInteger(0);

        cache.get("key", k -> { calls.incrementAndGet(); return "loaded"; });
        cache.get("key", k -> { calls.incrementAndGet(); return "loaded"; });

        assertEquals(1, calls.get(), "loader must only be called once — result is cached after first miss");
    }

    // -------------------------------------------------------------------------
    // Exception handling
    // -------------------------------------------------------------------------

    @Test
    void loaderThrowsRuntimeExceptionIsPropagatedAsCacheLoadException() {
        assertThrows(CacheLoadException.class, () ->
            cache.get("key", k -> { throw new RuntimeException("backend failure"); })
        );
    }

    @Test
    void cacheLoadExceptionFromLoaderIsNotDoubleWrapped() {
        var original = new CacheLoadException("key", new RuntimeException("cause"));

        var thrown = assertThrows(CacheLoadException.class, () ->
            cache.get("key", k -> { throw original; })
        );

        assertSame(original, thrown, "CacheLoadException must pass through unwrapped");
    }

    @Test
    void afterLoaderFailureCacheIsEmptyAndNextCallRetries() {
        AtomicInteger calls = new AtomicInteger(0);

        assertThrows(CacheLoadException.class, () ->
            cache.get("key", k -> { throw new RuntimeException("fail"); })
        );

        // second call should retry the loader, not return a cached exception
        String result = cache.get("key", k -> { calls.incrementAndGet(); return "recovered"; });

        assertEquals("recovered", result);
        assertEquals(1, calls.get());
    }

    // -------------------------------------------------------------------------
    // getOrLoad
    // -------------------------------------------------------------------------

    @Test
    void getOrLoadUsesConfiguredLoaderOnMiss() {
        try (var c = CacheBuilder.<String, String>newBuilder()
                .maxSize(10)
                .loader(k -> "loaded-" + k)
                .buildLoadingCache()) {
            assertEquals("loaded-key", c.getOrLoad("key"));
        }
    }

    @Test
    void getOrLoadThrowsIllegalStateWhenNoLoaderConfigured() {
        assertThrows(IllegalStateException.class, () -> cache.getOrLoad("key"));
    }

    // -------------------------------------------------------------------------
    // refresh
    // -------------------------------------------------------------------------

    @Test
    void refreshForcesReloadEvenWhenValueIsPresent() {
        AtomicInteger calls = new AtomicInteger(0);

        try (var c = CacheBuilder.<String, String>newBuilder()
                .maxSize(10)
                .loader(k -> "v" + calls.incrementAndGet())
                .buildLoadingCache()) {

            c.put("key", "initial");
            assertEquals("initial", c.get("key"));

            c.refresh("key");

            assertEquals(1, calls.get(), "loader must be called once by refresh");
            assertEquals("v1", c.get("key"), "cache must hold the refreshed value");
        }
    }

    @Test
    void refreshThrowsIllegalStateWhenNoLoaderConfigured() {
        assertThrows(IllegalStateException.class, () -> cache.refresh("key"));
    }

    // -------------------------------------------------------------------------
    // Null-key guards
    // -------------------------------------------------------------------------

    @Test
    void nullKeyThrowsNpe() {
        assertThrows(NullPointerException.class, () -> cache.get(null, k -> "v"));
        assertThrows(NullPointerException.class, () -> cache.getOrLoad(null));
        assertThrows(NullPointerException.class, () -> cache.refresh(null));
    }
}
