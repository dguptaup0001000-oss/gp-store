package com.gpstore.store;

import com.gpstore.support.TestMobileNumbers;
import com.gpstore.dto.request.PlaceOrderRequest;
import com.gpstore.dto.response.PlaceOrderResponse;
import com.gpstore.entity.*;
import com.gpstore.exception.ConflictException;
import com.gpstore.repository.*;
import com.gpstore.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The order path under the shop's own hours and switch.
 *
 * <p>WHY AN END-TO-END TEST AND NOT A UNIT ONE. The claim being made is
 * "the BACKEND refuses, not the button", and the only way to show that is to
 * call the real checkout against a real database with the switch really off.
 * A mock proving OrderService calls a method it was written to call proves
 * nothing about production.
 *
 * <p>THE SWITCH IS SHARED STATE, and this suite runs against a database every
 * other test shares. An OFF row left behind would fail every checkout test
 * that happens to run afterwards, with a symptom pointing nowhere near here -
 * so {@link #restoreTheSwitch()} runs after every test whether it passed,
 * failed, or threw. This codebase has already been bitten once by a test that
 * quietly wrote settings other tests read.
 */
@SpringBootTest(properties = {
        // NO LIVE OUTBOX WORKER. This class places real orders, and a running
        // drain turns each one into an auto-assigned delivery against whichever
        // rider is available - including another test class's fixture riders,
        // because the least-loaded fallback picks globally. Spring caches this
        // context and never closes it, so the worker outlives the class and can
        // still be assigning while a later class asserts on rider workload.
        //
        // That is exactly how TerritoryDispatchTest started failing with
        // "expected: <22> but was: <23>": a stray assignment gave one of two
        // deliberately-tied riders a live order, its score rose, and the tie
        // it was asserting broke the other way.
        //
        // Nothing here tests the outbox or any async side effect, so the drain
        // has no purpose in this class beyond causing that.
        "outbox.drain-interval-ms=3600000"
})
class StoreHoursCheckoutTest {

    @Autowired private OrderService orderService;
    @Autowired private DeliveryScheduleService scheduleService;
    @Autowired private StoreOperationsService operationsService;
    @Autowired private StoreOperationsSettingsRepository settingsRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository productVariantRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private AddressRepository addressRepository;
    @Autowired private CartRepository cartRepository;
    @Autowired private CartItemRepository cartItemRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private com.gpstore.platform.ShopScopeSwitch shopScopeSwitch;
    @Autowired private com.gpstore.platform.ShopRepository shops;
    @Autowired private com.gpstore.platform.PlatformProperties platform;
    @Autowired private com.gpstore.platform.MerchantRepository merchants;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** Shops whose hours this test wrote, so teardown removes exactly those. */
    private final java.util.Set<Long> hoursTouched = new java.util.HashSet<>();
    private Long secondShopId;
    private Long secondMerchantId;

    @Value("${store.latitude}") private double storeLatitude;
    @Value("${store.longitude}") private double storeLongitude;

    /**
     * Puts the switch back to AUTO, on the shop this test actually used.
     *
     * <p>Written against the repository rather than through
     * StoreOperationsService on purpose: the service audits, and a stream of
     * "acceptance changed" entries from a test run is noise in a log that
     * exists to answer a real question about a real day.
     *
     * <p>THIS USED TO RESET {@code findById(SINGLETON_ID)} AND FORCE
     * {@code setId(SINGLETON_ID)}, which was the last live use of a constant
     * that is {@code @Deprecated(forRemoval = true)} and that production
     * stopped using at V49. Two things were wrong with it. The id is
     * {@code @GeneratedValue}, so row 1 is not reliably this shop's settings -
     * the reset could land on another shop's row. And when no row had id 1 it
     * CREATED one, with a forced primary key and a shop stamped from whatever
     * scope happened to be active, leaving a stray settings row behind for
     * {@code findByShopId} to pick up later.
     *
     * <p>Settings are per shop (V49) and are found by shop. So is this.
     */
    @AfterEach
    void restoreTheSwitch() {
        long shopId = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();
        settingsRepository.findByShopId(shopId).ifPresent(settings -> {
            settings.setOrderAcceptance(StoreOrderAcceptance.AUTO);
            settings.setClosureMessage(null);
            settingsRepository.save(settings);
        });

        // ONLY THE HOURS THIS TEST WROTE. Shop #1 is the live shop: hours
        // invented here that outlast the run are a real kirana advertising a
        // week nobody agreed to, and the next test reading them is the mildest
        // of the consequences.
        for (Long touched : hoursTouched) {
            jdbc.update("DELETE FROM shop_business_hours WHERE shop_id = ?", touched);
        }
        hoursTouched.clear();

        if (secondShopId != null) {
            jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", secondShopId);
            jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", secondShopId);
            jdbc.update("DELETE FROM shops WHERE id = ?", secondShopId);
            secondShopId = null;
        }
        if (secondMerchantId != null) {
            jdbc.update("DELETE FROM merchants WHERE id = ?", secondMerchantId);
            secondMerchantId = null;
        }
    }

    // ------------------------------------------------------------------
    // The server decides when.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an order records the delivery window the server chose")
    void orderCarriesTheServersSchedulingDecision() {
        Fixture fixture = newFixture();

        PlaceOrderResponse response = place(fixture);
        Order order = orderRepository.findById(response.getOrderId()).orElseThrow();

        assertNotNull(order.getDeliveryType(),
                "the server must record which window it chose, not leave it to be guessed later");

        // ASKED THE WAY THE ORDER PATH ASKS IT: same function, same shop, same
        // instant. OrderService does
        //
        //     shopScopeSwitch.within(shopId, () -> getStoreStatusAt(placedAt))
        //
        // and records that answer. So this recomputes exactly that.
        //
        // IT USED TO SAMPLE scheduleService.calculateDeliveryType() BEFORE AND
        // AFTER and accept either, which is a different question in two ways
        // and is why CI failed while every local run passed. calculateDelivery-
        // Type() reads now() rather than the order's pinned placedAt, and it
        // reads the AMBIENT shop rather than the order's - DeliveryScheduleSer-
        // vice.schedule() resolves hours through shopHours.forCurrentShop().
        // Whenever the basket resolved to a shop other than the ambient one,
        // the test compared one shop's clock against another shop's decision
        // and reported "recorded NEXT_MORNING, but the server's own answer was
        // SAME_DAY then SAME_DAY". Neither number was wrong; the comparison
        // was.
        //
        // This is a STRONGER assertion, not a weaker one: it pins one exact
        // expected value instead of accepting either of two samples, and it
        // cannot drift with the wall clock because it reuses the order's own
        // recorded instant.
        Instant placedAt = order.getOrderDate().toInstant(java.time.ZoneOffset.UTC);
        DeliveryType decidedTheWayProductionDecides = shopScopeSwitch.within(
                order.getShopId(), () -> scheduleService.getStoreStatusAt(placedAt).deliveryType());

        assertEquals(decidedTheWayProductionDecides, order.getDeliveryType(),
                "the order recorded " + order.getDeliveryType() + ", but shop "
                        + order.getShopId() + " at " + placedAt + " decides "
                        + decidedTheWayProductionDecides);

        LocalDate today = scheduleService.now()
                .atZone(scheduleService.getProperties().getZone()).toLocalDate();
        assertNotNull(order.getScheduledDeliveryDate(), "an order must know which day it is for");
        assertFalse(order.getScheduledDeliveryDate().isBefore(today),
                "an order cannot be scheduled for a day that has already passed");
    }

    /**
     * THE EXACT DISCREPANCY THAT TURNED CI RED, PINNED.
     *
     * <p>Two shops, the same instant, different opening hours. The delivery
     * promise is per shop (V49 and the locked rule that shop hours and
     * order-acceptance hours belong to the shop), so at one moment the two
     * shops can and must give different answers - and asking the WRONG shop
     * produces precisely the failure this class reported for four runs:
     * "recorded NEXT_MORNING, but the server's own answer was SAME_DAY".
     *
     * <p>Nothing here is about the wall clock. The hours are chosen relative
     * to the shop's own current time, so the test means the same thing at
     * 09:27 as at midnight, and the instant is pinned once and reused for both
     * shops rather than sampled twice.
     */
    @Test
    @DisplayName("two shops, one instant: the delivery promise is the shop's, not the ambient one's")
    void theDeliveryPromiseBelongsToTheShopBeingAskedAbout() {
        long shopA = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();
        long shopB = aSecondShop();

        // A CHOSEN INSTANT, NOT now(). getStoreStatusAt takes the moment to
        // judge, so this test fixes it at 10:00 today in the shop's own zone
        // and the answer is the same whether CI runs at 04:00 or at midnight.
        //
        // Deriving the window from now() instead - "opens two hours ago,
        // closes in two" - reads well and is a trap: near midnight it wraps
        // past the end of the day and violates ck_shop_hours_order
        // (closes_at > opens_at), so the test would fail for an hour a day for
        // a reason that has nothing to do with what it checks.
        java.time.ZonedDateTime tenToday = scheduleService.now()
                .atZone(scheduleService.shopZone())
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        Instant at = tenToday.toInstant();

        // Shop A trades across 10:00. Shop B shut at 08:00.
        setWeek(shopA, java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0));
        setWeek(shopB, java.time.LocalTime.of(6, 0), java.time.LocalTime.of(8, 0));

        DeliveryType forA = shopScopeSwitch.within(shopA,
                () -> scheduleService.getStoreStatusAt(at).deliveryType());
        DeliveryType forB = shopScopeSwitch.within(shopB,
                () -> scheduleService.getStoreStatusAt(at).deliveryType());

        assertEquals(DeliveryType.SAME_DAY, forA,
                "shop A is open across this instant, so it can still deliver today");
        assertNotEquals(forA, forB,
                "shop B closed an hour ago and cannot promise what shop A promises - if these "
                        + "agree, the schedule is not being read per shop and every order in a "
                        + "two-shop basket would inherit one shop's hours");

        // AND THE ORDER RECORDS ITS OWN SHOP'S ANSWER. This is the half that
        // was actually broken: not the production decision, but asking for it
        // in the wrong scope.
        Fixture fixture = newFixture();
        PlaceOrderResponse response = place(fixture);
        Order order = orderRepository.findById(response.getOrderId()).orElseThrow();

        Instant placedAt = order.getOrderDate().toInstant(java.time.ZoneOffset.UTC);
        assertEquals(
                shopScopeSwitch.within(order.getShopId(),
                        () -> scheduleService.getStoreStatusAt(placedAt).deliveryType()),
                order.getDeliveryType(),
                "the order must carry the answer for ITS shop at ITS instant");
    }

    @Test
    @DisplayName("a same-day order is for today; a night order is for the next opening")
    void theScheduledDateAgreesWithTheType() {
        Fixture fixture = newFixture();
        PlaceOrderResponse response = place(fixture);
        Order order = orderRepository.findById(response.getOrderId()).orElseThrow();

        LocalDate today = scheduleService.now()
                .atZone(scheduleService.getProperties().getZone()).toLocalDate();

        // The two columns must tell the same story. A SAME_DAY order dated
        // tomorrow would be a receipt that contradicts itself.
        if (order.getDeliveryType() == DeliveryType.SAME_DAY) {
            assertEquals(today, order.getScheduledDeliveryDate(),
                    "a same-day order is delivered today, by definition");
        } else {
            assertTrue(!order.getScheduledDeliveryDate().isBefore(today),
                    "a next-morning order is for today's 09:00 at the earliest");
        }
    }

    @Test
    @DisplayName("the request has no way to ask for a delivery date")
    void theClientCannotSupplyADeliveryDate() {
        // STRUCTURAL, not behavioural, and stronger for it. A test that sends
        // a date and checks it was ignored only proves today's code ignores
        // it; this proves there is no field to read, so a future change that
        // starts trusting the client has to add one and trip this first.
        for (java.lang.reflect.Field field : PlaceOrderRequest.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase();
            assertFalse(
                    name.contains("deliverydate") || name.contains("deliverytype")
                            || name.contains("scheduled") || name.contains("slot"),
                    "PlaceOrderRequest." + field.getName() + " lets the client choose its own "
                            + "delivery window - the server must decide this");
        }
    }

    // ------------------------------------------------------------------
    // The backend refuses, not the button.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("checkout is rejected when the owner has paused orders")
    void checkoutIsRefusedWhenOrdersArePaused() {
        Fixture fixture = newFixture();

        operationsService.setOrderAcceptance(
                StoreOrderAcceptance.OFF, "Back at 9am", "test");

        ConflictException refused = assertThrows(ConflictException.class,
                () -> place(fixture),
                "a paused shop must refuse the order in the SERVICE, not merely in the app - "
                        + "a disabled Flutter button does not stop a replayed request");

        assertTrue(refused.getMessage().contains("Back at 9am"),
                "the customer should be told the shop's own reason, not a generic error: "
                        + refused.getMessage());

        // And nothing was written. A rejection that half-created an order
        // would leave stock reserved for a purchase that never happened.
        assertEquals(0, orderRepository.findByCustomerIdOrderByOrderDateDesc(
                fixture.customerId, org.springframework.data.domain.PageRequest.of(0, 1))
                .getTotalElements(), "a refused checkout must not create an order");
    }

    @Test
    @DisplayName("the cart survives a refusal, so nothing is lost")
    void aRefusedCheckoutLeavesTheCartAlone() {
        Fixture fixture = newFixture();
        operationsService.setOrderAcceptance(StoreOrderAcceptance.OFF, null, "test");

        assertThrows(ConflictException.class, () -> place(fixture));

        assertFalse(cartItemRepository.findByCartId(fixture.cartId).isEmpty(),
                "a customer refused at 3am must find their basket still there in the morning");
    }

    @Test
    @DisplayName("forcing orders ON takes them regardless of the hour")
    void forcedOnAcceptsOrders() {
        Fixture fixture = newFixture();
        operationsService.setOrderAcceptance(StoreOrderAcceptance.ON, null, "test");

        PlaceOrderResponse response = place(fixture);
        assertTrue(response.isSuccess());
        assertNotNull(response.getOrderId());
    }

    @Test
    @DisplayName("AUTO takes orders at whatever time this test happens to run")
    void autoAcceptsOrdersAtAnyHour() {
        // The headline promise, asserted at whatever o'clock CI runs. There is
        // no hour at which this may fail.
        Fixture fixture = newFixture();
        assertTrue(scheduleService.isStoreAcceptingOrders(),
                "AUTO must take orders round the clock - it was "
                        + scheduleService.now().atZone(scheduleService.getProperties().getZone()));
        assertTrue(place(fixture).isSuccess());
    }

    @Test
    @DisplayName("pausing orders never closes the catalogue")
    void browsingSurvivesThePause() {
        operationsService.setOrderAcceptance(StoreOrderAcceptance.OFF, "Stocktake", "test");

        StoreStatus status = scheduleService.getStoreStatus();
        assertTrue(status.browsingOpen(), "the shop must remain shoppable 24 hours");
        assertFalse(status.acceptingOrders());
        assertEquals("Stocktake", status.closureReason());
    }

    // ------------------------------------------------------------------
    // Fixture.
    // ------------------------------------------------------------------

    private record Fixture(Long customerId, Long addressId, Long cartId) {}

    /** A second shop, so "per shop" can mean something. Removed in teardown. */
    private long aSecondShop() {
        com.gpstore.platform.Merchant m = new com.gpstore.platform.Merchant();
        m.setLegalName("Hours fixture " + System.nanoTime());
        m.setDisplayName("Hours fixture");
        m.setStatus(com.gpstore.platform.MerchantStatus.ACTIVE);
        m.setIsDemo(Boolean.TRUE);
        m.setActive(Boolean.TRUE);
        secondMerchantId = merchants.save(m).getId();

        com.gpstore.platform.Shop b = new com.gpstore.platform.Shop();
        b.setMerchantId(secondMerchantId);
        b.setCode("HRS-" + System.nanoTime());
        b.setDisplayName("Hours fixture shop");
        b.setStatus(com.gpstore.platform.ShopStatus.ACTIVE);
        b.setIsDemo(Boolean.TRUE);
        b.setActive(Boolean.TRUE);
        secondShopId = shops.save(b).getId();
        return secondShopId;
    }

    /** Gives one shop the same hours on every day of the week. */
    private void setWeek(long shopId, java.time.LocalTime opens, java.time.LocalTime closes) {
        jdbc.update("DELETE FROM shop_business_hours WHERE shop_id = ?", shopId);
        for (int day = 1; day <= 7; day++) {
            // java.sql.Time, not the LocalTime and not its toString(). The
            // columns are "time without time zone" and the driver will not
            // take a String for one through a PreparedStatement - the first
            // version of this helper failed with BadSqlGrammarException, while
            // the neighbouring test that inlines '08:00' as a literal works.
            jdbc.update("INSERT INTO shop_business_hours (shop_id, day_of_week, opens_at, closes_at)"
                    + " VALUES (?, ?, ?, ?)",
                    shopId, day, java.sql.Time.valueOf(opens), java.sql.Time.valueOf(closes));
        }
        hoursTouched.add(shopId);
    }

    private PlaceOrderResponse place(Fixture fixture) {
        PlaceOrderRequest request = new PlaceOrderRequest();
        request.setAddressId(fixture.addressId);
        request.setPaymentMethod("COD");
        return orderService.placeOrder(request, fixture.customerId,
                "store-hours-" + System.nanoTime());
    }

    private Fixture newFixture() {
        Customer customer = new Customer();
        customer.setFullName("Store Hours Test Customer");
        customer.setEmail("store-hours-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber(TestMobileNumbers.unique());
        customer.setPassword("irrelevant-for-this-test");
        customer.setEnabled(true);
        customer.setActive(true);
        customer = customerRepository.save(customer);

        Address address = new Address();
        address.setCustomer(customer);
        address.setFullName(customer.getFullName());
        address.setMobileNumber(customer.getMobileNumber());
        address.setHouseNo("1");
        address.setArea("Test Area");
        address.setCity("Test City");
        address.setState("Test State");
        address.setPincode("110001");
        address.setCountry("India");
        // The shop's own coordinates: zero distance, so serviceable whatever
        // the radius is configured to.
        address.setLatitude(storeLatitude);
        address.setLongitude(storeLongitude);
        address.setDefaultAddress(true);
        address = addressRepository.save(address);

        Category category = new Category();
        category.setName("Store Hours Category " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        category = categoryRepository.save(category);

        Product product = new Product();
        product.setName("Store Hours Item " + System.nanoTime());
        product.setBrand("TestBrand");
        product.setActive(true);
        product.setCategory(category);
        product = productRepository.save(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setQuantity(1.0);
        variant.setUnit("pc");
        variant.setMrp(new BigDecimal("100.00"));
        variant.setSellingPrice(new BigDecimal("90.00"));
        variant.setAvailable(true);
        variant.setActive(true);
        variant = productVariantRepository.save(variant);

        Inventory inventory = new Inventory();
        inventory.setProductVariant(variant);
        inventory.setStock(50);
        inventoryRepository.save(inventory);

        Cart cart = new Cart();
        cart.setCustomer(customer);
        cart = cartRepository.save(cart);

        CartItem item = new CartItem();
        item.setCart(cart);
        item.setProductVariant(variant);
        item.setQuantity(2);
        item.setPrice(new BigDecimal("90.00"));
        cartItemRepository.save(item);

        return new Fixture(customer.getId(), address.getId(), cart.getId());
    }
}
