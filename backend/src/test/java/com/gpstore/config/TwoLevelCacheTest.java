package com.gpstore.config;

import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;

import static org.junit.jupiter.api.Assertions.*;

class TwoLevelCacheTest {

    @Test
    void missOnL1ReadsL2AndFillsL1() {
        Cache l1 = new ConcurrentMapCache("l1");
        Cache l2 = new ConcurrentMapCache("l2");
        l2.put("k", "from-redis");
        TwoLevelCache cache = new TwoLevelCache("products", l1, l2);

        assertEquals("from-redis", cache.get("k", String.class));
        assertEquals("from-redis", l1.get("k", String.class));
    }

    @Test
    void hitOnL1DoesNotNeedL2() {
        Cache l1 = new ConcurrentMapCache("l1");
        Cache l2 = new ConcurrentMapCache("l2");
        l1.put("k", "local");
        l2.put("k", "stale-redis");
        TwoLevelCache cache = new TwoLevelCache("products", l1, l2);

        assertEquals("local", cache.get("k", String.class));
    }

    @Test
    void evictClearsBothLayers() {
        Cache l1 = new ConcurrentMapCache("l1");
        Cache l2 = new ConcurrentMapCache("l2");
        TwoLevelCache cache = new TwoLevelCache("products", l1, l2);
        cache.put("k", "v");
        cache.evict("k");

        assertNull(l1.get("k"));
        assertNull(l2.get("k"));
    }
}
