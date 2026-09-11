package com.gpstore.order.cancellation;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Order;
import com.gpstore.entity.StoreOperationsSettings;
import com.gpstore.enums.OrderFault;
import com.gpstore.exception.BadRequestException;
import com.gpstore.platform.Merchant;
import com.gpstore.platform.MerchantRepository;
import com.gpstore.platform.MerchantStatus;
import com.gpstore.platform.PlatformMode;
import com.gpstore.platform.PlatformProperties;
import com.gpstore.platform.Shop;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.ShopStatus;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantDefaults;
import com.gpstore.platform.TenantScope;
import com.gpstore.repository.StoreOperationsSettingsRepository;
import com.gpstore.store.StoreOperationsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WHAT IT COSTS TO CHANGE YOUR MIND, AND WHO DECIDES (Part 3 §9-§12).
 *
 * <p>FOUR RULES PULL IN DIFFERENT DIRECTIONS and all four are asserted here,
 * because each one is a promise to a different person:
 *
 * <ul>
 *   <li>§9 - the five-second window stays. It is the one piece of this the app
 *       already had, and it existed only as a timer on a screen.</li>
 *   <li>§10 - the MERCHANT sets the charge, the platform caps it, and the
 *       customer sees it before confirming.</li>
 *   <li>§11 - a COD order collected nothing, so the charge becomes a debt to
 *       that shop rather than a deduction from a refund that does not exist.</li>
 *   <li>§12 - if the shop could not fulfil the order, the customer pays
 *       nothing. No arrangement of settings may get past this.</li>
 * </ul>
 *
 * <p>AND THE WHOLE THING IS PER SHOP, so the last group asks the question §6
 * makes unavoidable: can one kirana's terms reach another kirana's customer,
 * and can one kirana read what a customer owes its competitor.
 */
@SpringBootTest(properties = {
        "platform.mode=MULTI_SHOP_PRODUCTION",
        "platform.cancellation.max-fee-percent=5",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("A cancellation has a price, and the shop sets it")
class ACancellationHasAPriceTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private CancellationPolicy policy;
    @Autowired private CancellationDues dues;
    @Autowired private CustomerCancellationDuesRepository dueRows;
    @Autowired private StoreOperationsService operations;
    @Autowired private StoreOperationsSettingsRepository settings;

    private final String tag = "cnc" + System.nanoTime();

    private long shopA;
    private long shopB;
    private Long merchantId;

    /** Synthetic ids: nothing here is a real order row, and nothing needs to be. */
    private final long customerId = 900_000_000L + (System.nanoTime() % 1_000_000L);
    private long nextOrderId = 800_000_000L + (System.nanoTime() % 1_000_000L);

    @BeforeEach
    void twoShops() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
        shopA = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();

        Merchant m = new Merchant();
        m.setLegalName("Cancellation fixture " + tag);
        m.setDisplayName("Cancellation fixture");
        m.setStatus(MerchantStatus.ACTIVE);
        m.setIsDemo(Boolean.TRUE);
        m.setActive(Boolean.TRUE);
        merchantId = merchants.save(m).getId();

        Shop b = new Shop();
        b.setMerchantId(merchantId);
        b.setCode("CNC-" + tag);
        b.setDisplayName("The shop next door");
        b.setStatus(ShopStatus.ACTIVE);
        b.setLatitude(27.16);
        b.setLongitude(83.94);
        b.setMaxDeliveryRadiusKm(new BigDecimal("5"));
        b.setTimeZone("Asia/Kolkata");
        b.setIsDemo(Boolean.TRUE);
        b.setActive(Boolean.TRUE);
        shopB = shops.save(b).getId();

        // Shop B needs a settings row to have terms at all; shop #1 has had
        // one since V49.
        inShop(shopB, () -> {
            StoreOperationsSettings row = new StoreOperationsSettings();
            row.setShopId(shopB);
            settings.save(row);
        });
    }

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        // SHOP #1 IS THE LIVE SHOP. A cancellation fee this test invented must
        // not survive it - a real kirana would start charging for something
        // nobody agreed to.
        jdbc.update("UPDATE store_operations_settings SET cancellation_fee_percent = NULL, "
                + "cancellation_charges_delivery = FALSE, free_cancellation_seconds = 5 "
                + "WHERE shop_id = ?", shopA);
        jdbc.update("DELETE FROM customer_cancellation_dues WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM shops WHERE id = ?", shopB);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    // ------------------------------------------------------------------ §9

    @Nested
    @DisplayName("§9 the five-second window")
    class TheFreeWindow {

        @Test
        @DisplayName("a shop that has set nothing still gives five free seconds")
        void fiveSecondsIsTheDefault() {
            CancellationTerms terms = policy.termsFor(shopB);
            assertEquals(5, terms.freeSeconds(),
                    "THE COUNTDOWN THE APP HAS ALWAYS DRAWN. §9 says it must remain, and a "
                            + "default that quietly became zero would remove it for every shop "
                            + "that never opened the settings screen.");
            assertFalse(terms.chargesAFee(), "and charges nothing by default");
        }

        @Test
        @DisplayName("cancelling inside the window is free even where the shop charges")
        void insideTheWindowNothingIsCharged() {
            chargeOnShopA("5");

            LocalDateTime placed = utcNow();
            Order order = order(shopA, placed, "600.00", "40.00");

            CancellationCharge charge = policy.quote(order, OrderFault.CUSTOMER, placed.plusSeconds(2));

            assertTrue(charge.isFree(), "two seconds after ordering, cancelling is free");
            assertTrue(charge.withinFreeWindow());
            assertNotNull(charge.freeUntil(), "and the customer is told when it stops being free");
        }

        @Test
        @DisplayName("the window is the server's, not a screen's timer")
        void pastTheWindowTheChargeApplies() {
            chargeOnShopA("5");

            LocalDateTime placed = utcNow();
            Order order = order(shopA, placed, "600.00", "40.00");

            CancellationCharge charge = policy.quote(order, OrderFault.CUSTOMER, placed.plusSeconds(6));

            assertFalse(charge.isFree(),
                    "A CLIENT THAT SIMPLY DID NOT DRAW THE TIMER, or drew it slowly, used to "
                            + "decide this. The server that takes the money decides it now.");
            assertFalse(charge.withinFreeWindow());
        }

        @Test
        @DisplayName("a generous shop can widen it, and only for itself")
        void aShopMayWidenTheWindow() {
            inShop(shopA, () -> operations.setCancellationTerms(120, new BigDecimal("3"), null, "test"));

            assertEquals(120, policy.termsFor(shopA).freeSeconds());
            assertEquals(5, policy.termsFor(shopB).freeSeconds(),
                    "and the shop next door still has the five it always had");
        }
    }

    // ----------------------------------------------------------------- §10

    @Nested
    @DisplayName("§10 the merchant's charge, inside the platform's cap")
    class TheCharge {

        @Test
        @DisplayName("the charge is a percentage of what was bought, quoted with its working")
        void theChargeIsQuotedInFull() {
            chargeOnShopA("2");

            LocalDateTime placed = utcNow().minusMinutes(10);
            Order order = order(shopA, placed, "640.00", "40.00");

            CancellationCharge charge = policy.quote(order, OrderFault.CUSTOMER, utcNow());

            assertEquals(new BigDecimal("12.00"), charge.amount(),
                    "2% of the 600 of goods - the 40 of delivery is not the shop's to charge on");
            assertEquals(new BigDecimal("600.00"), charge.chargeableBase());
            // compareTo, not equals: the column is NUMERIC(5,2), so the rate
            // comes back as 2.00 and "2" would be asserting on the scale
            // Postgres chose rather than on the rate the shop set.
            assertEquals(0, new BigDecimal("2").compareTo(charge.percentApplied()));
            assertNotNull(charge.explanation());
            assertFalse(charge.explanation().isBlank(),
                    "§10 SAYS THE CUSTOMER MUST SEE THE CHARGE BEFORE CONFIRMING, and a bare "
                            + "number on a dialog is not something a person can agree to.");
        }

        @Test
        @DisplayName("delivery is charged on only where the shop asked for it")
        void deliveryIsOptedInto() {
            inShop(shopA, () ->
                    operations.setCancellationTerms(null, new BigDecimal("2"), true, "test"));

            Order order = order(shopA, utcNow().minusMinutes(10), "640.00", "40.00");
            CancellationCharge charge = policy.quote(order, OrderFault.CUSTOMER, utcNow());

            assertEquals(new BigDecimal("12.80"), charge.amount(), "2% of the whole 640");
            assertEquals(new BigDecimal("640.00"), charge.chargeableBase());
        }

        @Test
        @DisplayName("the platform refuses a fee above its cap, out loud")
        void aFeeAboveTheCapIsRefused() {
            BadRequestException refused = assertThrows(BadRequestException.class,
                    () -> inShop(shopA, () ->
                            operations.setCancellationTerms(null, new BigDecimal("40"), null, "test")));

            assertTrue(refused.getMessage().contains("5"),
                    "and says what the limit is: " + refused.getMessage());

            assertFalse(policy.termsFor(shopA).chargesAFee(),
                    "SILENTLY CLAMPING WOULD BE WORSE. A shopkeeper believing they charge 40% "
                            + "and a customer paying 5% first hear about it in an argument.");
        }

        @Test
        @DisplayName("a row carrying more than the cap is still charged at the cap")
        void theQuoteClampsWhateverTheRowSays() {
            // Straight past the service, the way a bad migration or a hand-run
            // UPDATE would. The DB check allows it deliberately (it guards a
            // slipped decimal point, not policy); the quote must not.
            jdbc.update("UPDATE store_operations_settings SET cancellation_fee_percent = 40 "
                    + "WHERE shop_id = ?", shopA);

            Order order = order(shopA, utcNow().minusMinutes(10), "600.00", "0.00");
            CancellationCharge charge = policy.quote(order, OrderFault.CUSTOMER, utcNow());

            assertEquals(new BigDecimal("30.00"), charge.amount(),
                    "5% of 600, not 40% - the cap is enforced where the money is worked out, "
                            + "not only where the form is validated");
            assertEquals(new BigDecimal("5"), charge.percentApplied());
        }

        @Test
        @DisplayName("a shop that charges nothing says so, and nothing is taken")
        void nothingIsTheDefaultAnswer() {
            Order order = order(shopB, utcNow().minusMinutes(10), "600.00", "0.00");
            CancellationCharge charge = policy.quote(order, OrderFault.CUSTOMER, utcNow());

            assertTrue(charge.isFree());
            assertEquals(BigDecimal.ZERO, charge.amount());
        }
    }

    // ----------------------------------------------------------------- §12

    @Nested
    @DisplayName("§12 the shop's failure is not the customer's bill")
    class WhoseFault {

        @Test
        @DisplayName("a merchant-fault cancellation costs the customer nothing")
        void merchantFaultIsFree() {
            chargeOnShopA("5");

            Order order = order(shopA, utcNow().minusHours(1), "2000.00", "0.00");
            CancellationCharge charge = policy.quote(order, OrderFault.MERCHANT, utcNow());

            assertTrue(charge.isFree(),
                    "THE SHOP COULD NOT FULFIL IT. Billing the customer for the shop's own "
                            + "stock-out is exactly the unfairness §12 exists to prevent.");
        }

        @Test
        @DisplayName("an undecided fault is not billed either")
        void anOpenQuestionIsNotBilled() {
            chargeOnShopA("5");

            Order order = order(shopA, utcNow().minusHours(1), "2000.00", "0.00");
            assertTrue(policy.quote(order, null, utcNow()).isFree(),
                    "a platform cancellation during a dispute has decided nobody's fault yet, "
                            + "and a customer is not charged on the strength of an open question");
            assertTrue(policy.quote(order, OrderFault.NOBODY, utcNow()).isFree());
        }
    }

    // ----------------------------------------------------------------- §11

    @Nested
    @DisplayName("§11 a COD cancellation leaves a debt")
    class TheDebt {

        @Test
        @DisplayName("the debt is recorded once, however many times the cancellation is retried")
        void recordingIsIdempotent() {
            Order order = order(shopA, utcNow().minusMinutes(10), "600.00", "0.00");

            inShop(shopA, () -> {
                dues.record(order, new BigDecimal("12.00"), "Cancellation charge");
                dues.record(order, new BigDecimal("12.00"), "Cancellation charge");
            });

            List<CustomerCancellationDue> mine =
                    inShop(shopA, () -> dues.outstandingFor(customerId));
            assertEquals(1, mine.size(),
                    "A DOUBLE-TAP ON CANCEL MUST NOT BILL SOMEBODY TWICE for changing their "
                            + "mind once. The unique index on order_id is what actually stops it.");
            assertEquals(0, new BigDecimal("12.00").compareTo(mine.get(0).getAmount()));
        }

        @Test
        @DisplayName("the customer can see what they owe before it appears on a bill")
        void theDebtIsVisible() {
            Order order = order(shopA, utcNow().minusMinutes(10), "600.00", "0.00");
            inShop(shopA, () -> dues.record(order, new BigDecimal("12.00"), "Cancellation charge"));

            assertEquals(0, new BigDecimal("12.00").compareTo(
                            inShop(shopA, () -> dues.totalOutstandingFor(customerId))),
                    "A DEBT DISCOVERED AT AN UNRELATED CHECKOUT WEEKS LATER is "
                            + "indistinguishable from an overcharge.");
        }

        @Test
        @DisplayName("collecting it on a later order closes it, naming that order")
        void settlingNamesTheOrder() {
            Order order = order(shopA, utcNow().minusMinutes(10), "600.00", "0.00");
            inShop(shopA, () -> dues.record(order, new BigDecimal("12.00"), "Cancellation charge"));

            long laterOrder = nextOrderId++;
            inShop(shopA, () -> dues.settleOn(customerId, laterOrder));

            assertTrue(inShop(shopA, () -> dues.outstandingFor(customerId)).isEmpty(),
                    "nothing is owed twice");
            CustomerCancellationDue settled =
                    inShop(shopA, () -> dues.historyFor(customerId)).get(0);
            assertEquals(DueStatus.SETTLED, settled.getStatus());
            assertEquals(laterOrder, settled.getSettledOrderId(),
                    "and the order it was collected on is on the record, so it can be explained");
        }
    }

    // ------------------------------------------------------------ isolation

    @Nested
    @DisplayName("§6 none of this crosses the counter between two shops")
    class OneShopAtATime {

        @Test
        @DisplayName("one shop's cancellation terms are not another's")
        void termsDoNotLeak() {
            chargeOnShopA("4");

            Order fromA = order(shopA, utcNow().minusMinutes(10), "500.00", "0.00");
            Order fromB = order(shopB, utcNow().minusMinutes(10), "500.00", "0.00");

            assertEquals(new BigDecimal("20.00"),
                    policy.quote(fromA, OrderFault.CUSTOMER, utcNow()).amount());
            assertTrue(policy.quote(fromB, OrderFault.CUSTOMER, utcNow()).isFree(),
                    "ONE MERCHANT DECIDING TO CHARGE MUST NOT MAKE THE SHOP NEXT DOOR CHARGE. "
                            + "Before per-shop settings this was not a question anybody could ask.");
        }

        @Test
        @DisplayName("a shop cannot see what a customer owes its competitor")
        void debtsDoNotLeak() {
            Order fromA = order(shopA, utcNow().minusMinutes(10), "600.00", "0.00");
            inShop(shopA, () -> dues.record(fromA, new BigDecimal("12.00"), "Cancellation charge"));

            assertFalse(inShop(shopA, () -> dues.outstandingFor(customerId)).isEmpty(),
                    "the shop that is owed it can see it");
            assertTrue(inShop(shopB, () -> dues.outstandingFor(customerId)).isEmpty(),
                    "AND ITS COMPETITOR CANNOT. \"This customer cancels a lot\" read off "
                            + "another shop's ledger is a competitive leak, not an "
                            + "operational need - and it is enforced by the shop filter, not "
                            + "by a screen that declines to draw it.");
        }

        @Test
        @DisplayName("the debt is booked to the shop that is owed it, not to the scope")
        void theDebtBelongsToTheOrdersShop() {
            Order fromA = order(shopA, utcNow().minusMinutes(10), "600.00", "0.00");
            // Booked from the platform scope, the way a background or admin
            // path would reach it.
            TenantContext.runWithin(TenantScope.platform(),
                    () -> dues.record(fromA, new BigDecimal("12.00"), "Cancellation charge"));

            Long owedTo = jdbc.queryForObject(
                    "SELECT shop_id FROM customer_cancellation_dues WHERE customer_id = ?",
                    Long.class, customerId);
            assertEquals(shopA, owedTo);
        }
    }

    // ------------------------------------------------------------- fixtures

    private static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** An order that was never saved - the quote reads fields, not rows. */
    private Order order(long shopId, LocalDateTime placedAt, String total, String delivery) {
        Order order = new Order();
        order.setId(nextOrderId++);
        order.setOrderNumber("TEST-" + order.getId());
        order.setShopId(shopId);
        order.setOrderDate(placedAt);
        order.setTotalAmount(new BigDecimal(total));
        order.setDeliveryFee(new BigDecimal(delivery));
        Customer customer = new Customer();
        customer.setId(customerId);
        order.setCustomer(customer);
        return order;
    }

    private void chargeOnShopA(String percent) {
        inShop(shopA, () ->
                operations.setCancellationTerms(null, new BigDecimal(percent), null, "test"));
    }

    private <T> T inShop(long shopId, java.util.function.Supplier<T> work) {
        return TenantContext.runWithin(TenantScope.ofShop(shopId), work::get);
    }

    private void inShop(long shopId, Runnable work) {
        TenantContext.runWithin(TenantScope.ofShop(shopId), work);
    }
}
