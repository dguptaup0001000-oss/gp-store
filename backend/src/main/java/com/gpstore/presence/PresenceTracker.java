package com.gpstore.presence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * How many distinct people are using the shop right now.
 *
 * WHAT "CONCURRENT" MEANS HERE, because the word is meaningless unspecified:
 * the number of distinct signed-in accounts that made at least one request in
 * the last {@code presence.window-seconds} (default 300). It is a rolling
 * window, not a count of open sockets - a shopper reading a product page has
 * no request in flight but is obviously still using the shop, while a count
 * of TCP connections would report Tomcat's keep-alive pool and call it people.
 *
 * WHY A SORTED SET. One key holds {@code userId -> last-seen epoch second}.
 * Recording a visit is a single ZADD, which is O(log n) and idempotent per
 * user - the tenth request in a minute overwrites the score rather than
 * inflating a counter, so the number counts PEOPLE and not traffic. Reading is
 * one ZCOUNT over the window. The alternative - a key per user plus SCAN to
 * count them - is O(keyspace) per read and would get slower as the shop grows,
 * on an endpoint an admin dashboard polls.
 *
 * Stale members are trimmed on read rather than by a scheduled job: the set is
 * only ever read by the admin dashboard, so trimming there costs nothing when
 * nobody is looking and keeps the key from growing without bound.
 *
 * ANONYMOUS TRAFFIC IS NOT COUNTED. Only authenticated requests carry an
 * identity that can be de-duplicated; counting anonymous browsers by IP would
 * fold an entire mobile carrier's NAT gateway into one "user" and, worse,
 * would mean the number changed meaning depending on how customers happened to
 * be connected. An honest smaller number beats an impressive incoherent one.
 *
 * DEGRADES TO UNKNOWN, NOT TO ZERO. If Redis is unavailable the count is
 * absent rather than 0, because "0 people are shopping" and "I cannot tell you
 * how many people are shopping" are different statements and a shopkeeper acts
 * differently on each.
 */
@Component
public class PresenceTracker {

    private static final Logger log = LoggerFactory.getLogger(PresenceTracker.class);

    /** One key, shared by every backend instance, so the count is shop-wide. */
    static final String KEY = "presence:online";

    private final StringRedisTemplate redis;
    private final Duration window;

    public PresenceTracker(
            StringRedisTemplate redis,
            @Value("${presence.window-seconds:300}") long windowSeconds) {
        this.redis = redis;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    /**
     * Records that {@code userId} is active now. Called on every authenticated
     * request, so it must stay cheap and must never throw into the filter
     * chain: failing to record presence is not a reason to fail a customer's
     * request.
     */
    public void recordSeen(long userId) {
        try {
            long now = Instant.now().getEpochSecond();
            redis.opsForZSet().add(KEY, Long.toString(userId), now);
            // Refreshed on every write. Without it the key would live forever
            // in a shop that went quiet, and Redis would hold the last set of
            // shoppers indefinitely.
            redis.expire(KEY, window.toSeconds() * 2, TimeUnit.SECONDS);
        } catch (RuntimeException ex) {
            // DEBUG, not WARN: a Redis blip here costs a slightly low count on
            // an admin panel, and logging it per request would turn one outage
            // into a log flood on the hot path.
            log.debug("Could not record presence for user {}", userId, ex);
        }
    }

    /**
     * Distinct accounts active within the window, or empty when the count
     * cannot be determined.
     */
    public PresenceSnapshot snapshot() {
        try {
            long now = Instant.now().getEpochSecond();
            long cutoff = now - window.toSeconds();

            // Trim first so the count below cannot include people who left.
            redis.opsForZSet().removeRangeByScore(KEY, Double.NEGATIVE_INFINITY, cutoff);
            Long count = redis.opsForZSet().count(KEY, cutoff, Double.POSITIVE_INFINITY);

            return PresenceSnapshot.of(count == null ? 0 : count.intValue(), window);
        } catch (RuntimeException ex) {
            log.warn("Presence count unavailable: {}", ex.toString());
            return PresenceSnapshot.unavailable(window);
        }
    }
}
