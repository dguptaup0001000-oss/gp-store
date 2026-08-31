package com.gpstore.store;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One day's delivery run: the date it belongs to and the instants it spans.
 *
 * <p>CARRIES BOTH A LOCAL DATE AND ABSOLUTE INSTANTS ON PURPOSE. The date is
 * what a customer is told ("arriving Tuesday") and what the morning
 * preparation list groups by; the instants are what comparisons are done
 * against, because comparing a LocalDateTime computed in the shop's zone with
 * one produced by a UTC server is the exact bug this whole package exists to
 * prevent.
 *
 * @param date        the shop-local day this run belongs to
 * @param start       when the vans start, as an absolute instant
 * @param end         when the vans stop, as an absolute instant
 * @param preparation when staff start packing for this run
 */
public record DeliveryWindow(LocalDate date, Instant start, Instant end, Instant preparation) {

    /** Whether {@code at} falls inside the run: start inclusive, end exclusive. */
    public boolean contains(Instant at) {
        return !at.isBefore(start) && at.isBefore(end);
    }

    /** The shop-local wall-clock time the run starts, for display. */
    public LocalTime startTime(java.time.ZoneId zone) {
        return start.atZone(zone).toLocalTime();
    }
}
