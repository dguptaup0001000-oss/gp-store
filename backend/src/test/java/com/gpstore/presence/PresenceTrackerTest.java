package com.gpstore.presence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The concurrent-user number, and the two ways a number like this normally
 * lies to the person reading it.
 *
 * FIRST LIE: counting traffic and calling it people. A shopper refreshing a
 * category page ten times is one shopper. A counter would say ten, and the
 * dashboard would read "busy" on the strength of one bored customer.
 *
 * SECOND LIE: reporting zero when the truth is "I don't know". A shopkeeper
 * seeing 0 online concludes the shop is empty and may go and do something
 * else; seeing "unavailable" they conclude the dashboard is broken. Those
 * lead to different actions, so a Redis outage must not be dressed up as an
 * empty shop.
 */
@DisplayName("Presence counts people, and admits when it cannot")
class PresenceTrackerTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ZSetOperations<String, String> zset = mock(ZSetOperations.class);

    private PresenceTracker tracker(long windowSeconds) {
        when(redis.opsForZSet()).thenReturn(zset);
        return new PresenceTracker(redis, windowSeconds);
    }

    @Test
    @DisplayName("a visit is one ZADD per user, so repeat requests do not inflate it")
    void repeatRequestsDoNotInflateTheCount() {
        PresenceTracker t = tracker(300);

        t.recordSeen(42L);
        t.recordSeen(42L);
        t.recordSeen(42L);

        // Three requests, three ZADDs of the SAME member - the score is
        // overwritten each time, so the set still holds one person. A counter
        // (INCR) here would have reported three shoppers where there is one.
        verify(zset, times(3)).add(eq(PresenceTracker.KEY), eq("42"), anyDouble());
    }

    @Test
    @DisplayName("the count excludes anyone last seen before the window")
    void staleMembersAreTrimmedBeforeCounting() {
        PresenceTracker t = tracker(300);
        when(zset.count(eq(PresenceTracker.KEY), anyDouble(), anyDouble())).thenReturn(7L);

        PresenceSnapshot snapshot = t.snapshot();

        assertTrue(snapshot.available());
        assertEquals(7, snapshot.onlineNow());
        assertEquals(300, snapshot.windowSeconds());
        // Trimming happens before counting, not on a timer - otherwise a
        // read could include people who left an hour ago.
        verify(zset).removeRangeByScore(eq(PresenceTracker.KEY), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("a Redis outage reports unavailable, NOT zero")
    void outageIsNotAnEmptyShop() {
        PresenceTracker t = tracker(300);
        when(zset.count(eq(PresenceTracker.KEY), anyDouble(), anyDouble()))
                .thenThrow(new RedisConnectionFailureException("down"));

        PresenceSnapshot snapshot = t.snapshot();

        assertFalse(snapshot.available());
        assertNull(snapshot.onlineNow(),
                "0 means the shop is empty; null means we cannot tell. They are not the same.");
    }

    @Test
    @DisplayName("recording presence never throws into the request that triggered it")
    void recordingNeverBreaksTheRequest() {
        PresenceTracker t = tracker(300);
        doThrow(new RedisConnectionFailureException("down"))
                .when(zset).add(anyString(), anyString(), anyDouble());

        // A customer's checkout must not fail because a dashboard statistic
        // could not be written.
        assertDoesNotThrow(() -> t.recordSeen(1L));
    }

    @Test
    @DisplayName("the window travels with the number")
    void windowIsReported() {
        PresenceTracker t = tracker(60);
        when(zset.count(eq(PresenceTracker.KEY), anyDouble(), anyDouble())).thenReturn(3L);

        // "3 online" is not a fact until you know over what period.
        assertEquals(60, t.snapshot().windowSeconds());
    }

    @Test
    @DisplayName("an empty set is a real zero, not an unavailable reading")
    void emptyShopIsReportedAsZero() {
        PresenceTracker t = tracker(300);
        when(zset.count(eq(PresenceTracker.KEY), anyDouble(), anyDouble())).thenReturn(0L);

        PresenceSnapshot snapshot = t.snapshot();

        assertTrue(snapshot.available());
        assertEquals(0, snapshot.onlineNow());
    }
}
