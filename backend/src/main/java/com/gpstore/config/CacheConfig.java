package com.gpstore.config;

import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the cache a cache again: an optimisation that can fail, rather than a
 * dependency that can take the site down.
 *
 * Spring's default is SimpleCacheErrorHandler, which RETHROWS anything Redis
 * raises. With spring.data.redis.timeout=2000, that means a Redis instance
 * that is merely slow turns every @Cacheable browse request into a 500 -
 * even though the database is healthy and could have answered. The cache
 * exists to reduce load, so a cache problem must degrade to "do the work"
 * and never to "fail the request".
 *
 * TWO FAILURE MODES THIS COVERS, both of which are real here:
 *
 * 1. Redis unreachable or slow. Every browse endpoint is @Cacheable, so
 *    without this the whole catalogue is unavailable whenever Redis is.
 *
 * 2. Deserialisation of a stale entry. ProductResponse and friends are
 *    cached with plain JDK serialization, and none of them declared a
 *    serialVersionUID - so the JVM derived one from the class structure and
 *    ADDING A SINGLE FIELD invalidated every entry already in Redis. On
 *    deploy, those entries raise InvalidClassException on read, which the
 *    default handler rethrows as a 500 on every browse request until the
 *    10-minute TTL drains them. An explicit serialVersionUID (added
 *    alongside this) fixes the cause; this handler makes the symptom
 *    survivable rather than an outage.
 *
 * GET failures fall through to the method, so the request is served from the
 * database. PUT/EVICT failures are logged and swallowed: a failed write
 * means a future miss, and a failed evict is bounded by the TTL - neither is
 * worth failing a request the application already computed correctly.
 *
 * Deliberately NOT swallowed anywhere: nothing about correctness of orders,
 * payments or inventory passes through this class. It only affects cached
 * READ paths for catalogue data.
 */
@Configuration
public class CacheConfig implements CachingConfigurer {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CacheConfig.class);

    @Override
    @Bean
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {

            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                // Warn, not error: this is the expected, survivable path
                // during a Redis blip or the deploy after a DTO change. The
                // caller now does the real work instead.
                log.warn("Cache GET failed on '{}' key '{}' - serving from source instead: {}",
                        cache.getName(), key, exception.toString());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                log.warn("Cache PUT failed on '{}' key '{}' - the response is still correct, "
                        + "the next read will just miss: {}", cache.getName(), key, exception.toString());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                // Louder than the others on purpose: a failed evict is the
                // one case that can serve STALE data (an edited price, a
                // deactivated product) rather than merely slow data. Bounded
                // by spring.cache.redis.time-to-live, but worth seeing.
                log.error("Cache EVICT failed on '{}' key '{}' - stale entries may be served until TTL expiry: {}",
                        cache.getName(), key, exception.toString());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.error("Cache CLEAR failed on '{}' - stale entries may be served until TTL expiry: {}",
                        cache.getName(), exception.toString());
            }
        };
    }
}
