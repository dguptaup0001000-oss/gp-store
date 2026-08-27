package com.gpstore.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

@Configuration
public class TwoLevelCacheConfiguration {

    /**
     * L1 lives only in this process (15s). L2 remains Redis so a second
     * instance still shares catalog entries and @CacheEvict still crosses
     * instances. Do not replace Redis with Caffeine-only: that is the
     * split-brain the Redis cache was introduced to fix.
     */
    @Bean
    @Primary
    public CacheManager cacheManager(
            RedisConnectionFactory redisConnectionFactory,
            CacheHitStats cacheHitStats,
            @Value("${CACHE_TTL_MS:600000}") long redisTtlMs,
            @Value("${cache.l1-ttl-seconds:15}") long l1TtlSeconds,
            @Value("${cache.l1-max-entries:2000}") int l1MaxEntries) {

        RedisCacheManager redis = RedisCacheManager.builder(
                        RedisCacheWriter.nonLockingRedisCacheWriter(redisConnectionFactory))
                .cacheDefaults(RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(Duration.ofMillis(redisTtlMs)))
                .build();

        CaffeineCacheManager local = new CaffeineCacheManager();
        local.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(l1TtlSeconds, TimeUnit.SECONDS)
                .maximumSize(l1MaxEntries)
                .recordStats());
        local.setAllowNullValues(false);

        return new CacheManager() {
            @Override
            @Nullable
            public Cache getCache(String name) {
                Cache l2 = redis.getCache(name);
                Cache l1 = local.getCache(name);
                if (l1 == null || l2 == null) {
                    return l2;
                }
                return new TwoLevelCache(name, l1, l2, cacheHitStats);
            }

            @Override
            public Collection<String> getCacheNames() {
                return redis.getCacheNames();
            }
        };
    }
}
