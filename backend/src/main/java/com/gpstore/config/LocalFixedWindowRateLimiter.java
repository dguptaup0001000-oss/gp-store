package com.gpstore.config;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process fallback for security-sensitive rate limits when Redis is down.
 *
 * This is NOT a substitute for Redis in a multi-instance deployment: each
 * JVM has its own counters, so the real ceiling is (limit × instances).
 * It exists so a Redis outage does not silently disable brute-force
 * protection on login, checkout and admin writes. Availability-critical
 * reads (search, catalog) must not use this class - they fail open.
 *
 * Windows are aligned to the epoch so a process restart does not reset
 * the clock to "now" in a way that is surprising, and keys are pruned
 * when the map grows so a long outage cannot leak memory unbounded.
 */
final class LocalFixedWindowRateLimiter {

    private record Window(long windowStartMs, AtomicInteger count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final long windowMs;
    private static final int PRUNE_ABOVE = 10_000;

    LocalFixedWindowRateLimiter(long windowMs) {
        this.windowMs = windowMs;
    }

    /**
     * Records one hit and returns whether it is still under {@code limit}.
     * Thread-safe per key.
     */
    boolean allow(String key, int limit) {
        long now = System.currentTimeMillis();
        long windowStart = now - (now % windowMs);
        Window window = windows.compute(key, (k, existing) -> {
            if (existing == null || existing.windowStartMs != windowStart) {
                return new Window(windowStart, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });
        if (windows.size() > PRUNE_ABOVE) {
            prune(windowStart);
        }
        return window.count.get() <= limit;
    }

    private void prune(long currentWindowStartMs) {
        windows.entrySet().removeIf(e -> e.getValue().windowStartMs < currentWindowStartMs);
    }
}
