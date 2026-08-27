package com.gpstore.service;

import com.gpstore.dto.request.PlaceOrderRequest;
import com.gpstore.dto.response.PlaceOrderResponse;
import com.gpstore.entity.Address;
import com.gpstore.entity.Cart;
import com.gpstore.entity.CartItem;
import com.gpstore.entity.Category;
import com.gpstore.entity.Customer;
import com.gpstore.entity.IdempotencyRecord;
import com.gpstore.entity.Inventory;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.repository.AddressRepository;
import com.gpstore.repository.CartItemRepository;
import com.gpstore.repository.CartRepository;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.IdempotencyRecordRepository;
import com.gpstore.repository.InventoryRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3/4/5: the checkout idempotency contract.
 *
 * Before this work an Idempotency-Key only answered "has this key been used
 * before". That cannot tell a RETRIED checkout apart from a key REUSED for a
 * different checkout, so reusing one silently replayed the first order - the
 * customer never received the second one and nothing anywhere reported a
 * problem. The key was also optional, and the Flutter client never sent one,
 * so in practice real checkout had no duplicate protection at all.
 *
 * Every assertion here is on database state (how many orders exist, what the
 * stored record says) rather than only on the HTTP-ish return value, because
 * the failure mode is a wrong number of real orders.
 */
@SpringBootTest
class IdempotencyFingerprintTest {

    @Autowired private OrderService orderService;
    @Autowired private com.gpstore.service.PaymentService paymentService;
    @Autowired private com.gpstore.repository.PaymentRepository paymentRepository;
    @Autowired private IdempotencyRecordRepository idempotencyRecordRepository;
    @Autowired private IdempotencyRetentionService retentionService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private AddressRepository addressRepository;
    @Autowired private CartRepository cartRepository;
    @Autowired private CartItemRepository cartItemRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository productVariantRepository;
    @Autowired private InventoryRepository inventoryRepository;

    @Value("${store.latitude}") private double storeLatitude;
    @Value("${store.longitude}") private double storeLongitude;

    /** Phase 3: a missing key is rejected outright rather than silently accepted. */
    @Test
    void placingAnOrderWithoutAnIdempotencyKeyIsRejected() {
        Fixture fixture = newCheckoutReadyCustomer(2);

        assertThrows(BadRequestException.class,
                () -> orderService.placeOrder(request(fixture), fixture.customerId, null));
        assertThrows(BadRequestException.class,
                () -> orderService.placeOrder(request(fixture), fixture.customerId, "   "));

        assertEquals(0, ordersFor(fixture.customerId),
                "A rejected checkout must not have created an order");
    }

    /** TEST 1: same key + same request replays instead of placing a second order. */
    @Test
    void sameKeySameRequestReplaysTheOriginalOrder() {
        Fixture fixture = newCheckoutReadyCustomer(2);
        String key = UUID.randomUUID().toString();

        PlaceOrderResponse first = orderService.placeOrder(request(fixture), fixture.customerId, key);
        PlaceOrderResponse replay = orderService.placeOrder(request(fixture), fixture.customerId, key);

        assertEquals(first.getOrderId(), replay.getOrderId(),
                "A retry must return the ORIGINAL order, not a new one");
        assertEquals(1, ordersFor(fixture.customerId),
                "Exactly one real order may exist for one idempotency key");
    }

    @Test
    void concurrentSameKeyPlaceOrderCreatesExactlyOneOrder() throws InterruptedException {
        Fixture fixture = newCheckoutReadyCustomer(2);
        String key = UUID.randomUUID().toString();
        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    PlaceOrderResponse response = orderService.placeOrder(
                            request(fixture), fixture.customerId, key);
                    if (response != null && response.isSuccess()) {
                        successes.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, ordersFor(fixture.customerId),
                "Concurrent retries of the same key must not create duplicate orders");
        assertTrue(successes.get() >= 1, "at least one caller must receive the order");
    }

    /**
     * TEST 2: same key, different quantity.
     *
     * The basket lives server-side rather than in the request body, so the
     * fingerprint has to include the cart lines - otherwise a changed
     * quantity is indistinguishable from a retry.
     */
    @Test
    void sameKeyDifferentQuantityIsRejected() {
        Fixture fixture = newCheckoutReadyCustomer(2);
        String key = UUID.randomUUID().toString();

        orderService.placeOrder(request(fixture), fixture.customerId, key);

        // Refill the cart with a DIFFERENT quantity of the same variant.
        addToCart(fixture, fixture.variantId, 5);

        assertThrows(ConflictException.class,
                () -> orderService.placeOrder(request(fixture), fixture.customerId, key));
        assertEquals(1, ordersFor(fixture.customerId));
    }

    /** TEST 3: same key, different product. */
    @Test
    void sameKeyDifferentProductIsRejected() {
        Fixture fixture = newCheckoutReadyCustomer(2);
        String key = UUID.randomUUID().toString();

        orderService.placeOrder(request(fixture), fixture.customerId, key);

        Long otherVariant = createVariantWithStock(50);
        addToCart(fixture, otherVariant, 2);

        assertThrows(ConflictException.class,
                () -> orderService.placeOrder(request(fixture), fixture.customerId, key));
        assertEquals(1, ordersFor(fixture.customerId));
    }

    /** TEST 4: same key, different delivery address. */
    @Test
    void sameKeyDifferentAddressIsRejected() {
        Fixture fixture = newCheckoutReadyCustomer(2);
        String key = UUID.randomUUID().toString();

        orderService.placeOrder(request(fixture), fixture.customerId, key);
        addToCart(fixture, fixture.variantId, 2);

        Long otherAddress = createAddress(customerRepository.findById(fixture.customerId).orElseThrow());
        PlaceOrderRequest changed = request(fixture);
        changed.setAddressId(otherAddress);

        assertThrows(ConflictException.class,
                () -> orderService.placeOrder(changed, fixture.customerId, key));
        assertEquals(1, ordersFor(fixture.customerId));
    }

    /** TEST 5: same key, different payment method. */
    @Test
    void sameKeyDifferentPaymentMethodIsRejected() {
        Fixture fixture = newCheckoutReadyCustomer(2);
        String key = UUID.randomUUID().toString();

        orderService.placeOrder(request(fixture), fixture.customerId, key);
        addToCart(fixture, fixture.variantId, 2);

        PlaceOrderRequest changed = request(fixture);
        changed.setPaymentMethod("UPI");

        assertThrows(ConflictException.class,
                () -> orderService.placeOrder(changed, fixture.customerId, key));
        assertEquals(1, ordersFor(fixture.customerId));
    }

    /**
     * TEST 6: two different customers using the SAME key string.
     *
     * Keys are scoped per customer (the unique constraint is on
     * customer_id + idempotency_key), so one customer's key must never
     * interfere with another's - two strangers picking the same UUID, or a
     * client with a broken generator, must not block each other's orders.
     */
    @Test
    void sameKeyAcrossDifferentCustomersDoesNotInterfere() {
        Fixture one = newCheckoutReadyCustomer(2);
        Fixture two = newCheckoutReadyCustomer(2);
        String sharedKey = UUID.randomUUID().toString();

        PlaceOrderResponse first = orderService.placeOrder(request(one), one.customerId, sharedKey);
        PlaceOrderResponse second = orderService.placeOrder(request(two), two.customerId, sharedKey);

        assertNotEquals(first.getOrderId(), second.getOrderId(),
                "Two customers sharing a key string must still get separate orders");
        assertEquals(1, ordersFor(one.customerId));
        assertEquals(1, ordersFor(two.customerId));
    }

    /**
     * Phase 5: the retention boundary.
     *
     * A record is what stops a retried checkout from creating a second
     * order, so deleting one too early re-opens the duplicate-order hole the
     * key exists to close. This pins that only records OLDER than the
     * configured window are removed and anything inside it survives.
     */
    @Test
    void retentionDeletesOnlyRecordsPastTheBoundary() {
        int retentionDays = retentionService.getRetentionDays();

        IdempotencyRecord justInsideWindow = newRecord(
                LocalDateTime.now().minusDays(retentionDays).plusHours(1));
        IdempotencyRecord wellPastWindow = newRecord(
                LocalDateTime.now().minusDays(retentionDays).minusDays(2));

        // Calls the batch delete directly rather than cleanupExpiredRecords().
        // That method carries @SchedulerLock, and ShedLock's aspect intercepts
        // a direct call the same as a scheduled one - with the scheduler
        // already having taken the lock during this test run (lockAtLeastFor
        // is 1m), an invocation here is silently SKIPPED and the assertions
        // below would pass or fail for reasons unrelated to retention. The
        // boundary itself is what this test is about; ShedLock's own
        // behaviour is not under test.
        retentionService.deleteOneBatch(LocalDateTime.now().minusDays(retentionDays));

        assertTrue(idempotencyRecordRepository.findById(justInsideWindow.getId()).isPresent(),
                "A record still inside the retention window must survive - deleting it "
                        + "would let a legitimate retry create a duplicate order");
        assertTrue(idempotencyRecordRepository.findById(wellPastWindow.getId()).isEmpty(),
                "A record past the retention window must be deleted, or the table grows forever");
    }


    /**
     * Payment is created WITH the order, in one request.
     *
     * Checkout used to be two sequential HTTP calls - place, wait, pay,
     * wait - which cost a full extra round trip and left a real gap: an
     * order could exist with NO payment at all if the second call never
     * arrived (app killed, network dropped, process died between them).
     * Asserting on stored state rather than the response, because the point
     * is that the row exists.
     */
    @Test
    void placingACodOrderCreatesItsPaymentInTheSameTransaction() {
        Fixture fixture = newCheckoutReadyCustomer(2);

        PlaceOrderResponse response = orderService.placeOrder(
                request(fixture), fixture.customerId, UUID.randomUUID().toString());

        com.gpstore.entity.Payment payment =
                paymentRepository.findByOrderId(response.getOrderId()).orElseThrow(
                        () -> new AssertionError("Placing an order must create its payment - "
                                + "leaving it to a second request is what allowed orders with no payment"));

        assertEquals(com.gpstore.enums.PaymentMethod.COD, payment.getPaymentMethod());
        assertEquals(com.gpstore.enums.PaymentStatus.COD_PENDING, payment.getPaymentStatus());
        assertEquals("COD_PENDING", response.getPaymentStatus(),
                "The response must tell the client a payment already exists, or it will "
                        + "make the now-redundant second request anyway");
    }

    /**
     * The old two-request flow must keep working: older app builds still call
     * POST /api/payments unconditionally, and now the payment already exists.
     * Returning the existing one is correct - the caller's desired end state
     * is already true. Rejecting it would break checkout for every client
     * that has not been updated.
     */
    @Test
    void initiatingPaymentAgainReturnsTheExistingOneInsteadOfConflicting() {
        Fixture fixture = newCheckoutReadyCustomer(2);
        PlaceOrderResponse order = orderService.placeOrder(
                request(fixture), fixture.customerId, UUID.randomUUID().toString());

        com.gpstore.dto.request.InitiatePaymentRequest second =
                new com.gpstore.dto.request.InitiatePaymentRequest();
        second.setOrderId(order.getOrderId());
        second.setPaymentMethod("COD");

        var result = paymentService.initiatePayment(second, fixture.customerId);

        assertEquals("COD_PENDING", result.getPayment().getPaymentStatus());
        assertEquals(1, paymentRepository.findAll().stream()
                        .filter(p -> p.getOrder() != null && p.getOrder().getId().equals(order.getOrderId()))
                        .count(),
                "A repeat payment request must not create a SECOND payment for the order");
    }

    /**
     * A mismatched method is a genuine disagreement about what the customer
     * is doing, not a retry, so it must still conflict. Silently returning
     * the COD payment to a caller asking for UPI would be worse than failing.
     */
    @Test
    void initiatingPaymentWithADifferentMethodStillConflicts() {
        Fixture fixture = newCheckoutReadyCustomer(2);
        PlaceOrderResponse order = orderService.placeOrder(
                request(fixture), fixture.customerId, UUID.randomUUID().toString());

        com.gpstore.dto.request.InitiatePaymentRequest mismatched =
                new com.gpstore.dto.request.InitiatePaymentRequest();
        mismatched.setOrderId(order.getOrderId());
        mismatched.setPaymentMethod("UPI");

        assertThrows(ConflictException.class,
                () -> paymentService.initiatePayment(mismatched, fixture.customerId));
    }

    // ---------- fixtures ----------

    private record Fixture(Long customerId, Long addressId, Long variantId, Long cartId) {
    }

    /**
     * A replay returns the SAME payment information as the original.
     *
     * Not a cosmetic completeness check. checkout_screen.dart reads a null
     * paymentStatus as "this backend does not create the payment with the
     * order" and falls back to a second HTTP call, POST /payments. Because
     * buildReplayResponse left both payment fields unset, the retry path -
     * the one that exists precisely because the first attempt was slow or
     * dropped - was the path that became two round trips and fired an
     * initiatePayment at an order that already had a payment row.
     */
    @Test
    void replayReturnsTheSamePaymentInformationAsTheOriginal() {
        Fixture fixture = newCheckoutReadyCustomer(2);
        String key = UUID.randomUUID().toString();

        PlaceOrderResponse first = orderService.placeOrder(request(fixture), fixture.customerId, key);
        PlaceOrderResponse replay = orderService.placeOrder(request(fixture), fixture.customerId, key);

        assertEquals(first.getOrderId(), replay.getOrderId(), "A replay must be the same order");
        assertNotNull(first.getPaymentStatus(), "Precondition: the first response carries a payment status");
        assertNotNull(replay.getPaymentStatus(),
                "A null paymentStatus on replay is exactly what triggers the client's second HTTP request");
        assertEquals(first.getPaymentStatus(), replay.getPaymentStatus());
        assertEquals(first.getUpiPaymentLink(), replay.getUpiPaymentLink());
    }

    /**
     * Same guarantee for UPI, where the payment link is the field that
     * actually matters - a replay that lost it would send the customer to a
     * confirmation screen with no way to pay.
     */
    @Test
    void upiReplayReturnsTheSamePaymentLink() {
        Fixture fixture = newCheckoutReadyCustomer(2);
        String key = UUID.randomUUID().toString();

        PlaceOrderRequest upiRequest = request(fixture);
        upiRequest.setPaymentMethod("UPI");

        PlaceOrderResponse first = orderService.placeOrder(upiRequest, fixture.customerId, key);
        PlaceOrderResponse replay = orderService.placeOrder(upiRequest, fixture.customerId, key);

        assertNotNull(first.getUpiPaymentLink(), "Precondition: a UPI checkout returns a payment link");
        assertEquals(first.getUpiPaymentLink(), replay.getUpiPaymentLink(),
                "The link is derived from order number and amount, so a replay must reproduce it exactly");
        assertEquals(first.getPaymentStatus(), replay.getPaymentStatus());
    }

    private PlaceOrderRequest request(Fixture fixture) {
        PlaceOrderRequest request = new PlaceOrderRequest();
        request.setAddressId(fixture.addressId);
        request.setPaymentMethod("COD");
        return request;
    }

    private long ordersFor(Long customerId) {
        return orderRepository.findAll().stream()
                .filter(o -> o.getCustomer() != null && o.getCustomer().getId().equals(customerId))
                .count();
    }

    private IdempotencyRecord newRecord(LocalDateTime createdAt) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setCustomerId(-1L * Math.abs(System.nanoTime() % 100000));
        record.setIdempotencyKey("retention-" + UUID.randomUUID());
        record.setCreatedAt(createdAt);
        return idempotencyRecordRepository.saveAndFlush(record);
    }

    private Fixture newCheckoutReadyCustomer(int quantity) {
        Customer customer = new Customer();
        customer.setFullName("Idempotency Test Customer");
        customer.setEmail("idem-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber("9" + String.valueOf(System.nanoTime()).substring(0, 9));
        customer.setPassword("irrelevant-for-this-test");
        customer.setEnabled(true);
        customer.setActive(true);
        customer = customerRepository.save(customer);

        Long addressId = createAddress(customer);
        Long variantId = createVariantWithStock(100);

        Cart cart = new Cart();
        cart.setCustomer(customer);
        cart = cartRepository.save(cart);

        Fixture fixture = new Fixture(customer.getId(), addressId, variantId, cart.getId());
        addToCart(fixture, variantId, quantity);
        return fixture;
    }

    private void addToCart(Fixture fixture, Long variantId, int quantity) {
        CartItem item = new CartItem();
        item.setCart(cartRepository.findById(fixture.cartId).orElseThrow());
        item.setProductVariant(productVariantRepository.findById(variantId).orElseThrow());
        item.setQuantity(quantity);
        cartItemRepository.save(item);
    }

    private Long createAddress(Customer customer) {
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
        // The store's own coordinates - zero distance, always deliverable
        // regardless of the configured radius.
        address.setLatitude(storeLatitude);
        address.setLongitude(storeLongitude);
        address.setDefaultAddress(true);
        return addressRepository.save(address).getId();
    }

    private Long createVariantWithStock(int stock) {
        Category category = new Category();
        category.setName("Idempotency Category " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        category = categoryRepository.save(category);

        Product product = new Product();
        product.setName("Idempotency Item " + System.nanoTime());
        product.setBrand("TestBrand");
        product.setActive(true);
        product.setCategory(category);
        product = productRepository.save(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setQuantity(1.0);
        variant.setUnit("pc");
        variant.setMrp(new BigDecimal("100"));
        variant.setSellingPrice(new BigDecimal("90"));
        variant.setAvailable(true);
        variant.setActive(true);
        variant = productVariantRepository.save(variant);

        Inventory inventory = new Inventory();
        inventory.setProductVariant(variant);
        inventory.setStock(stock);
        inventoryRepository.save(inventory);

        return variant.getId();
    }
}
