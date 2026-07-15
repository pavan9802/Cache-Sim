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
}
