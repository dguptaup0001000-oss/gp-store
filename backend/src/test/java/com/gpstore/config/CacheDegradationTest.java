package com.gpstore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.support.NoOpCache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The point of CacheConfig is that a cache failure must NOT become a request
 * failure. That is only true if every one of the four handler methods
 * swallows, so each is asserted by name rather than trusting one
 * representative case - a single rethrowing method is enough to take the
 * catalogue down when Redis is unhappy.
 *
 * RuntimeException stands in for the two real failures: a Lettuce timeout
 * when Redis is slow, and InvalidClassException when a DTO gained a field
 * while stale entries were still in Redis.
 */
class CacheDegradationTest {

    private final CacheErrorHandler handler = new CacheConfig().errorHandler();
    private final Cache cache = new NoOpCache("products");

    @Test
    @DisplayName("A cache GET failure is swallowed so the request falls through to the database")
    void getFailureDoesNotPropagate() {
        assertNotNull(handler);
        assertDoesNotThrow(() -> handler.handleCacheGetError(
                new RuntimeException("Redis command timed out after 2000ms"), cache, "key"));
        // The InvalidClassException case, which is what a DTO field addition
        // produces against entries written by the previous build.
        assertDoesNotThrow(() -> handler.handleCacheGetError(
                new IllegalArgumentException(
                        new java.io.InvalidClassException("ProductResponse", "local class incompatible")),
                cache, "key"));
    }

    @Test
    @DisplayName("A cache PUT failure is swallowed - the response was already computed correctly")
    void putFailureDoesNotPropagate() {
        assertDoesNotThrow(() -> handler.handleCachePutError(
                new RuntimeException("Redis unreachable"), cache, "key", "value"));
    }

    @Test
    @DisplayName("A cache EVICT failure is swallowed - staleness is bounded by TTL, an outage is not")
    void evictFailureDoesNotPropagate() {
        assertDoesNotThrow(() -> handler.handleCacheEvictError(
                new RuntimeException("Redis unreachable"), cache, "key"));
    }

    @Test
    @DisplayName("A cache CLEAR failure is swallowed")
    void clearFailureDoesNotPropagate() {
        assertDoesNotThrow(() -> handler.handleCacheClearError(
                new RuntimeException("Redis unreachable"), cache));
    }
}
