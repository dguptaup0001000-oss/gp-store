package com.gpstore.store;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * The scheduling rules themselves: no Spring, no database, no clock of its own.
 *
 * <p>WHY THIS IS SPLIT OUT of {@link DeliveryScheduleService}. Every question
 * this class answers has an exactly right answer at an exactly given instant -
 * 20:59:59 is same-day and 21:00:00 is not - and pinning that down should not
 * require a Postgres container and a Spring context. Everything here is a pure
 * function of (instant, configuration, which days are closed), so the edge
 * cases are tested by calling a method with a value, and the service above is
 * left with only the plumbing: where the clock comes from and where the closed
 * days are stored.
 *
 * <p>THE INSTANT IS THE INPUT, NEVER A LOCAL TIME. Callers pass an
 * {@link Instant}; this class converts into the shop's zone itself. That is
 * the single place the UTC-server problem is solved, and it cannot be bypassed
 * by a caller who reaches for LocalDateTime.now() out of habit.
 */
public final class DeliverySchedule {

    private final StoreScheduleProperties properties;
    private final Predicate<LocalDate> closed;

    /**
     * @param properties the hours, from configuration
     * @param closed     whether a given shop-local date is a full-day closure.
     *                   Taken as a predicate rather than a repository so this
     *                   class stays free of persistence; the service passes an
     *                   in-memory set it has already loaded, which also means
     *                   one query answers a whole lookahead scan rather than
     *                   one query per day.
     */
    public DeliverySchedule(StoreScheduleProperties properties, Predicate<LocalDate> closed) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.closed = Objects.requireNonNull(closed, "closed");
    }

    /** The shop-local date at {@code at}. */
    public LocalDate localDate(Instant at) {
        return at.atZone(properties.getZone()).toLocalDate();
    }

    /**
     * The next delivery run that has not finished yet, or null if the shop is
     * closed for longer than the configured lookahead.
     *
     * <p>Scans forward from the shop-local today. Today qualifies only if it
     * is an open day AND its run has not already ended - at 22:00 today's van
     * is back, so the answer is tomorrow. Note that a window whose start is
     * still in the future (an order at 07:00) is returned as today's: the
     * order is placed now and delivered at 09:00 the same date.
     */
    public DeliveryWindow nextWindow(Instant at) {
        ZoneId zone = properties.getZone();
        ZonedDateTime local = at.atZone(zone);
        LocalDate today = local.toLocalDate();

        for (int offset = 0; offset <= properties.getMaxClosureLookaheadDays(); offset++) {
            LocalDate date = today.plusDays(offset);
            if (closed.test(date)) {
                continue;
            }
            DeliveryWindow window = windowOn(date);
            // Today's run is only a candidate while it is still running. Any
            // later date's run is entirely in the future by construction.
            if (!window.end().isAfter(at)) {
                continue;
            }
            return window;
        }
        return null;
    }

    /** The fixed delivery run for a given shop-local date, open or not. */
    public DeliveryWindow windowOn(LocalDate date) {
        ZoneId zone = properties.getZone();
        return new DeliveryWindow(
                date,
                // ZonedDateTime.of resolves a local time that does not exist
                // (a spring-forward gap) forward rather than throwing, and an
                // ambiguous one to the earlier offset. Asia/Kolkata has no
                // DST so neither arises today; a shop in a zone that does gets
                // a defined answer instead of an exception on the order path.
                ZonedDateTime.of(date, properties.getDeliveryStart(), zone).toInstant(),
                ZonedDateTime.of(date, properties.getDeliveryEnd(), zone).toInstant(),
                ZonedDateTime.of(date, properties.getMorningPreparation(), zone).toInstant());
    }

    /**
     * Same-day while the vans are actually out, night otherwise.
     *
     * <p>Derived from whether {@code at} falls inside the next run, which
     * makes a full-day closure fall out for free: on a closed day there is no
     * run containing now, so 14:00 on a closure is NIGHT and the order goes to
     * the next open day - without closure needing a branch of its own.
     */
    public StoreMode mode(Instant at) {
        DeliveryWindow next = nextWindow(at);
        return next != null && next.contains(at) ? StoreMode.SAME_DAY : StoreMode.NIGHT;
    }

    /**
     * What an order placed at {@code at} would be, or null if nothing is
     * deliverable within the lookahead.
     *
     * <p>Never returns {@link DeliveryType#MANUAL_SCHEDULED}: that is a human
     * decision, not one the clock can make.
     */
    public DeliveryType deliveryType(Instant at) {
        DeliveryWindow next = nextWindow(at);
        if (next == null) {
            return null;
        }
        return next.contains(at) ? DeliveryType.SAME_DAY : DeliveryType.NEXT_MORNING;
    }

    /**
     * The date an order placed at {@code at} is to be delivered, or null.
     *
     * <p>THIS IS THE ONLY ANSWER THE SERVER ACCEPTS. A delivery date that
     * arrived in a request body is a number the customer's phone chose, and a
     * phone whose clock is a day slow - or whose owner edited the JSON - would
     * otherwise book a van for a day of its choosing.
     */
    public LocalDate deliveryDate(Instant at) {
        DeliveryWindow next = nextWindow(at);
        return next == null ? null : next.date();
    }

    /**
     * Time left of same-day ordering, or null when the warning should not show.
     *
     * <p>Null rather than ZERO when inactive, so a caller cannot render "closes
     * in 0 minutes" at nine in the morning by forgetting to check a flag.
     */
    public Duration countdownRemaining(Instant at) {
        DeliveryWindow next = nextWindow(at);
        if (next == null || !next.contains(at)) {
            return null;
        }
        Duration remaining = Duration.between(at, next.end());
        return remaining.compareTo(properties.getClosingCountdown()) <= 0 ? remaining : null;
    }

    /** Whether the closing warning is showing at {@code at}. */
    public boolean countdownActive(Instant at) {
        return countdownRemaining(at) != null;
    }

    /**
     * Whether a new order may be created, given the owner's override.
     *
     * <p>AUTO accepts whenever there is a delivery day to promise. It does NOT
     * consult the hour, and that is the entire feature: the shop takes orders
     * at 3am and delivers them at 09:00. AUTO and ON therefore agree during
     * normal operation, and differ only when the shop is closed past the
     * lookahead - AUTO stops taking orders it cannot schedule, ON keeps taking
     * them anyway for whenever the shop reopens.
     */
    public boolean acceptingOrders(Instant at, StoreOrderAcceptance acceptance) {
        if (acceptance == StoreOrderAcceptance.OFF) {
            return false;
        }
        if (acceptance == StoreOrderAcceptance.ON) {
            return true;
        }
        return nextWindow(at) != null;
    }

    /** Everything above, computed once, for the status endpoint. */
    public StoreStatus status(Instant at, StoreOrderAcceptance acceptance, String closureReason) {
        DeliveryWindow next = nextWindow(at);
        boolean sameDay = next != null && next.contains(at);
        Duration remaining = null;
        if (sameDay) {
            Duration left = Duration.between(at, next.end());
            if (left.compareTo(properties.getClosingCountdown()) <= 0) {
                remaining = left;
            }
        }
        return new StoreStatus(
                at,
                sameDay ? StoreMode.SAME_DAY : StoreMode.NIGHT,
                // Browsing is open. Unconditionally, with no branch above it.
                true,
                acceptingOrders(at, acceptance),
                acceptance,
                closed.test(localDate(at)),
                closureReason,
                next,
                next == null ? null : (sameDay ? DeliveryType.SAME_DAY : DeliveryType.NEXT_MORNING),
                remaining);
    }
}
