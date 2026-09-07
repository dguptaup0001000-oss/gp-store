package com.gpstore.platform;

import com.gpstore.entity.StoreOperationsSettings;
import com.gpstore.repository.StoreOperationsSettingsRepository;
import com.gpstore.store.DeliveryScheduleService;
import com.gpstore.store.StoreOperationsService;
import com.gpstore.store.StoreOrderAcceptance;
import com.gpstore.store.StoreStatus;
import com.gpstore.store.hours.ShopBusinessHours;
import com.gpstore.store.hours.ShopBusinessHoursRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ONE KIRANA'S OPENING HOURS ARE ITS OWN (§3, §11, §18.10).
 *
 * <p>WHAT THIS REPLACES. Until now "when is the shop open" was one
 * @ConfigurationProperties bean shared by the whole deployment: 09:00 to 21:00,
 * Asia/Kolkata, for every shop on the marketplace. A merchant could say "we
 * are not taking orders" and could declare a day closed, and that was the
 * whole vocabulary - there was no way to say "we open at seven", "we shut for
 * lunch", or "we do not open on Sundays".
 *
 * <p>THE THREE THINGS A SHARED SETTING BREAKS, and this test asserts all
 * three: a shop cannot describe its own day; a shop that changes its hours
 * changes everybody's; and a shop that has said nothing must keep the hours it
 * has always traded on rather than inheriting a neighbour's.
 */
@SpringBootTest(properties = {
        "platform.mode=MULTI_SHOP_PRODUCTION",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("Each shop keeps its own hours")
class EachShopKeepsItsOwnHoursTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private StoreOperationsService operations;
    @Autowired private DeliveryScheduleService schedule;
    @Autowired private ShopBusinessHoursRepository weeklyHours;
    @Autowired private StoreOperationsSettingsRepository settings;

    private final String tag = "hrs" + System.nanoTime();

    private long shopA;
    private long shopB;
    private Long merchantId;

    @BeforeEach
    void twoShops() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
        shopA = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();

        Merchant m = new Merchant();
        m.setLegalName("Hours fixture " + tag);
        m.setDisplayName("Hours fixture");
        m.setStatus(MerchantStatus.ACTIVE);
        m.setIsDemo(Boolean.TRUE);
        m.setActive(Boolean.TRUE);
        merchantId = merchants.save(m).getId();

        Shop b = new Shop();
        b.setMerchantId(merchantId);
        b.setCode("HRS-" + tag);
        b.setDisplayName("Late kirana");
        b.setStatus(ShopStatus.ACTIVE);
        b.setLatitude(27.16);
        b.setLongitude(83.94);
        b.setMaxDeliveryRadiusKm(new BigDecimal("5"));
        b.setTimeZone("Asia/Kolkata");
        b.setIsDemo(Boolean.TRUE);
        b.setActive(Boolean.TRUE);
        shopB = shops.save(b).getId();
    }

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        // Shop #1 is the LIVE shop. Anything this test wrote onto it has to
        // come off, or the next test - and a real deployment restored from
        // this database - finds a kirana with invented opening hours.
        for (long shop : List.of(shopA, shopB)) {
            jdbc.update("DELETE FROM shop_business_hours WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_hours_override WHERE shop_id = ?", shop);
            jdbc.update("UPDATE store_operations_settings SET order_acceptance = 'AUTO', "
                    + "paused_until = NULL, closure_message = NULL WHERE shop_id = ?", shop);
        }
        jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM shops WHERE id = ?", shopB);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    // ------------------------------------------------------------- the week

    @Test
    @DisplayName("a shop that sets its own hours gets them, and its neighbour does not")
    void hoursAreNotShared() {
        setWholeWeek(shopA, "06:00", "10:00");

        List<ShopBusinessHours> onA = read(shopA, operations::weeklyHours);
        List<ShopBusinessHours> onB = read(shopB, operations::weeklyHours);

        assertEquals(7, onA.size(), "seven days, one session each");
        assertTrue(onB.isEmpty(),
                "ONE MERCHANT SETTING THEIR HOURS MUST NOT SET THE SHOP NEXT DOOR'S. Before "
                        + "V55 there was one set of hours for the whole marketplace, so this "
                        + "was not a question anybody could ask.");

        assertEquals(LocalTime.of(6, 0), read(shopA, () -> openingTimeToday()));
        assertEquals(LocalTime.of(9, 0), read(shopB, () -> openingTimeToday()),
                "and a shop that has said nothing keeps the hours it has always traded on "
                        + "(§19) rather than inheriting its neighbour's");
    }

    @Test
    @DisplayName("a weekday the shop did not mention is a weekly holiday, for that shop alone")
    void aWeeklyHolidayIsPerShop() {
        // Open every day except the shop-local today.
        DayOfWeek today = read(shopA, () -> schedule.localNow().toLocalDate().getDayOfWeek());
        Map<DayOfWeek, List<StoreOperationsService.TradingSession>> week =
                new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day != today) {
                week.put(day, List.of(new StoreOperationsService.TradingSession(
                        LocalTime.of(9, 0), LocalTime.of(21, 0))));
            }
        }
        read(shopA, () -> operations.replaceWeek(week, "hours-test"));

        LocalDate localToday = read(shopA, () -> schedule.localNow().toLocalDate());
        assertNull(read(shopA, () -> schedule.closingTimeOn(localToday)),
                "the shop is shut today, so there is no closing time to report");
        assertNotNull(read(shopB, () -> schedule.closingTimeOn(localToday)),
                "AND THE SHOP NEXT DOOR IS OPEN. A weekly holiday that closed the marketplace "
                        + "would be the shared-configuration bug wearing a new hat.");
    }

    // ------------------------------------------------------------ the pause

    @Test
    @DisplayName("\"back in 30 minutes\" stops this shop taking orders and nobody else's")
    void aPauseIsPerShop() {
        read(shopA, () -> operations.pauseForMinutes(30, "Back shortly " + tag, "hours-test"));

        assertFalse(read(shopA, () -> schedule.isStoreAcceptingOrders()),
                "the shop that paused is not taking orders");
        assertTrue(read(shopB, () -> schedule.isStoreAcceptingOrders()),
                "and the one that did not, is");

        StoreStatus onA = read(shopA, () -> schedule.getStoreStatus());
        assertNotNull(onA.pausedUntil(),
                "\"BACK AT FOUR\" IS A DIFFERENT PROMISE FROM \"CLOSED\", and the app can only "
                        + "draw the first one if the time comes with it");
        assertTrue(onA.browsingOpen(), "browsing is never closed - a paused kirana is still a shop");
    }

    @Test
    @DisplayName("a pause that has run out has already lifted, with nothing to run it")
    void anExpiredPauseLiftsItself() {
        read(shopA, () -> operations.pauseForMinutes(30, "Back shortly " + tag, "hours-test"));
        assertFalse(read(shopA, () -> schedule.isStoreAcceptingOrders()));

        // The clock moved on. Written straight to the row because that is what
        // the passage of half an hour looks like from the database's side, and
        // the point of the design is that NOTHING ELSE HAS TO HAPPEN - no job
        // runs, no flag is flipped, the next read simply finds the time past.
        jdbc.update("UPDATE store_operations_settings SET paused_until = ? WHERE shop_id = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), shopA);

        assertTrue(read(shopA, () -> schedule.isStoreAcceptingOrders()),
                "A PAUSE WITH A TIME ON IT ENDS BY ITSELF. Needing a scheduled job to lift it "
                        + "would mean the shop stays shut for exactly as long as the job is late.");

        StoreOperationsSettings row = read(shopA,
                () -> settings.findByShopId(shopA).orElseThrow());
        assertEquals(StoreOrderAcceptance.OFF, row.acceptanceOrDefault(),
                "and the row still records what the shopkeeper actually chose - the lift is "
                        + "computed on read, not written behind their back");
    }

    @Test
    @DisplayName("resuming clears the pause and the message together")
    void resumeClearsEverything() {
        read(shopA, () -> operations.pauseForMinutes(30, "Back shortly " + tag, "hours-test"));
        read(shopA, () -> operations.resume("hours-test"));

        StoreStatus status = read(shopA, () -> schedule.getStoreStatus());
        assertTrue(status.acceptingOrders());
        assertNull(status.pausedUntil());
        assertNull(status.closureReason(),
                "a stale \"back in 30 minutes\" on a shop that is open is worse than no message");
    }

    // ------------------------------------------------------------- fixtures

    private LocalTime openingTimeToday() {
        LocalDate today = schedule.localNow().toLocalDate();
        var windows = schedule.windowsOn(today);
        return windows.isEmpty() ? null
                : windows.get(0).start().atZone(schedule.shopZone()).toLocalTime();
    }

    private void setWholeWeek(long shopId, String opens, String closes) {
        Map<DayOfWeek, List<StoreOperationsService.TradingSession>> week =
                new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            week.put(day, List.of(new StoreOperationsService.TradingSession(
                    LocalTime.parse(opens), LocalTime.parse(closes))));
        }
        read(shopId, () -> operations.replaceWeek(week, "hours-test"));
    }

    private <T> T read(long shopId, java.util.function.Supplier<T> work) {
        return TenantContext.runWithin(TenantScope.ofShop(shopId), work::get);
    }

    private void read(long shopId, Runnable work) {
        TenantContext.runWithin(TenantScope.ofShop(shopId), work);
    }
}
