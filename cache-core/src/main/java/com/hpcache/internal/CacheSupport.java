package com.hpcache.internal;

import org.slf4j.Logger;

import com.hpcache.api.RemovalCause;
import com.hpcache.api.RemovalListener;

/** Shared helpers for cache implementations. */
public final class CacheSupport {

    private CacheSupport() {}

    /**
     * Throws {@link NullPointerException} if {@code key} is {@code null}.
     *
     * @param key the value to check
     * @throws NullPointerException if {@code key} is null
     */
    public static void rejectNullKey(Object key) {
        if (key == null) throw new NullPointerException("key must not be null");
    }

    /**
     * Delivers a removal notification to {@code listener} if it is non-null, swallowing
     * and logging any exception the listener throws.
     *
     * <p>Listener exceptions must not propagate — doing so would corrupt the lock state of
     * the calling cache method. All exceptions are caught and logged at {@code WARN} level.
     *
     * @param listener the callback to invoke; a {@code null} listener is silently skipped
     * @param log      logger used to record listener exceptions
     * @param key      the key being removed
     * @param value    the value that was associated with {@code key}
     * @param cause    the reason for removal
     */
    public static <K, V> void notifyRemoval(
            RemovalListener<K, V> listener, Logger log, K key, V value, RemovalCause cause) {
        if (listener != null) {
            try {
                listener.onRemoval(key, value, cause);
            } catch (Exception e) {
                log.warn("RemovalListener threw for key {}", key, e);
            }
        }
    }
}
