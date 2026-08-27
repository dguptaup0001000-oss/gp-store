package com.gpstore.config;

import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TwoLevelCacheTest {

    @Test
    void missOnL1ReadsL2AndFillsL1() {
        Cache l1 = new ConcurrentMapCache("l1");
        Cache l2 = new ConcurrentMapCache("l2");
        l2.put("k", "from-redis");
        TwoLevelCache cache = new TwoLevelCache("products", l1, l2, new CacheHitStats());

        assertEquals("from-redis", cache.get("k", String.class));
        assertEquals("from-redis", l1.get("k", String.class));
    }

    @Test
    void hitOnL1DoesNotNeedL2() {
        Cache l1 = new ConcurrentMapCache("l1");
        Cache l2 = new ConcurrentMapCache("l2");
        l1.put("k", "local");
        l2.put("k", "stale-redis");
        TwoLevelCache cache = new TwoLevelCache("products", l1, l2, new CacheHitStats());

        assertEquals("local", cache.get("k", String.class));
    }

    @Test
    void evictClearsBothLayers() {
        Cache l1 = new ConcurrentMapCache("l1");
        Cache l2 = new ConcurrentMapCache("l2");
        TwoLevelCache cache = new TwoLevelCache("products", l1, l2, new CacheHitStats());
        cache.put("k", "v");
        cache.evict("k");

        assertNull(l1.get("k"));
        assertNull(l2.get("k"));
    }

    @Test
    void getWithLoaderOnlyRunsOnceUnderStampede() throws Exception {
        Cache l1 = new ConcurrentMapCache("l1");
        Cache l2 = new ConcurrentMapCache("l2");
        TwoLevelCache cache = new TwoLevelCache("products", l1, l2, new CacheHitStats());

        AtomicInteger loads = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<String>> futures = new ArrayList<>();
        Callable<String> loader = () -> {
            loads.incrementAndGet();
            Thread.sleep(40);
            return "loaded";
        };
        for (int i = 0; i < 16; i++) {
            futures.add(pool.submit(() -> {
                start.await(2, TimeUnit.SECONDS);
                return cache.get("k", loader);
            }));
        }
        start.countDown();
        for (Future<String> future : futures) {
            assertEquals("loaded", future.get(5, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertEquals(1, loads.get(),
                "Concurrent misses must share one loader call, not rebuild the catalog 16 times");
        assertEquals("loaded", l1.get("k", String.class));
    }
}
