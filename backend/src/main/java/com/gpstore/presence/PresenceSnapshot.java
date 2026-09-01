package com.gpstore.presence;

import java.time.Duration;

/**
 * The concurrent-user reading, and enough context to read it honestly.
 *
 * {@code onlineNow} is deliberately nullable. An admin panel that renders a
 * hard 0 during a Redis outage tells the shopkeeper the shop is empty, which
 * is a different and much more alarming claim than "this number is currently
 * unavailable". The window travels with the number for the same reason: "12
 * online" means nothing until you know whether that is over five minutes or
 * five seconds.
 */
public record PresenceSnapshot(Integer onlineNow, int windowSeconds, boolean available) {

    public static PresenceSnapshot of(int count, Duration window) {
        return new PresenceSnapshot(count, (int) window.toSeconds(), true);
    }

    public static PresenceSnapshot unavailable(Duration window) {
        return new PresenceSnapshot(null, (int) window.toSeconds(), false);
    }
}
