package com.gpstore.store.hours;

import com.gpstore.store.StoreScheduleProperties;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * When one shop is open, as a value - no Spring, no database, no clock.
 *
 * <p>WHY A VALUE OBJECT. {@link com.gpstore.store.DeliverySchedule} is a pure
 * function of (instant, hours, closed days) and its edge cases - 20:59:59 is
 * same-day, 21:00:00 is not - are worth testing by calling a method with a
 * value rather than by standing up Postgres. Making the hours a value keeps
 * that true now that they come from a table instead of a properties file.
 *
 * <p>THREE LAYERS, RESOLVED IN ONE PLACE. A date's sessions are, in order:
 * <ol>
 *   <li>an override for that exact date - "on the 14th we open late";
 *   <li>the shop's ordinary week - possibly empty, which is a weekly holiday;
 *   <li>the deployment's configured hours, for a shop that has set none.
 * </ol>
 * The third layer is the whole of the backward-compatibility story (§19): a
 * shop that has never opened the hours screen trades on exactly the
 * configuration it traded on before this class existed, and a SINGLE_SHOP
 * deployment never leaves it. It is also why {@link #isConfigured()} is a
 * distinct question from "is there a session on Sunday" - without it, a shop
 * declaring Sunday closed would be indistinguishable from a shop that has
 * said nothing, and would silently fall back to trading on Sundays.
 *
 * <p>FULL-DAY CLOSURES ARE NOT HERE. They are store_closures, they are per
 * shop, they already have a reason and an audit trail, and the schedule
 * applies them as a predicate over the top of this. Expressing a closed day
 * twice is how two places start disagreeing about whether the shop is shut.
 */
public final class ShopHours implements java.io.Serializable {

    /**
     * Pinned so this type can gain fields without invalidating entries already
     * sitting in Redis - see ProductResponse for the failure that argument
     * comes from.
     */
    private static final long serialVersionUID = 1L;

    /** One stretch of a day the shop is open. Never spans midnight - see V55. */
    public record OpenPeriod(LocalTime opensAt, LocalTime closesAt)
            implements java.io.Serializable {
        public OpenPeriod {
            if (opensAt == null || closesAt == null || !closesAt.isAfter(opensAt)) {
                throw new IllegalArgumentException(
                        "A trading session has to end after it starts: " + opensAt + "-" + closesAt);
            }
        }
    }

    private final ZoneId zone;
    private final Map<DayOfWeek, List<OpenPeriod>> weekly;
    private final Map<LocalDate, List<OpenPeriod>> overrides;
    private final List<OpenPeriod> deploymentDefault;

    private ShopHours(ZoneId zone,
                      Map<DayOfWeek, List<OpenPeriod>> weekly,
                      Map<LocalDate, List<OpenPeriod>> overrides,
                      List<OpenPeriod> deploymentDefault) {
        this.zone = zone;
        this.weekly = weekly;
        this.overrides = overrides;
        this.deploymentDefault = deploymentDefault;
    }

    /**
     * The hours every shop had before shops had hours: one session a day,
     * every day, from the deployment configuration.
     */
    public static ShopHours deploymentDefault(StoreScheduleProperties properties) {
        return new ShopHours(properties.getZone(), Map.of(), Map.of(),
                List.of(new OpenPeriod(properties.getDeliveryStart(), properties.getDeliveryEnd())));
    }

    /**
     * One shop's own hours, over the deployment's as a fallback.
     *
     * @param zone     the shop's zone, or the deployment's when it has none
     * @param weekly   sessions by weekday; EMPTY MAP means "not configured",
     *                 an entry with an empty list means "closed that weekday"
     * @param overrides sessions that replace a specific date's weekday
     */
    public static ShopHours of(ZoneId zone,
                               Map<DayOfWeek, List<OpenPeriod>> weekly,
                               Map<LocalDate, List<OpenPeriod>> overrides,
                               StoreScheduleProperties properties) {
        return new ShopHours(
                zone == null ? properties.getZone() : zone,
                weekly == null ? Map.of() : Map.copyOf(weekly),
                overrides == null ? Map.of() : Map.copyOf(overrides),
                List.of(new OpenPeriod(properties.getDeliveryStart(), properties.getDeliveryEnd())));
    }

    /** The shop's own zone. Customers' phones are irrelevant; the shop's clock rules. */
    public ZoneId zone() {
        return zone;
    }

    /**
     * Whether this shop has said anything about its own week.
     *
     * False means every day falls back to the deployment's hours. It is not
     * the same as "open seven days": a configured shop with no Sunday
     * sessions is shut on Sundays, and must not fall back into trading.
     */
    public boolean isConfigured() {
        return !weekly.isEmpty();
    }

    /**
     * The stretches this shop is open on one date, earliest first.
     *
     * An empty list means the shop does not trade that day at all - a weekly
     * holiday, or an override that removed every session.
     */
    public List<OpenPeriod> on(LocalDate date) {
        List<OpenPeriod> forDate = overrides.get(date);
        if (forDate != null) {
            return sorted(forDate);
        }
        if (!isConfigured()) {
            return deploymentDefault;
        }
        return sorted(weekly.getOrDefault(date.getDayOfWeek(), List.of()));
    }

    /** Whether the shop trades at all on that date, before closures are applied. */
    public boolean tradesOn(LocalDate date) {
        return !on(date).isEmpty();
    }

    private static List<OpenPeriod> sorted(List<OpenPeriod> periods) {
        if (periods.size() < 2) {
            return List.copyOf(periods);
        }
        List<OpenPeriod> copy = new ArrayList<>(periods);
        copy.sort(Comparator.comparing(OpenPeriod::opensAt));
        return List.copyOf(copy);
    }
}
