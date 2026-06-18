package com.hpcache.impl;

import java.util.concurrent.ConcurrentHashMap;

import net.jcip.annotations.ThreadSafe;

import com.hpcache.api.Cache;

@ThreadSafe
public class ConcurrentHashMapCache<K, V> implements Cache<K, V> {

    private final ConcurrentHashMap<K, V> map = new ConcurrentHashMap<>();

    @Override
    public V get(K key) {
        return map.get(key);
    }

    @Override
    public void put(K key, V value) {
        map.put(key, value);
    }

    @Override
    public void put(K key, V value, long ttlMs) {
        map.put(key, value);
    }

    @Override
    public void invalidate(K key) {
        map.remove(key);
    }

    @Override
    public void invalidateAll() {
        map.clear();
    }

    @Override
    public boolean containsKey(K key) {
        return map.containsKey(key);
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public Object stats() {
        return null;
    }

    @Override
    public void close() {
    }
}
