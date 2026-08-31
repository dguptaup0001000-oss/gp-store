package com.gpstore.store;

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

    @Value("${store.latitude}") private double storeLatitude;
    @Value("${store.longitude}") private double storeLongitude;

    /**
     * Puts the switch back to AUTO, always.
     *
     * <p>Written against the repository rather than through
     * StoreOperationsService on purpose: the service audits, and a stream of
     * "acceptance changed" entries from a test run is noise in a log that
     * exists to answer a real question about a real day.
     */
    @AfterEach
    void restoreTheSwitch() {
        StoreOperationsSettings settings = settingsRepository
                .findById(StoreOperationsSettings.SINGLETON_ID)
                .orElseGet(StoreOperationsSettings::new);
        settings.setId(StoreOperationsSettings.SINGLETON_ID);
        settings.setOrderAcceptance(StoreOrderAcceptance.AUTO);
        settings.setClosureMessage(null);
        settingsRepository.save(settings);
    }

    // ------------------------------------------------------------------
    // The server decides when.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an order records the delivery window the server chose")
    void orderCarriesTheServersSchedulingDecision() {
        Fixture fixture = newFixture();

        // Captured before and after, because the boundary could fall between
        // them: at 20:59:59.999 the expected type genuinely changes mid-test.
        // Asserting membership of that two-element set is honest; asserting
        // one exact value would be a test that fails once a day.
        DeliveryType before = scheduleService.calculateDeliveryType();
        PlaceOrderResponse response = place(fixture);
        DeliveryType after = scheduleService.calculateDeliveryType();

        Order order = orderRepository.findById(response.getOrderId()).orElseThrow();

        assertNotNull(order.getDeliveryType(),
                "the server must record which window it chose, not leave it to be guessed later");
        assertTrue(order.getDeliveryType() == before || order.getDeliveryType() == after,
                "recorded " + order.getDeliveryType() + ", but the server's own answer was "
                        + before + " then " + after);

        LocalDate today = scheduleService.now()
                .atZone(scheduleService.getProperties().getZone()).toLocalDate();
        assertNotNull(order.getScheduledDeliveryDate(), "an order must know which day it is for");
        assertFalse(order.getScheduledDeliveryDate().isBefore(today),
                "an order cannot be scheduled for a day that has already passed");
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
        customer.setMobileNumber("9" + String.valueOf(System.nanoTime()).substring(0, 9));
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
