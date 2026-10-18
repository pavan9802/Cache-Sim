package com.hpcache.api;

/** Thrown when a cache loader fails or is interrupted. Wraps the original cause. */
public class CacheLoadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CacheLoadException(Object key, Throwable cause) {
        super("Cache loader failed for key: " + key, cause);
    }
}
