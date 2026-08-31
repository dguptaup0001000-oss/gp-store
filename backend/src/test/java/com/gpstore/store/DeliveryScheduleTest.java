package com.gpstore.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The scheduling rules, at the exact minutes where they change.
 *
 * <p>WHY THE TIMES ARE WRITTEN AS SHOP-LOCAL STRINGS and converted here rather
 * than as Instants or epoch seconds: 2026-03-02T15:30:00Z is 21:00 in Kanpur
 * and reviewing a test full of those means doing the arithmetic in your head
 * to check the test itself is right. {@link #at} does the conversion once, and
 * doing it through the shop's ZoneId is also the thing under test - the server
 * these run on is Etc/UTC, so a rule that accidentally used the JVM default
 * would put every boundary five and a half hours out and fail here.
 */
class DeliveryScheduleTest {

    private static final ZoneId SHOP = ZoneId.of("Asia/Kolkata");

    /** An ordinary Monday, no closures anywhere near it. */
    private static final LocalDate DAY = LocalDate.of(2026, 3, 2);

    private static StoreScheduleProperties defaults() {
        StoreScheduleProperties p = new StoreScheduleProperties();
        p.setZone("Asia/Kolkata");
        p.setDeliveryStart(LocalTime.of(9, 0));
        p.setDeliveryEnd(LocalTime.of(21, 0));
        p.setMorningPreparation(LocalTime.of(8, 0));
        p.setClosingCountdown(Duration.ofMinutes(15));
        p.validate();
        return p;
    }

    private static DeliverySchedule open() {
        return new DeliverySchedule(defaults(), date -> false);
    }

    private static DeliverySchedule closedOn(LocalDate... dates) {
        Set<LocalDate> shut = Set.of(dates);
        return new DeliverySchedule(defaults(), shut::contains);
    }

    /** A shop-local wall-clock time on {@link #DAY}, as an absolute instant. */
    private static Instant at(String hhmmss) {
        return ZonedDateTime.of(DAY, LocalTime.parse(hhmmss), SHOP).toInstant();
    }

    // ------------------------------------------------------------------
    // The fifteen times.
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} -> {1}, arriving {2}")
    @CsvSource({
            // Deep night. The shop is open for business; the van is not.
            "00:00:00, NEXT_MORNING, 2026-03-02",
            "03:30:00, NEXT_MORNING, 2026-03-02",
            // Still before opening: the next 09:00 is TODAY's, not tomorrow's.
            // Reading "next morning" as "tomorrow" would push a 07:00 order a
            // full day late, which is the single most likely off-by-one here.
            "07:59:59, NEXT_MORNING, 2026-03-02",
            "08:00:00, NEXT_MORNING, 2026-03-02",
            "08:59:59, NEXT_MORNING, 2026-03-02",
            // The window opens. Start is inclusive.
            "09:00:00, SAME_DAY,     2026-03-02",
            "09:00:01, SAME_DAY,     2026-03-02",
            "12:00:00, SAME_DAY,     2026-03-02",
            "20:44:59, SAME_DAY,     2026-03-02",
            // Inside the closing warning, but still same-day - the countdown
            // is a warning, not a cutoff.
            "20:45:00, SAME_DAY,     2026-03-02",
            "20:59:59, SAME_DAY,     2026-03-02",
            // The window closes. End is exclusive: 21:00:00 has missed it.
            "21:00:00, NEXT_MORNING, 2026-03-03",
            "21:00:01, NEXT_MORNING, 2026-03-03",
            "23:59:59, NEXT_MORNING, 2026-03-03",
            "22:30:00, NEXT_MORNING, 2026-03-03",
    })
    void deliveryTypeAndDateAtEveryBoundary(String time, DeliveryType type, LocalDate date) {
        DeliverySchedule schedule = open();
        Instant now = at(time);
        assertEquals(type, schedule.deliveryType(now), "delivery type at " + time);
        assertEquals(date, schedule.deliveryDate(now), "delivery date at " + time);
        assertEquals(
                type == DeliveryType.SAME_DAY ? StoreMode.SAME_DAY : StoreMode.NIGHT,
                schedule.mode(now),
                "mode at " + time);
    }

    @Nested
    @DisplayName("browsing and order acceptance")
    class Availability {

        @Test
        void browsingIsOpenAtEveryHourOfTheDay() {
            // The headline requirement: the shop can be shopped at 3am.
            DeliverySchedule schedule = open();
            for (int hour = 0; hour < 24; hour++) {
                StoreStatus status = schedule.status(
                        at(String.format("%02d:00:00", hour)), StoreOrderAcceptance.AUTO, null);
                assertTrue(status.browsingOpen(), "browsing at " + hour + ":00");
            }
        }

        @Test
        void browsingStaysOpenEvenWhenOrderingIsSwitchedOff() {
            // Turning off orders must not turn off the catalogue. These are
            // separate switches and this is the test that says so.
            StoreStatus status = open().status(at("03:00:00"), StoreOrderAcceptance.OFF, "Stocktake");
            assertTrue(status.browsingOpen());
            assertFalse(status.acceptingOrders());
        }

        @Test
        void autoAcceptsOrdersRoundTheClock() {
            DeliverySchedule schedule = open();
            for (int hour = 0; hour < 24; hour++) {
                assertTrue(
                        schedule.acceptingOrders(
                                at(String.format("%02d:30:00", hour)), StoreOrderAcceptance.AUTO),
                        "accepting at " + hour + ":30");
            }
        }

        @Test
        void offRefusesOrdersEvenAtMidday() {
            assertFalse(open().acceptingOrders(at("12:00:00"), StoreOrderAcceptance.OFF));
        }

        @Test
        void onAcceptsOrdersEvenWithNoReachableDeliveryDay() {
            // Every day inside the lookahead is shut, so AUTO has nothing to
            // promise and stops; ON deliberately keeps taking orders.
            LocalDate[] shut = new LocalDate[40];
            for (int i = 0; i < shut.length; i++) {
                shut[i] = DAY.plusDays(i);
            }
            DeliverySchedule schedule = closedOn(shut);
            Instant now = at("12:00:00");
            assertNull(schedule.nextWindow(now));
            assertFalse(schedule.acceptingOrders(now, StoreOrderAcceptance.AUTO));
            assertTrue(schedule.acceptingOrders(now, StoreOrderAcceptance.ON));
        }
    }

    @Nested
    @DisplayName("the closing countdown")
    class Countdown {

        @Test
        void isSilentUntilFifteenMinutesRemain() {
            DeliverySchedule schedule = open();
            assertFalse(schedule.countdownActive(at("20:44:59")));
            assertNull(schedule.countdownRemaining(at("20:44:59")));
            assertTrue(schedule.countdownActive(at("20:45:00")));
        }

        @Test
        void countsDownToTheMinute() {
            assertEquals(Duration.ofMinutes(15), open().countdownRemaining(at("20:45:00")));
            assertEquals(Duration.ofMinutes(1), open().countdownRemaining(at("20:59:00")));
            assertEquals(Duration.ofSeconds(1), open().countdownRemaining(at("20:59:59")));
        }

        @Test
        void stopsRatherThanGoingNegativeAtClose() {
            // 21:00:00 is outside the window, so there is nothing to count
            // down to. Null, not zero, and not a negative duration rendered
            // as "closes in -1 minutes".
            assertNull(open().countdownRemaining(at("21:00:00")));
            assertFalse(open().countdownActive(at("21:00:00")));
        }

        @Test
        void isSilentAtNightWhenThereIsNothingToMiss() {
            assertFalse(open().countdownActive(at("03:00:00")));
            assertFalse(open().countdownActive(at("08:00:00")));
        }
    }

    @Nested
    @DisplayName("full-day closure")
    class Closure {

        @Test
        void middayOnAClosedDayIsNightAndRollsToTomorrow() {
            // Nothing special-cases closure: there is simply no run today for
            // 14:00 to fall inside, so the ordinary rule produces the right
            // answer.
            DeliverySchedule schedule = closedOn(DAY);
            Instant now = at("14:00:00");
            assertEquals(StoreMode.NIGHT, schedule.mode(now));
            assertEquals(DeliveryType.NEXT_MORNING, schedule.deliveryType(now));
            assertEquals(DAY.plusDays(1), schedule.deliveryDate(now));
            assertTrue(schedule.status(now, StoreOrderAcceptance.AUTO, "Holi").closedToday());
        }

        @Test
        void ordersAreStillAcceptedOnAClosedDay() {
            // A closed day is a day with no van, not a day with no shop.
            DeliverySchedule schedule = closedOn(DAY);
            assertTrue(schedule.acceptingOrders(at("14:00:00"), StoreOrderAcceptance.AUTO));
            assertTrue(schedule.status(at("14:00:00"), StoreOrderAcceptance.AUTO, "Holi").browsingOpen());
        }

        @Test
        void skipsAsManyConsecutiveClosedDaysAsThereAre() {
            DeliverySchedule schedule = closedOn(DAY, DAY.plusDays(1), DAY.plusDays(2));
            assertEquals(DAY.plusDays(3), schedule.deliveryDate(at("10:00:00")));
        }

        @Test
        void aNightOrderSkipsAClosedTomorrow() {
            // 22:00 tonight: today's van has gone and tomorrow is shut, so the
            // order is for the day after.
            DeliverySchedule schedule = closedOn(DAY.plusDays(1));
            assertEquals(DAY.plusDays(2), schedule.deliveryDate(at("22:00:00")));
        }

        @Test
        void givesUpRatherThanLoopingWhenTheShopNeverReopens() {
            LocalDate[] shut = new LocalDate[40];
            for (int i = 0; i < shut.length; i++) {
                shut[i] = DAY.plusDays(i);
            }
            DeliverySchedule schedule = closedOn(shut);
            assertNull(schedule.nextWindow(at("10:00:00")));
            assertNull(schedule.deliveryDate(at("10:00:00")));
            assertNull(schedule.deliveryType(at("10:00:00")));
        }
    }

    @Nested
    @DisplayName("the window itself")
    class Window {

        @Test
        void preparationIsAnHourBeforeTheVansLeave() {
            DeliveryWindow window = open().windowOn(DAY);
            assertEquals(at("08:00:00"), window.preparation());
            assertEquals(at("09:00:00"), window.start());
            assertEquals(at("21:00:00"), window.end());
            assertEquals(DAY, window.date());
        }

        @Test
        void containsIsStartInclusiveAndEndExclusive() {
            DeliveryWindow window = open().windowOn(DAY);
            assertTrue(window.contains(at("09:00:00")));
            assertTrue(window.contains(at("20:59:59")));
            assertFalse(window.contains(at("08:59:59")));
            assertFalse(window.contains(at("21:00:00")));
        }

        @Test
        void aSevenAmOrderGetsTodaysWindowNotTomorrows() {
            assertEquals(DAY, open().nextWindow(at("07:00:00")).date());
        }

        @Test
        void aTenPmOrderGetsTomorrowsWindow() {
            assertEquals(DAY.plusDays(1), open().nextWindow(at("22:00:00")).date());
        }
    }

    @Nested
    @DisplayName("the server's timezone must not leak in")
    class Timezone {

        @Test
        void boundariesAreTheShopsHoursNotUtcHours() {
            // 15:29:59Z is 20:59:59 in Kanpur - inside the window. If any rule
            // used the JVM default zone (Etc/UTC here) it would read this as
            // 15:29 and agree by accident, so the companion assertion below
            // pins the case where the two disagree: 21:00 UTC is 02:30 next
            // day in Kanpur, which must be NIGHT, not SAME_DAY.
            DeliverySchedule schedule = open();
            assertEquals(StoreMode.SAME_DAY,
                    schedule.mode(Instant.parse("2026-03-02T15:29:59Z")));
            assertEquals(StoreMode.NIGHT,
                    schedule.mode(Instant.parse("2026-03-02T21:00:00Z")));
            assertEquals(StoreMode.NIGHT,
                    schedule.mode(Instant.parse("2026-03-02T15:30:00Z")));
        }

        @Test
        void theShopLocalDateRollsAtMidnightInKanpurNotInLondon() {
            // 2026-03-02T19:00Z is already the 3rd in Kanpur (00:30).
            assertEquals(LocalDate.of(2026, 3, 3),
                    open().localDate(Instant.parse("2026-03-02T19:00:00Z")));
            assertEquals(LocalDate.of(2026, 3, 2),
                    open().localDate(Instant.parse("2026-03-02T18:00:00Z")));
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        void refusesAWindowThatEndsBeforeItStarts() {
            StoreScheduleProperties p = new StoreScheduleProperties();
            p.setDeliveryStart(LocalTime.of(21, 0));
            p.setDeliveryEnd(LocalTime.of(9, 0));
            assertThrows(IllegalStateException.class, p::validate);
        }

        @Test
        void refusesPackingScheduledAfterTheVanLeaves() {
            StoreScheduleProperties p = new StoreScheduleProperties();
            p.setMorningPreparation(LocalTime.of(10, 0));
            assertThrows(IllegalStateException.class, p::validate);
        }

        @Test
        void refusesAnUnknownTimezoneRatherThanFallingBackToUtc() {
            StoreScheduleProperties p = new StoreScheduleProperties();
            assertThrows(java.time.DateTimeException.class, () -> p.setZone("Asia/Kanpur"));
        }

        @Test
        void differentHoursMoveTheBoundaries() {
            // Proves the hours are genuinely read from configuration rather
            // than any of them being hardcoded somewhere in the rules.
            StoreScheduleProperties p = new StoreScheduleProperties();
            p.setDeliveryStart(LocalTime.of(10, 0));
            p.setDeliveryEnd(LocalTime.of(20, 0));
            p.setMorningPreparation(LocalTime.of(9, 30));
            p.setClosingCountdown(Duration.ofMinutes(30));
            p.validate();
            DeliverySchedule schedule = new DeliverySchedule(p, date -> false);

            assertEquals(StoreMode.NIGHT, schedule.mode(at("09:30:00")));
            assertEquals(StoreMode.SAME_DAY, schedule.mode(at("10:00:00")));
            assertEquals(StoreMode.NIGHT, schedule.mode(at("20:00:00")));
            assertTrue(schedule.countdownActive(at("19:30:00")));
            assertFalse(schedule.countdownActive(at("19:29:59")));
        }
    }
}
