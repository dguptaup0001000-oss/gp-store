package com.gpstore.platform;

import com.gpstore.entity.Order;
import com.gpstore.repository.OrderRepository;
import com.gpstore.store.DeliveryScheduleService;
import com.gpstore.store.StoreOperationsService;
import com.gpstore.store.StoreOrderAcceptance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ONCE A SHOP HAS ACCEPTED AN ORDER, IT OWES THAT ORDER (§12).
 *
 * <p>THE RULE, AND WHY IT NEEDS A TEST OF ITS OWN. Everything a merchant can
 * do to stop trading - pause for half an hour, stop taking orders for the day,
 * declare tomorrow closed, close the shop - is about the NEXT customer. The
 * ones already waiting for their packets have been promised something, and the
 * promise does not expire because the shopkeeper pressed a button afterwards.
 *
 * <p>IT IS EASY TO GET WRONG IN THE OBVIOUS DIRECTION. "Stop accepting orders"
 * reads like it should clear the board, and a closure reads like it should
 * cancel that day's deliveries. Either would be the platform cancelling a
 * customer's order on the merchant's behalf, from a button whose label says
 * nothing of the kind - and the customer would be told nothing at all.
 *
 * <p>So the assertion is deliberately about what did NOT change: the order's
 * status, the day it was promised for, and the fact that both the shop and the
 * customer can still see it. The other half - that NEW orders are refused - is
 * what makes the test meaningful rather than vacuous.
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
@DisplayName("An accepted order is still owed")
class AnAcceptedOrderIsStillOwedTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;
    @Autowired private StoreOperationsService operations;
    @Autowired private DeliveryScheduleService schedule;
    @Autowired private OrderRepository orders;

    private final String tag = "owed" + System.nanoTime();

    private long shopId;
    private Long orderId;
    private LocalDate promisedFor;

    @BeforeEach
    void anOrderThisShopHasAccepted() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
        shopId = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();

        promisedFor = TenantContext.runWithin(TenantScope.ofShop(shopId),
                () -> schedule.calculateDeliveryDate());

        String number = "OWED-" + tag;
        jdbc.update("INSERT INTO orders (order_number, shop_id, order_date, total_amount, "
                        + "order_status, payment_status, scheduled_delivery_date) "
                        + "VALUES (?, ?, now(), ?, 'CONFIRMED', 'PENDING', ?)",
                number, shopId, new BigDecimal("240.00"),
                promisedFor == null ? null : java.sql.Date.valueOf(promisedFor));
        orderId = jdbc.queryForObject("SELECT id FROM orders WHERE order_number = ?",
                Long.class, number);
    }

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        jdbc.update("DELETE FROM orders WHERE id = ?", orderId);
        // Shop #1 is the live shop: put its switch back, or every test after
        // this one runs against a shop that is refusing orders.
        jdbc.update("UPDATE store_operations_settings SET order_acceptance = 'AUTO', "
                + "paused_until = NULL, closure_message = NULL WHERE shop_id = ?", shopId);
        jdbc.update("DELETE FROM store_closures WHERE shop_id = ? AND reason LIKE ?",
                shopId, "%" + tag + "%");
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    @Test
    @DisplayName("pausing for thirty minutes owes it still")
    void aPauseDoesNotTouchTheBoard() {
        within(() -> operations.pauseForMinutes(30, "Back shortly " + tag, "owed-test"));

        assertStillOwed("a shopkeeper stepping out for half an hour has not cancelled "
                + "anybody's shopping");
        assertFalse(within(() -> schedule.isStoreAcceptingOrders()),
                "and the pause is real - new orders ARE refused, or this test proves nothing");
    }

    @Test
    @DisplayName("stopping orders for the day owes it still")
    void switchingOffDoesNotTouchTheBoard() {
        within(() -> operations.setOrderAcceptance(
                StoreOrderAcceptance.OFF, "Closed for the day " + tag, "owed-test"));

        assertStillOwed("\"no more orders today\" is about the next customer, not about the "
                + "ones already waiting");
        assertFalse(within(() -> schedule.isStoreAcceptingOrders()));
    }

    @Test
    @DisplayName("declaring the promised day closed owes it still")
    void aClosureDoesNotCancelWhatWasPromised() {
        LocalDate today = within(() -> schedule.localNow().toLocalDate());
        within(() -> operations.addClosure(today, "Wedding " + tag, "owed-test"));

        assertStillOwed("A CLOSURE DECLARED AFTERWARDS MUST NOT CANCEL THE DELIVERY. The "
                + "merchant may have a genuine emergency and GP-STORE can review it - what "
                + "the platform must not do is quietly withdraw the promise and tell the "
                + "customer nothing.");
    }

    // ------------------------------------------------------------ assertions

    private void assertStillOwed(String why) {
        Order order = within(() -> orders.findById(orderId).orElseThrow());

        assertEquals("CONFIRMED", String.valueOf(order.getOrderStatus()), why);
        assertEquals(promisedFor, order.getScheduledDeliveryDate(),
                "the day the customer was promised must not move either: " + why);
        assertTrue(within(() -> orders.findById(orderId).isPresent()),
                "and the shop must still be able to see the order it owes");
    }

    private <T> T within(java.util.function.Supplier<T> work) {
        return TenantContext.runWithin(TenantScope.ofShop(shopId), work::get);
    }

    private void within(Runnable work) {
        TenantContext.runWithin(TenantScope.ofShop(shopId), work);
    }
}
