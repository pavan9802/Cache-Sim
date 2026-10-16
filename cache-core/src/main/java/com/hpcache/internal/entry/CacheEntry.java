package com.hpcache.internal.entry;

public record CacheEntry<V>(V value, long expiryNanos) {
    public boolean isExpired() {
        return expiryNanos != Long.MAX_VALUE && System.nanoTime() > expiryNanos;
    }

    public static <V> CacheEntry<V> noExpiry(V value) {
        return new CacheEntry<>(value, Long.MAX_VALUE);
    }

    public static <V> CacheEntry<V> withTtl(V value, long ttlMs) {
        return new CacheEntry<>(value, System.nanoTime() + ttlMs * 1_000_000L);
    }

    /** Returns a {@code withTtl} entry when {@code defaultTtlMs > 0}, otherwise {@code noExpiry}. */
    public static <V> CacheEntry<V> withDefaultTtl(V value, long defaultTtlMs) {
        return defaultTtlMs > 0 ? withTtl(value, defaultTtlMs) : noExpiry(value);
    }
}
