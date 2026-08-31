package com.gpstore.service;

import com.gpstore.entity.Category;
import com.gpstore.entity.Cart;
import com.gpstore.entity.CartItem;
import com.gpstore.entity.Coupon;
import com.gpstore.entity.Customer;
import com.gpstore.entity.IdempotencyRecord;
import com.gpstore.entity.Inventory;
import com.gpstore.entity.Order;
import com.gpstore.entity.OrderItem;
import com.gpstore.entity.Payment;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.dto.request.InitiatePaymentRequest;
import com.gpstore.enums.DiscountType;
import com.gpstore.enums.OrderStatus;
import com.gpstore.enums.PaymentMethod;
import com.gpstore.enums.PaymentStatus;
import com.gpstore.exception.ConflictException;
import com.gpstore.repository.CartItemRepository;
import com.gpstore.repository.CartRepository;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.CouponRepository;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.IdempotencyRecordRepository;
import com.gpstore.repository.InventoryRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.PaymentRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real concurrency, against a real Postgres (see .github/workflows/ci.yml's
 * postgres service container) - these tests only mean anything against a
 * real DB, since the row locks and unique constraints under test don't
 * exist in a mocked repository. Every test fires many threads at the same
 * protected resource at once and asserts on the resulting DB state, not
 * just that no exception was thrown - a race condition can "succeed" from
 * every individual caller's point of view and still leave the data wrong,
 * which is exactly the failure mode this class exists to catch.
 */
@SpringBootTest(properties = {
        // NO LIVE OUTBOX WORKER. A running drain turns committed work into
        // auto-assigned deliveries against whichever rider is available, and
        // Spring caches this context and never closes it - so the worker
        // outlives the class and keeps assigning while later classes are
        // asserting. That is how TerritoryDispatchTest failed with
        // "expected: <22> but was: <23>": a stray assignment gave one of two
        // deliberately-tied riders a live order and the tie broke the other
        // way.
        //
        // Nothing in this class tests the outbox or waits on an async side
        // effect, so the drain has no purpose here beyond causing that.
        // OutboxDurabilityTest, which does test it, keeps a live worker.
        "outbox.drain-interval-ms=3600000"
})
class ConcurrencyIntegrationTest {

    @Autowired private InventoryService inventoryService;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository productVariantRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private CouponRepository couponRepository;
    @Autowired private CouponService couponService;
    @Autowired private IdempotencyRecordRepository idempotencyRecordRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentService paymentService;
    @Autowired private CartService cartService;
    @Autowired private CartRepository cartRepository;
    @Autowired private CartItemRepository cartItemRepository;
    @Autowired private OrderService orderService;
    @Autowired private com.gpstore.repository.OrderItemRepository orderItemRepository;

    /**
     * The exact scenario from the scalability audit: stock = 10, 20
     * "customers" all try to buy 1 unit at the same instant. Without a real
     * row lock, a naive "if (stock >= qty) stock -= qty" can let every one
     * of the 20 pass the check before any of them saves, overselling by up
     * to 2x. InventoryService.decrementForPurchase uses
     * PESSIMISTIC_WRITE (see InventoryRepository.findByProductVariantIdForUpdate),
     * so exactly 10 should succeed and the other 10 should see it as out of
     * stock - never both succeeding on the same unit.
     */
    @Test
    void concurrentPurchasesNeverOversellInventory() throws InterruptedException {
        Long productVariantId = createProductVariant("Overselling Test Item");
        Inventory inventory = setStock(productVariantId, 10);

        int attackers = 20;
        ExecutorService pool = Executors.newFixedThreadPool(attackers);
        CountDownLatch ready = new CountDownLatch(attackers);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attackers);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();

        for (int i = 0; i < attackers; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    inventoryService.decrementForPurchase(productVariantId, 1);
                    successCount.incrementAndGet();
                } catch (Exception expectedWhenOutOfStock) {
                    rejectedCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "All purchase attempts should finish within 30s");
        pool.shutdown();

        Inventory after = inventoryRepository.findById(inventory.getId()).orElseThrow();

        assertEquals(10, successCount.get(), "Exactly 10 of the 20 concurrent attempts should succeed - one per unit of real stock");
        assertEquals(10, rejectedCount.get(), "The other 10 must be rejected as out of stock, not silently oversold");
        assertEquals(0, after.getStock(), "Final stock must be exactly 0 - never negative (oversold) and never left over");
    }

    /**
     * The other half of Phase 5/7: two requests carrying the same
     * Idempotency-Key (a double-tap on Place Order, or a client retry after
     * a network timeout) must not both succeed in creating a record for the
     * same (customerId, key) pair - see IdempotencyRecord's own doc comment
     * on why the unique DB constraint, not an app-level check-then-insert,
     * is what actually makes this safe under a genuine race.
     */
    @Test
    void concurrentDuplicateIdempotencyKeysOnlyOneWins() throws InterruptedException {
        Long customerId = 999_999_001L;
        String idempotencyKey = "test-key-" + System.nanoTime();

        int attackers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(attackers);
        CountDownLatch ready = new CountDownLatch(attackers);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attackers);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();

        for (int i = 0; i < attackers; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();

                    IdempotencyRecord record = new IdempotencyRecord();
                    record.setCustomerId(customerId);
                    record.setIdempotencyKey(idempotencyKey);
                    record.setCreatedAt(LocalDateTime.now());
                    idempotencyRecordRepository.saveAndFlush(record);
                    successCount.incrementAndGet();
                } catch (DataIntegrityViolationException expectedForEveryLoser) {
                    rejectedCount.incrementAndGet();
                } catch (Exception unexpected) {
                    rejectedCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "All idempotency-key attempts should finish within 30s");
        pool.shutdown();

        assertEquals(1, successCount.get(), "Exactly one of the 10 concurrent same-key requests may create a record");
        assertEquals(9, rejectedCount.get(), "The other 9 must fail the unique constraint, not each create their own duplicate");

        long rowsWithThisKey = idempotencyRecordRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey).isPresent() ? 1 : 0;
        assertEquals(1, rowsWithThisKey, "Only one row should exist in the DB for this (customerId, key) pair");
    }

    /**
     * A limited-use coupon (usageLimit = 1) redeemed by many concurrent
     * checkouts at once - CouponService.redeem() locks the coupon row
     * (findByCouponCodeForUpdate) before re-validating and incrementing
     * usedCount, which is what should stop more than one of these from
     * squeezing past the limit.
     */
    @Test
    void concurrentCouponRedemptionsRespectUsageLimit() throws InterruptedException {
        Coupon coupon = new Coupon();
        coupon.setCouponCode("RACE" + System.nanoTime());
        coupon.setActive(true);
        coupon.setDiscountType(DiscountType.FLAT);
        coupon.setDiscountValue(new BigDecimal("10"));
        coupon.setUsageLimit(1);
        coupon.setUsedCount(0);
        coupon.setExpiryDate(LocalDate.now().plusDays(1));
        coupon = couponRepository.save(coupon);
        String code = coupon.getCouponCode();

        int attackers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(attackers);
        CountDownLatch ready = new CountDownLatch(attackers);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attackers);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();

        for (int i = 0; i < attackers; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    couponService.redeem(code, new BigDecimal("100"));
                    successCount.incrementAndGet();
                } catch (Exception expectedOnceLimitReached) {
                    rejectedCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "All redemption attempts should finish within 30s");
        pool.shutdown();

        assertEquals(1, successCount.get(), "Only one of the 10 concurrent redemptions may succeed against usageLimit=1");
        assertEquals(9, rejectedCount.get());

        Coupon after = couponRepository.findByCouponCodeIgnoreCase(code).orElseThrow();
        assertEquals(1, after.getUsedCount(), "usedCount must land at exactly 1, never overshoot the limit");
    }

    /**
     * Phase 7's other missing case: a double-tap on "Pay now" (or a client
     * retry after a timeout) firing two concurrent initiatePayment() calls
     * for the same order. PaymentService.initiatePayment checks
     * findByOrderId first, but that check-then-insert has a race window
     * under real concurrency - uq_payments_order_id (V4 migration) is the
     * actual backstop (see the comment in PaymentService.initiatePayment),
     * so exactly one concurrent request should succeed and the rest should
     * see a clean ConflictException, never two Payment rows for one order.
     */
    @Test
    void concurrentDuplicatePaymentInitiationOnlyOneSucceeds() throws InterruptedException {
        Long orderId = createOrder();
        Long customerId = orderRepository.findById(orderId).orElseThrow().getCustomer().getId();

        int attackers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(attackers);
        CountDownLatch ready = new CountDownLatch(attackers);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attackers);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();
        java.util.Set<Long> returnedPaymentIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

        for (int i = 0; i < attackers; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();

                    InitiatePaymentRequest request = new InitiatePaymentRequest();
                    request.setOrderId(orderId);
                    request.setPaymentMethod("COD");
                    var result = paymentService.initiatePayment(request, customerId);
                    successCount.incrementAndGet();
                    returnedPaymentIds.add(result.getPayment().getId());
                } catch (ConflictException expectedForEveryLoser) {
                    rejectedCount.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "All payment initiation attempts should finish within 30s");
        pool.shutdown();

        // initiatePayment is now IDEMPOTENT rather than throwing on a repeat:
        // placeOrder creates the payment inside the order transaction, so by
        // the time this endpoint is called the payment normally already
        // exists, and older app builds still call it unconditionally.
        // Rejecting that would break checkout for every un-updated client,
        // for a request whose desired end state is already true.
        //
        // So the old "exactly 1 success, 9 conflicts" assertion no longer
        // describes correct behaviour. What replaces it is STRICTER about the
        // property that actually matters:
        assertTrue(successCount.get() >= 1,
                "At least one concurrent initiatePayment call must succeed");
        assertEquals(attackers, successCount.get() + rejectedCount.get(),
                "Every attempt must end in a definite outcome, not vanish");

        // 1. Every caller that succeeded got the SAME payment - proving they
        //    observed one shared row rather than each creating their own.
        //    This is the real idempotency guarantee, and the old assertion
        //    could not express it at all.
        assertEquals(1, returnedPaymentIds.size(),
                "All successful callers must receive the SAME payment - more than one distinct "
                        + "id means concurrent requests created separate payments for one order");

        // 2. Exactly one payment row exists. Counted properly rather than via
        //    findByOrderId().isPresent(), which collapses "one" and "many"
        //    into the same answer and so could never have caught a duplicate.
        long paymentRowsForOrder = paymentRepository.findAll().stream()
                .filter(p -> p.getOrder() != null && p.getOrder().getId().equals(orderId))
                .count();
        assertEquals(1, paymentRowsForOrder, "Only one Payment row may exist for this order, never two");
    }

    /**
     * Phase 11: CartService.addToCart does a read-then-write on the
     * existing CartItem's quantity (see CartRepository.findByCustomerIdForUpdate's
     * doc comment) with no unique constraint backing it. Ten concurrent
     * "add 1 of the same item" calls for the same customer/cart must land
     * on exactly one CartItem row with quantity 10 - not two rows, and not
     * a lower quantity from a lost update where two requests both read the
     * same pre-increment value.
     */
    @Test
    void concurrentAddToCartNeverLosesAnIncrement() throws InterruptedException {
        Long customerId = createCustomer();
        Long variantId = createProductVariant("Cart Race Test Item");

        int attackers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(attackers);
        CountDownLatch ready = new CountDownLatch(attackers);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attackers);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        for (int i = 0; i < attackers; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    cartService.addToCart(customerId, variantId, 1);
                    successCount.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (Exception unexpected) {
                    failureCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "All add-to-cart attempts should finish within 30s");
        pool.shutdown();

        assertEquals(attackers, successCount.get(), "Every concurrent add-1 call should succeed when stock covers the total");
        assertEquals(0, failureCount.get(), "None of these should fail");

        Cart cart = cartRepository.findByCustomerId(customerId).orElseThrow();
        List<CartItem> items = cartItemRepository.findByCartId(cart.getId());

        assertEquals(1, items.size(), "All 10 adds of the same variant must land on one CartItem row, never split into duplicates");
        assertEquals(10, items.get(0).getQuantity(), "Quantity must reflect all 10 increments - a lost update would leave this lower");
    }

    /**
     * The race the audit specifically called out: two concurrent
     * cancellation requests for the same order (a double-tap, or a
     * customer and an admin cancelling at the same instant). Before
     * OrderRepository.findByIdForUpdate, cancelOrder did a plain
     * read-check-write - both requests could read the same
     * not-yet-cancelled status, both pass the check, and both restore
     * inventory for the same order, inflating stock. Exactly one
     * cancellation must succeed, and inventory must be restored by exactly
     * the ordered quantity - never twice.
     */
    @Test
    void concurrentCancellationRestoresInventoryExactlyOnce() throws InterruptedException {
        int orderedQuantity = 4;
        int stockAfterOrderPlaced = 6;

        Long variantId = createProductVariant("Cancellation Race Test Item");
        setStock(variantId, stockAfterOrderPlaced);

        Long orderId = createOrder();
        Order order = orderRepository.findById(orderId).orElseThrow();
        Long customerId = order.getCustomer().getId();

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProductVariant(productVariantRepository.findById(variantId).orElseThrow());
        item.setQuantity(orderedQuantity);
        item.setPrice(new BigDecimal("90.00"));
        item.setTotalPrice(new BigDecimal("360.00"));
        item.setActive(true);
        orderItemRepository.save(item);

        int attackers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(attackers);
        CountDownLatch ready = new CountDownLatch(attackers);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attackers);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();

        for (int i = 0; i < attackers; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    orderService.cancelOrder(orderId, customerId, false);
                    successCount.incrementAndGet();
                } catch (ConflictException expectedForEveryLoser) {
                    rejectedCount.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "All cancellation attempts should finish within 30s");
        pool.shutdown();

        assertEquals(1, successCount.get(), "Exactly one of the 10 concurrent cancellations may succeed");
        assertEquals(9, rejectedCount.get(), "The other 9 must be rejected as already-cancelled, not each restore inventory again");

        Order after = orderRepository.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, after.getOrderStatus());

        Inventory afterInventory = inventoryRepository.findByProductVariantId(variantId).orElseThrow();
        assertEquals(stockAfterOrderPlaced + orderedQuantity, afterInventory.getStock(),
                "Stock must be restored by exactly the ordered quantity once - double-restoration would show up here as too much stock");
    }

    /**
     * The other state-transition race the audit called out: two
     * concurrent status updates on the same order (two admins/delivery
     * staff both advancing it at once). OrderRepository.findByIdForUpdate
     * serializes these the same way it does for cancellation - exactly one
     * transition may succeed per call, the loser sees the already-updated
     * status and gets a clean ConflictException instead of silently
     * re-applying (and double-triggering side effects like the
     * notification/audit-log entries).
     */
    @Test
    void concurrentOrderStatusUpdatesApplyExactlyOnce() throws InterruptedException {
        Long orderId = createPaidOnlineOrder();
        // Paid ONLINE order at PENDING_CONFIRMATION - the only valid
        // transition from there is to CONFIRMED. Without a SUCCESS payment
        // the new confirm gate refuses the advance.

        int attackers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(attackers);
        CountDownLatch ready = new CountDownLatch(attackers);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attackers);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();

        for (int i = 0; i < attackers; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    orderService.updateOrderStatus(orderId, OrderStatus.CONFIRMED);
                    successCount.incrementAndGet();
                } catch (ConflictException expectedForEveryLoser) {
                    rejectedCount.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "All status-update attempts should finish within 30s");
        pool.shutdown();

        assertEquals(1, successCount.get(), "Exactly one of the 10 concurrent status transitions may succeed");
        assertEquals(9, rejectedCount.get(), "The other 9 must see the already-transitioned status as a conflict, not silently re-apply");

        Order after = orderRepository.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.CONFIRMED, after.getOrderStatus(), "Final status must reflect exactly one transition");
    }

    private Long createCustomer() {
        Customer customer = new Customer();
        customer.setFullName("Concurrency Test Customer");
        customer.setEmail("concurrency-test-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber("9" + String.valueOf(System.nanoTime()).substring(0, 9));
        customer.setPassword("irrelevant-for-this-test");
        customer.setEnabled(true);
        customer.setActive(true);
        return customerRepository.save(customer).getId();
    }

    private Long createOrder() {
        Customer customer = customerRepository.findById(createCustomer()).orElseThrow();

        Order order = new Order();
        order.setOrderNumber("TESTORD-" + System.nanoTime());
        order.setCustomer(customer);
        order.setTotalAmount(new BigDecimal("199.00"));
        order.setOrderStatus(OrderStatus.PENDING_CONFIRMATION);
        order.setOrderDate(LocalDateTime.now());
        order.setActive(true);
        return orderRepository.save(order).getId();
    }

    private Long createPaidOnlineOrder() {
        Long orderId = createOrder();
        Order order = orderRepository.findById(orderId).orElseThrow();
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setAmount(order.getTotalAmount());
        payment.setPaymentMethod(PaymentMethod.ONLINE);
        payment.setPaymentStatus(PaymentStatus.SUCCESS);
        payment.setActive(true);
        paymentRepository.save(payment);
        return orderId;
    }

    private Inventory setStock(Long variantId, int stock) {
        Inventory inventory = inventoryRepository.findByProductVariantId(variantId).orElseThrow();
        inventory.setStock(stock);
        return inventoryRepository.save(inventory);
    }

    private Long createProductVariant(String namePrefix) {
        Category category = new Category();
        category.setName("Concurrency Test Category " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        category = categoryRepository.save(category);

        Product product = new Product();
        product.setName(namePrefix + " " + System.nanoTime());
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
        inventory.setStock(100);
        inventoryRepository.save(inventory);

        return variant.getId();
    }
}
