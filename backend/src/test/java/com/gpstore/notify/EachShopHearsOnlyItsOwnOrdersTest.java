package com.gpstore.notify;

import com.gpstore.entity.Order;
import com.gpstore.enums.OrderStatus;
import com.gpstore.enums.PaymentStatus;
import com.gpstore.platform.MerchantStatus;
import com.gpstore.platform.MerchantLifecycleService;
import com.gpstore.platform.PlatformMode;
import com.gpstore.platform.PlatformProperties;
import com.gpstore.platform.ShopLifecycleService;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.ShopStatus;
import com.gpstore.platform.TenantDefaults;
import com.gpstore.service.PushNotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An order belongs to one shop, and so does the news of it.
 *
 * <h2>The defect these were written against</h2>
 *
 * <p>{@code notifyAdminsOfNewOrder} read {@code customerRepository.findByRole(ADMIN)}
 * and pushed to every one of them. Every merchant on this platform holds ADMIN,
 * so an order placed at one shop announced the customer's name and the order
 * total to every other merchant - a real cross-tenant disclosure, on the same
 * data the rest of the codebase goes to considerable lengths to keep apart.
 *
 * <p>These assert the recipients, which is the part that was wrong. They run
 * against a real database with real {@code shop_staff} rows, and they do not
 * need a Firebase credential: the provider is mocked, so what is being tested
 * is WHO would be sent to, not whether Google accepted it.
 */
@SpringBootTest
@DisplayName("each shop hears only its own orders")
class EachShopHearsOnlyItsOwnOrdersTest {

    @Autowired private NewOrderAlerts alerts;
    @Autowired private OrderAlertLedger ledger;
    @Autowired private PushRegistrationService registrations;
    @Autowired private PushRegistrationRepository registrationRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MerchantLifecycleService merchantLifecycle;
    @Autowired private ShopLifecycleService shopLifecycle;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;

    /**
     * MOCKED ON PURPOSE. CI has no Firebase service account and production has
     * push switched off, so a test that needed a real send could only ever be
     * skipped. Everything worth asserting here happens before the provider.
     */
    @MockitoBean private PushNotificationService push;

    private final String tag = "alert" + System.nanoTime();

    private Long merchantA;
    private Long merchantB;
    private long shopA;
    private long shopB;
    private long ownerA;
    private long managerA;
    private long ownerB;
    private final List<Long> orders = new ArrayList<>();

    @BeforeEach
    void twoShops() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
        when(push.sendPushTo(anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(PushNotificationService.PushOutcome.SENT);

        merchantA = merchant("A");
        merchantB = merchant("B");
        shopA = shop(merchantA, "alrt-a-" + tag);
        shopB = shop(merchantB, "alrt-b-" + tag);

        ownerA = account("ownera");
        managerA = account("managera");
        ownerB = account("ownerb");
        staff(shopA, ownerA, true);
        staff(shopA, managerA, false);
        staff(shopB, ownerB, true);
    }

    @AfterEach
    void tidyUp() {
        jdbc.update("DELETE FROM order_alerts_sent WHERE shop_id IN (?, ?)", shopA, shopB);
        for (Long order : orders) {
            jdbc.update("DELETE FROM order_alerts_sent WHERE order_id = ?", order);
            jdbc.update("DELETE FROM orders WHERE id = ?", order);
        }
        for (long account : new long[]{ownerA, managerA, ownerB}) {
            jdbc.update("DELETE FROM push_registrations WHERE customer_id = ?", account);
            jdbc.update("DELETE FROM shop_staff WHERE customer_id = ?", account);
            jdbc.update("DELETE FROM customers WHERE id = ?", account);
        }
        for (long shop : new long[]{shopA, shopB}) {
            jdbc.update("DELETE FROM shop_staff WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shops WHERE id = ?", shop);
        }
        jdbc.update("DELETE FROM merchants WHERE id IN (?, ?)", merchantA, merchantB);
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    // ==================================================== who is selected

    @Nested
    @DisplayName("the recipients")
    class Recipients {

        @Test
        @DisplayName("shop A's order reaches shop A's people and nobody else")
        void neverTheOtherShop() {
            register(ownerA, "tok-a-owner", PushApp.MERCHANT_ADMIN);
            register(managerA, "tok-a-manager", PushApp.MERCHANT_ADMIN);
            register(ownerB, "tok-b-owner", PushApp.MERCHANT_ADMIN);

            List<String> reached = alerts.recipientsFor(shopA).stream()
                    .map(PushRegistration::getToken).toList();

            assertEquals(2, reached.size(), "both of shop A's people: " + reached);
            assertTrue(reached.contains("tok-a-owner"));
            assertTrue(reached.contains("tok-a-manager"),
                    "a second legitimate manager device must be included");
            assertFalse(reached.contains("tok-b-owner"),
                    "THE BUG: the other merchant must never be on this list");
        }

        @Test
        @DisplayName("the same person's customer app is not a merchant device")
        void theAppMatters() {
            register(ownerA, "tok-a-merchant", PushApp.MERCHANT_ADMIN);
            register(ownerA, "tok-a-customer", PushApp.CUSTOMER);

            List<String> reached = alerts.recipientsFor(shopA).stream()
                    .map(PushRegistration::getToken).toList();

            assertEquals(List.of("tok-a-merchant"), reached,
                    "a shopkeeper's own customer app must not receive shop alerts");
        }

        @Test
        @DisplayName("a manager removed from the shop stops hearing its orders")
        void removedMeansRemoved() {
            register(managerA, "tok-a-manager", PushApp.MERCHANT_ADMIN);
            assertEquals(1, alerts.recipientsFor(shopA).size(), "on the staff, so included");

            jdbc.update("UPDATE shop_staff SET active = false WHERE shop_id = ? AND customer_id = ?",
                    shopA, managerA);

            assertTrue(alerts.recipientsFor(shopA).isEmpty(),
                    "membership is read fresh at dispatch, so revoking it takes effect at once "
                            + "with no device to clean up");
        }

        @Test
        @DisplayName("signing out silences that device and no other")
        void signingOutIsPerDevice() {
            register(ownerA, "tok-phone", PushApp.MERCHANT_ADMIN);
            register(ownerA, "tok-tablet", PushApp.MERCHANT_ADMIN);

            registrations.forget(ownerA, "tok-phone");

            List<String> reached = alerts.recipientsFor(shopA).stream()
                    .map(PushRegistration::getToken).toList();
            assertEquals(List.of("tok-tablet"), reached,
                    "signing out of the phone is not signing out of the counter tablet");
        }

        @Test
        @DisplayName("a second merchant signing in on the same handset takes the device over")
        void thePhoneChangesHands() {
            register(ownerA, "shared-handset", PushApp.MERCHANT_ADMIN);
            assertEquals(1, alerts.recipientsFor(shopA).size());

            // The phone is handed to a different merchant, who signs in.
            register(ownerB, "shared-handset", PushApp.MERCHANT_ADMIN);

            assertTrue(alerts.recipientsFor(shopA).isEmpty(),
                    "the previous merchant must stop receiving orders on a phone they no "
                            + "longer hold");
            assertEquals(1, alerts.recipientsFor(shopB).size(),
                    "and the new one must start");
            assertEquals(1, registrationRepository.findByToken("shared-handset").stream().count(),
                    "one token, one row - never two");
        }

        @Test
        @DisplayName("you cannot silence somebody else's device by naming their token")
        void forgettingIsNotAWeapon() {
            register(ownerB, "tok-b-owner", PushApp.MERCHANT_ADMIN);

            boolean acted = registrations.forget(ownerA, "tok-b-owner");

            assertFalse(acted, "presenting another account's token must do nothing");
            assertEquals(1, alerts.recipientsFor(shopB).size(),
                    "and their device must still be reachable");
        }
    }

    // ================================================= when it is announced

    @Nested
    @DisplayName("announcing")
    class Announcing {

        @Test
        @DisplayName("a real order is announced once, to its own shop")
        void announcedOnce() {
            register(ownerA, "tok-a-owner", PushApp.MERCHANT_ADMIN);
            Order order = placedOrder(shopA, PaymentStatus.COD_PENDING, OrderStatus.CONFIRMED);

            alerts.announce(order);

            ArgumentCaptor<Map<String, String>> data = captor();
            verify(push).sendPushTo(anyString(), anyString(), anyString(), data.capture());
            assertEquals(String.valueOf(shopA), data.getValue().get("shopId"),
                    "the payload must name the shop the order is actually for");
            assertEquals(1, alertRows(order), "one ledger row");
        }

        @Test
        @DisplayName("the same order arriving twice is announced once")
        void retriesDoNotDuplicate() {
            register(ownerA, "tok-a-owner", PushApp.MERCHANT_ADMIN);
            Order order = placedOrder(shopA, PaymentStatus.SUCCESS, OrderStatus.CONFIRMED);

            // A retried checkout, a replayed webhook and an outbox retry all look
            // exactly like this.
            alerts.announce(order);
            alerts.announce(order);
            alerts.announce(order);

            verify(push).sendPushTo(anyString(), anyString(), anyString(), anyMap());
            assertEquals(1, alertRows(order),
                    "the unique index is the mechanism, so three attempts leave one row");
        }

        @Test
        @DisplayName("an unverified online payment is not an order yet")
        void nothingIsSaidBeforeThePaymentIsReal() {
            register(ownerA, "tok-a-owner", PushApp.MERCHANT_ADMIN);
            Order unpaid = placedOrder(shopA, PaymentStatus.PENDING,
                    OrderStatus.PENDING_CONFIRMATION);

            alerts.announce(unpaid);

            verify(push, never()).sendPushTo(any(), any(), any(), any());
            assertEquals(0, alertRows(unpaid),
                    "and nothing is claimed either, so the real confirmation can still announce it");
        }

        @Test
        @DisplayName("a confirmed order for a shop with no device simply reaches nobody")
        void noDeviceIsNotAnError() {
            Order order = placedOrder(shopA, PaymentStatus.COD_PENDING, OrderStatus.CONFIRMED);

            alerts.announce(order);

            verify(push, never()).sendPushTo(any(), any(), any(), any());
        }

        @Test
        @DisplayName("shop B's device is never sent shop A's order")
        void theWholePointOfTheChange() {
            register(ownerA, "tok-a-owner", PushApp.MERCHANT_ADMIN);
            register(ownerB, "tok-b-owner", PushApp.MERCHANT_ADMIN);
            Order forA = placedOrder(shopA, PaymentStatus.COD_PENDING, OrderStatus.CONFIRMED);

            alerts.announce(forA);

            ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
            verify(push).sendPushTo(token.capture(), anyString(), anyString(), anyMap());
            assertEquals("tok-a-owner", token.getValue(),
                    "exactly one device, and it is shop A's");
        }

        @Test
        @DisplayName("a dead token is retired rather than retried forever")
        void deadTokensStopBeingTried() {
            register(ownerA, "gone", PushApp.MERCHANT_ADMIN);
            clearInvocations(push);
            when(push.sendPushTo(anyString(), anyString(), anyString(), anyMap()))
                    .thenReturn(PushNotificationService.PushOutcome.INVALID_TOKEN);

            alerts.announce(placedOrder(shopA, PaymentStatus.COD_PENDING, OrderStatus.CONFIRMED));

            assertTrue(alerts.recipientsFor(shopA).isEmpty(),
                    "FCM said the install is gone, so the row stops being used");
            assertFalse(registrationRepository.findByToken("gone").isEmpty(),
                    "disabled, not deleted - the history is worth keeping");
        }
    }

    @Test
    @DisplayName("the ledger itself refuses a second claim on one order")
    void theLedgerIsTheGuarantee() {
        Order order = placedOrder(shopA, PaymentStatus.COD_PENDING, OrderStatus.CONFIRMED);

        assertTrue(ledger.claim(order.getId(), OrderAlertSent.NEW_ORDER, shopA), "first wins");
        assertFalse(ledger.claim(order.getId(), OrderAlertSent.NEW_ORDER, shopA), "second loses");
        assertEquals(1, alertRows(order));
    }

    // ========================================================== fixtures

    private void register(long account, String token, PushApp app) {
        registrations.remember(account, token, app, "ANDROID", "device-" + token);
    }

    private int alertRows(Order order) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM order_alerts_sent WHERE order_id = ?",
                Integer.class, order.getId());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, String>> captor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    /**
     * A real row in {@code orders}, because {@code order_alerts_sent} has a
     * foreign key to it - the idempotency being tested is the database's.
     */
    private Order placedOrder(long shopId, PaymentStatus payment, OrderStatus status) {
        String number = "GP" + System.nanoTime();
        jdbc.update("""
                INSERT INTO orders (order_number, shop_id, total_amount, order_status, payment_status)
                VALUES (?, ?, ?, ?, ?)
                """, number, shopId, new BigDecimal("520.00"), status.name(), payment.name());
        Long id = jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number = ?", Long.class, number);
        orders.add(id);

        Order order = new Order();
        // Order has no id setter, so the entity is hydrated the way the real
        // dispatcher receives it - by reading it back.
        order.setOrderNumber(number);
        order.setShopId(shopId);
        order.setTotalAmount(new BigDecimal("520.00"));
        order.setOrderStatus(status);
        order.setPaymentStatus(payment);
        setId(order, id);
        return order;
    }

    private static void setId(Order order, Long id) {
        try {
            java.lang.reflect.Field field = Order.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(order, id);
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private Long merchant(String kind) {
        Long id = merchantLifecycle.register(
                "Alert Merchant " + kind + " " + tag, "Alert " + kind,
                null, null, null, true).getId();
        merchantLifecycle.transition(id, MerchantStatus.PENDING_REVIEW, "submitted");
        merchantLifecycle.transition(id, MerchantStatus.APPROVED, "checked");
        merchantLifecycle.transition(id, MerchantStatus.ACTIVE, "trading");
        return id;
    }

    private long shop(Long merchant, String code) {
        long id = shopLifecycle.open(merchant, code, "Alert Shop",
                12.9716, 77.5946, new BigDecimal("5"), "Asia/Kolkata").getId();
        shopLifecycle.transitionAsPlatform(id, ShopStatus.ACTIVE, "ready to trade");
        return id;
    }

    private long account(String kind) {
        String email = tag + "-" + kind + "@example.test";
        jdbc.update("""
                INSERT INTO customers (full_name, email, mobile_number, password, role, active)
                VALUES (?, ?, ?, 'not-a-real-hash', 'ADMIN', true)
                """, "Person " + kind, email,
                "9" + (100000000 + (int) (Math.random() * 899999999)));
        return jdbc.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
    }

    private void staff(long shop, long account, boolean home) {
        jdbc.update("""
                INSERT INTO shop_staff (shop_id, customer_id, is_default, active)
                VALUES (?, ?, ?, true)
                ON CONFLICT (shop_id, customer_id) DO NOTHING
                """, shop, account, home);
    }
}
