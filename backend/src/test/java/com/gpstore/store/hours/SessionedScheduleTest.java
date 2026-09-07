package com.gpstore.store.hours;

import com.gpstore.store.DeliverySchedule;
import com.gpstore.store.DeliveryType;
import com.gpstore.store.DeliveryWindow;
import com.gpstore.store.StoreMode;
import com.gpstore.store.StoreScheduleProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A DAY WITH TWO RUNS IN IT.
 *
 * <p>The kirana that shuts between one and four is the case the old schedule
 * could not describe at all: it had one start and one end for the whole
 * deployment, so a shop closed for lunch either advertised itself as open
 * through it, or shut at one and never reopened. Neither is a shop.
 *
 * <p>WHAT THE SESSIONS HAVE TO GET RIGHT is not "is there a session" - it is
 * the four questions the order path asks at 14:00, when the shutters are down
 * but the day is not over: is this same-day (no), when will it arrive (this
 * afternoon), is the closing countdown running (no), and is the shop taking
 * the order at all (yes). A single-session shop is the same shop it was
 * before, and the last case here is what says so.
 */
@DisplayName("A day with two runs in it")
class SessionedScheduleTest {

    private static final ZoneId SHOP = ZoneId.of("Asia/Kolkata");
    /** A Monday, so the weekly pattern below lands on it. */
    private static final LocalDate DAY = LocalDate.of(2026, 3, 2);

    private static StoreScheduleProperties deployment() {
        StoreScheduleProperties p = new StoreScheduleProperties();
        p.setZone("Asia/Kolkata");
        p.setDeliveryStart(LocalTime.of(9, 0));
        p.setDeliveryEnd(LocalTime.of(21, 0));
        p.setMorningPreparation(LocalTime.of(8, 0));
        p.setClosingCountdown(Duration.ofMinutes(15));
        return p;
    }

    private static ShopHours.OpenPeriod session(String opens, String closes) {
        return new ShopHours.OpenPeriod(LocalTime.parse(opens), LocalTime.parse(closes));
    }

    private static Instant at(String hhmm) {
        return ZonedDateTime.of(DAY, LocalTime.parse(hhmm), SHOP).toInstant();
    }

    /** Open 07:00-13:00 and 16:00-21:00 on Mondays; shut every other day. */
    private static DeliverySchedule lunchBreakShop() {
        StoreScheduleProperties properties = deployment();
        ShopHours hours = ShopHours.of(SHOP,
                Map.of(DayOfWeek.MONDAY, List.of(session("07:00", "13:00"),
                        session("16:00", "21:00"))),
                Map.of(), properties);
        return new DeliverySchedule(properties, hours, date -> false);
    }

    @Test
    @DisplayName("the morning run is same-day while it is running")
    void duringTheMorningSession() {
        DeliverySchedule schedule = lunchBreakShop();
        assertEquals(StoreMode.SAME_DAY, schedule.mode(at("09:00")));
        assertEquals(DeliveryType.SAME_DAY, schedule.deliveryType(at("09:00")));
        assertEquals(DAY, schedule.deliveryDate(at("09:00")));
    }

    @Test
    @DisplayName("during the lunch break the order goes on this afternoon's run")
    void betweenTheSessions() {
        DeliverySchedule schedule = lunchBreakShop();
        Instant lunchtime = at("14:00");

        assertEquals(StoreMode.NIGHT, schedule.mode(lunchtime),
                "THE VAN IS PARKED. Reporting SAME_DAY here is how a customer is promised a "
                        + "delivery by a shop whose shutters are down.");
        assertEquals(DeliveryType.NEXT_MORNING, schedule.deliveryType(lunchtime));
        assertEquals(DAY, schedule.deliveryDate(lunchtime),
                "but it is still TODAY - the afternoon run has not gone yet, and telling the "
                        + "customer tomorrow would lose the shop a sale it can fulfil");

        DeliveryWindow next = schedule.nextWindow(lunchtime);
        assertNotNull(next);
        assertEquals(at("16:00"), next.start());
        assertFalse(schedule.countdownActive(lunchtime),
                "nothing is closing in fifteen minutes; the shop is already shut");
    }

    @Test
    @DisplayName("the countdown counts to the end of the session that is running")
    void theCountdownIsPerSession() {
        DeliverySchedule schedule = lunchBreakShop();
        assertTrue(schedule.countdownActive(at("12:50")),
                "the morning run closes at one, and 12:50 is ten minutes from it");
        assertFalse(schedule.countdownActive(at("12:40")));
        assertTrue(schedule.countdownActive(at("20:50")), "and again before the evening close");
    }

    @Test
    @DisplayName("after the last run the answer is the next open day")
    void afterTheDayIsOver() {
        DeliverySchedule schedule = lunchBreakShop();
        // Shut every day but Monday, so the next run is a week away.
        assertEquals(DAY.plusWeeks(1), schedule.deliveryDate(at("21:00")));
    }

    @Test
    @DisplayName("a weekday the shop never opens has no run at all")
    void aWeeklyHolidayHasNoWindow() {
        DeliverySchedule schedule = lunchBreakShop();
        assertEquals(List.of(), schedule.windowsOn(DAY.plusDays(1)));
        assertNull(schedule.windowOn(DAY.plusDays(1)),
                "and the packing list for that day is honestly empty rather than invented");
    }

    @Test
    @DisplayName("a shop on the deployment's hours behaves exactly as it always did")
    void oneSessionIsTheOldBehaviour() {
        StoreScheduleProperties properties = deployment();
        DeliverySchedule schedule = new DeliverySchedule(
                properties, ShopHours.deploymentDefault(properties), date -> false);

        // The boundary the old test pinned: 20:59:59 is same-day, 21:00:00 is not.
        assertEquals(StoreMode.SAME_DAY, schedule.mode(at("20:59")));
        assertEquals(StoreMode.NIGHT, schedule.mode(at("21:00")));
        assertEquals(DAY, schedule.deliveryDate(at("20:59")));
        assertEquals(DAY.plusDays(1), schedule.deliveryDate(at("21:00")));
        assertEquals(at("09:00"), schedule.windowOn(DAY).start());
        assertEquals(1, schedule.windowsOn(DAY).size(),
                "one session, because that is what a deployment-configured shop has - and "
                        + "Shop #1 is on this path until somebody sets its hours (§19)");
    }
}
