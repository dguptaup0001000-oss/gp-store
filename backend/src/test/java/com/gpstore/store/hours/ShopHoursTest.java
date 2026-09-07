package com.gpstore.store.hours;

import com.gpstore.store.StoreScheduleProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which hours apply on a given day, and why.
 *
 * <p>THREE LAYERS AND ONE TRAP. Override beats week beats the deployment's
 * configuration, and the trap is the difference between "this shop has said
 * nothing" and "this shop has said it does not open on Sundays" - which look
 * identical if you only ask "are there sessions on Sunday". Getting that wrong
 * has one of two consequences and both are bad: every shop keeps the
 * deployment's hours forever, or a shop that declares a weekly holiday quietly
 * trades through it.
 */
@DisplayName("A shop's hours")
class ShopHoursTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final LocalDate MONDAY = LocalDate.of(2026, 3, 2);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 3, 8);

    private static StoreScheduleProperties deployment() {
        StoreScheduleProperties p = new StoreScheduleProperties();
        p.setZone("Asia/Kolkata");
        p.setDeliveryStart(LocalTime.of(9, 0));
        p.setDeliveryEnd(LocalTime.of(21, 0));
        p.setMorningPreparation(LocalTime.of(8, 0));
        return p;
    }

    private static ShopHours.OpenPeriod session(String opens, String closes) {
        return new ShopHours.OpenPeriod(LocalTime.parse(opens), LocalTime.parse(closes));
    }

    @Nested
    @DisplayName("a shop that has not set any")
    class Unconfigured {

        @Test
        @DisplayName("trades on the deployment's hours, every day")
        void fallsBack() {
            ShopHours hours = ShopHours.deploymentDefault(deployment());

            assertFalse(hours.isConfigured());
            assertEquals(List.of(session("09:00", "21:00")), hours.on(MONDAY));
            assertEquals(List.of(session("09:00", "21:00")), hours.on(SUNDAY),
                    "A SHOP THAT HAS NEVER OPENED THE HOURS SCREEN MUST KEEP TRADING as it "
                            + "traded yesterday (§19). This is Shop #1 on the day this ships.");
        }

        @Test
        @DisplayName("is the same answer when the week is empty rather than absent")
        void anEmptyWeekIsAlsoNotConfigured() {
            ShopHours hours = ShopHours.of(KOLKATA, Map.of(), Map.of(), deployment());
            assertFalse(hours.isConfigured());
            assertTrue(hours.tradesOn(SUNDAY));
        }
    }

    @Nested
    @DisplayName("a shop that has set its own")
    class Configured {

        private ShopHours weekdaysOnly() {
            return ShopHours.of(KOLKATA,
                    Map.of(DayOfWeek.MONDAY, List.of(session("07:00", "13:00"),
                                    session("16:00", "21:00"))),
                    Map.of(), deployment());
        }

        @Test
        @DisplayName("keeps every session of a day it opens twice")
        void sessionsAreKept() {
            assertEquals(List.of(session("07:00", "13:00"), session("16:00", "21:00")),
                    weekdaysOnly().on(MONDAY));
        }

        @Test
        @DisplayName("is SHUT on a weekday it did not mention")
        void anUnmentionedWeekdayIsAWeeklyHoliday() {
            ShopHours hours = weekdaysOnly();

            assertTrue(hours.isConfigured());
            assertEquals(List.of(), hours.on(SUNDAY));
            assertFalse(hours.tradesOn(SUNDAY),
                    "A SHOP THAT DECLARES A WEEKLY HOLIDAY MUST NOT FALL BACK INTO TRADING. "
                            + "\"No rows for Sunday\" and \"no rows at all\" are different "
                            + "sentences, and only the second one means \"we have not said\".");
        }

        @Test
        @DisplayName("sorts its sessions however they arrive")
        void sessionsComeBackInOrder() {
            ShopHours hours = ShopHours.of(KOLKATA,
                    Map.of(DayOfWeek.MONDAY, List.of(session("16:00", "21:00"),
                            session("07:00", "13:00"))),
                    Map.of(), deployment());
            assertEquals(session("07:00", "13:00"), hours.on(MONDAY).get(0),
                    "the schedule scans sessions in order, so it has to get them in order");
        }
    }

    @Nested
    @DisplayName("one date, different hours")
    class Overrides {

        @Test
        @DisplayName("replace that date's weekday, and nothing else")
        void overrideWinsForItsDateOnly() {
            ShopHours hours = ShopHours.of(KOLKATA,
                    Map.of(DayOfWeek.MONDAY, List.of(session("07:00", "21:00"))),
                    Map.of(MONDAY, List.of(session("11:00", "14:00"))),
                    deployment());

            assertEquals(List.of(session("11:00", "14:00")), hours.on(MONDAY));
            assertEquals(List.of(session("07:00", "21:00")), hours.on(MONDAY.plusWeeks(1)),
                    "next Monday is an ordinary Monday");
        }

        @Test
        @DisplayName("beat the deployment's hours at a shop that has set no week")
        void overrideWorksWithoutAWeek() {
            ShopHours hours = ShopHours.of(KOLKATA, Map.of(),
                    Map.of(MONDAY, List.of(session("11:00", "14:00"))), deployment());

            assertEquals(List.of(session("11:00", "14:00")), hours.on(MONDAY));
            assertEquals(List.of(session("09:00", "21:00")), hours.on(SUNDAY),
                    "and the rest of the week is still the deployment's");
        }
    }

    @Test
    @DisplayName("a session that ends before it starts is refused, not repaired")
    void backwardsSessionsAreRefused() {
        // The database says the same thing (ck_shop_hours_order). Both, because
        // a value object that trusts its caller is one bad API request away
        // from a shop that is open from 18:00 until 02:00 the same morning -
        // which every calculation below it would answer differently.
        assertThrows(IllegalArgumentException.class, () -> session("18:00", "02:00"));
        assertThrows(IllegalArgumentException.class, () -> session("09:00", "09:00"));
    }

    @Test
    @DisplayName("keeps the shop's own zone, not the deployment's")
    void theZoneIsTheShops() {
        ZoneId dubai = ZoneId.of("Asia/Dubai");
        assertEquals(dubai, ShopHours.of(dubai, Map.of(), Map.of(), deployment()).zone());
        assertEquals(KOLKATA, ShopHours.of(null, Map.of(), Map.of(), deployment()).zone(),
                "a shop with no zone of its own keeps the deployment's");
    }
}
