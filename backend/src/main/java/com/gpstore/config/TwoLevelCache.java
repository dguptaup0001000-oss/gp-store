package com.gpstore.config;

import org.springframework.cache.Cache;
import org.springframework.lang.Nullable;

import java.util.concurrent.Callable;

/**
 * Process-local L1 in front of Redis L2.
 *
 * A 5,000-VU browse run was still a Redis round trip per catalog GET even
 * when the entry was hot. That kept forty Tomcat threads waiting on the
 * network instead of serving JSON, the queue overflowed, and Render answered
 * 502 because the origin never responded. L1 is the same objects Redis
 * already stores; eviction still goes to both layers so an admin edit is
 * not stuck in this JVM.
 */
final class TwoLevelCache implements Cache {

    private final String name;
    private final Cache l1;
    private final Cache l2;

    TwoLevelCache(String name, Cache l1, Cache l2) {
        this.name = name;
        this.l1 = l1;
        this.l2 = l2;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return l2.getNativeCache();
    }

    @Override
    @Nullable
    public ValueWrapper get(Object key) {
        ValueWrapper local = l1.get(key);
        if (local != null) {
            return local;
        }
        ValueWrapper remote = l2.get(key);
        if (remote != null && remote.get() != null) {
            l1.put(key, remote.get());
        }
        return remote;
    }

    @Override
    @Nullable
    public <T> T get(Object key, @Nullable Class<T> type) {
        T local = l1.get(key, type);
        if (local != null) {
            return local;
        }
        T remote = l2.get(key, type);
        if (remote != null) {
            l1.put(key, remote);
        }
        return remote;
    }

    @Override
    @Nullable
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper cached = get(key);
        if (cached != null) {
            @SuppressWarnings("unchecked")
            T value = (T) cached.get();
            return value;
        }
        try {
            T loaded = l2.get(key, valueLoader);
            if (loaded != null) {
                l1.put(key, loaded);
            }
            return loaded;
        } catch (RuntimeException ex) {
            throw ex;
        }
    }

    @Override
    public void put(Object key, @Nullable Object value) {
        l2.put(key, value);
        l1.put(key, value);
    }

    @Override
    public void evict(Object key) {
        l1.evict(key);
        l2.evict(key);
    }

    @Override
    public void clear() {
        l1.clear();
        l2.clear();
    }
}
