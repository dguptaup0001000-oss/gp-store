package com.gpstore.store;

import com.gpstore.store.hours.ShopHours;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
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
 *
 * <p>THE HOURS ARE THE SHOP'S, and they arrive as a {@link ShopHours} value
 * rather than being read off the deployment configuration. Everything else
 * here is unchanged, because everything else was already a function of "when
 * is the run" - it simply used to be one answer for the whole marketplace.
 *
 * <p>A DAY IS A LIST OF SESSIONS, not a single run. A shop that shuts for
 * lunch has two, and the difference shows up in exactly one place: the scan
 * below walks sessions within a day before moving to the next day, so an
 * order placed at 14:00 at a shop closed 13:00-16:00 is scheduled for that
 * afternoon's run rather than being called same-day against a van that is
 * parked. A shop with one session behaves identically to before, because one
 * session is what the deployment default is.
 */
public final class DeliverySchedule {

    private final StoreScheduleProperties properties;
    private final ShopHours hours;
    private final Predicate<LocalDate> closed;

    /**
     * @param properties the deployment-wide parts of the schedule that are not
     *                   a shop's trading hours: how far ahead to look for an
     *                   open day, how long the closing countdown runs, and what
     *                   time the morning preparation list is for
     * @param hours      THIS SHOP's trading hours
     * @param closed     whether a given shop-local date is a full-day closure.
     *                   Taken as a predicate rather than a repository so this
     *                   class stays free of persistence; the service passes an
     *                   in-memory set it has already loaded, which also means
     *                   one query answers a whole lookahead scan rather than
     *                   one query per day.
     */
    public DeliverySchedule(StoreScheduleProperties properties, ShopHours hours,
                            Predicate<LocalDate> closed) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.hours = Objects.requireNonNull(hours, "hours");
        this.closed = Objects.requireNonNull(closed, "closed");
    }

    /** The shop's own zone. Every local time in this class is in it. */
    public ZoneId zone() {
        return hours.zone();
    }

    /** The shop-local date at {@code at}. */
    public LocalDate localDate(Instant at) {
        return at.atZone(hours.zone()).toLocalDate();
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
        LocalDate today = localDate(at);

        for (int offset = 0; offset <= properties.getMaxClosureLookaheadDays(); offset++) {
            LocalDate date = today.plusDays(offset);
            if (closed.test(date)) {
                continue;
            }
            // SESSIONS WITHIN THE DAY, in order. For a shop with one session
            // this is the loop that was here before; for a shop that shuts for
            // lunch it is what makes 14:00 find the afternoon run rather than
            // reporting that a parked van is out.
            for (DeliveryWindow window : windowsOn(date)) {
                // A run is only a candidate while it has not finished. Any
                // later date's run is entirely in the future by construction.
                if (window.end().isAfter(at)) {
                    return window;
                }
            }
        }
        return null;
    }

    /**
     * The shop's runs on a given shop-local date, earliest first.
     *
     * Empty when the shop does not trade that weekday. Full-day closures are
     * NOT applied here - they sit over the top, in the scan above - so this
     * answers "what are this date's hours" rather than "is the shop shut",
     * which is the distinction store_closures exists to keep.
     */
    public List<DeliveryWindow> windowsOn(LocalDate date) {
        ZoneId zone = hours.zone();
        List<ShopHours.OpenPeriod> sessions = hours.on(date);
        List<DeliveryWindow> windows = new ArrayList<>(sessions.size());
        for (ShopHours.OpenPeriod session : sessions) {
            windows.add(new DeliveryWindow(
                    date,
                    // ZonedDateTime.of resolves a local time that does not exist
                    // (a spring-forward gap) forward rather than throwing, and an
                    // ambiguous one to the earlier offset. Asia/Kolkata has no
                    // DST so neither arises today; a shop in a zone that does gets
                    // a defined answer instead of an exception on the order path.
                    ZonedDateTime.of(date, session.opensAt(), zone).toInstant(),
                    ZonedDateTime.of(date, session.closesAt(), zone).toInstant(),
                    ZonedDateTime.of(date, properties.getMorningPreparation(), zone).toInstant()));
        }
        return windows;
    }

    /**
     * The first run of a given shop-local date, or null if the shop does not
     * trade that day.
     *
     * WHY THE FIRST AND NOT THE WHOLE DAY. Its one production caller is the
     * morning preparation list - "what has to be packed for that day's run" -
     * and packing is done before the shop opens, so the run it means is the
     * first one. A shop with a single session, which is every shop until one
     * says otherwise, is unaffected either way.
     */
    public DeliveryWindow windowOn(LocalDate date) {
        List<DeliveryWindow> windows = windowsOn(date);
        return windows.isEmpty() ? null : windows.get(0);
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

    /**
     * Everything above, computed once, for the status endpoint.
     *
     * @param acceptance  the switch AS IT STANDS AT {@code at} - the caller has
     *                    already lifted an expired pause, because only the
     *                    caller has the row that recorded one
     * @param pausedUntil when the shop said it would be back, or null
     */
    public StoreStatus status(Instant at, StoreOrderAcceptance acceptance, String closureReason,
                              java.time.LocalDateTime pausedUntil) {
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
                remaining,
                pausedUntil);
    }
}
