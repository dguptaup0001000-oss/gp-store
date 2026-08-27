package com.gpstore.config;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-local L1/L2 counters for {@link TwoLevelCache}. Not Redis INFO:
 * that would add a round trip to every {@code /health/runtime} scrape.
 */
@Component
public class CacheHitStats {

    private final AtomicLong l1Hits = new AtomicLong();
    private final AtomicLong l2Loads = new AtomicLong();

    void recordL1Hit() {
        l1Hits.incrementAndGet();
    }

    void recordLoad() {
        l2Loads.incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        long hits = l1Hits.get();
        long loads = l2Loads.get();
        long total = hits + loads;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cacheL1Hits", hits);
        body.put("cacheLoads", loads);
        body.put("cacheHitRate", total == 0 ? 0.0 : (double) hits / (double) total);
        return body;
    }
}
