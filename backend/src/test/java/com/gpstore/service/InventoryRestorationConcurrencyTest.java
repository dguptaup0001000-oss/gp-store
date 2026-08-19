package com.gpstore.service;

import com.gpstore.entity.Category;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Inventory;
import com.gpstore.entity.Order;
import com.gpstore.entity.OrderItem;
import com.gpstore.entity.Payment;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.enums.OrderStatus;
import com.gpstore.enums.PaymentMethod;
import com.gpstore.enums.PaymentStatus;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.InventoryRepository;
import com.gpstore.repository.OrderItemRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.PaymentRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1/2: proves an order's reserved stock goes back EXACTLY once, no
 * matter which combination of paths races for it.
 *
 * Three independent paths can each decide an order's stock should be
 * returned - explicit cancellation, the stale-UPI expiry sweep, and payment
 * failure handling - and before this work they coordinated only through
 * order/payment status, which does not actually answer "has the stock
 * already gone back".
 *
 * The concrete bug these tests were written against: cancelOrder() set a
 * COD_PENDING payment to FAILED and a SUCCESS payment to REFUND_PENDING,
 * but left a PENDING UPI payment untouched. A cancelled order therefore
 * kept a PENDING UPI payment carrying an old payment_date, which still
 * matched the expiry sweep's query - so the sweep restored that order's
 * stock a SECOND time, silently inflating inventory with no error anywhere.
 *
 * Every assertion below is on final database state rather than on which
 * calls threw, because that is precisely the failure mode: each individual
 * caller can succeed from its own point of view while the stored inventory
 * count ends up wrong.
 */
@SpringBootTest
class InventoryRestorationConcurrencyTest {

    @Autowired private OrderService orderService;
    @Autowired private PaymentService paymentService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository productVariantRepository;

    private static final int ORDERED_QUANTITY = 4;
    private static final int STOCK_AFTER_ORDER_PLACED = 6;

    /**
     * TEST A - 100 concurrent cancellations of the same order.
     *
     * Exactly one may take effect. The other 99 must be rejected rather
     * than each adding the ordered quantity back again; a single extra
     * restore shows up here as 4 too much stock.
     */
    @Test
    void hundredConcurrentCancellationsRestoreInventoryExactlyOnce() throws InterruptedException {
        Fixture fixture = newOrderWithStock();

        int attackers = 100;
        Outcome outcome = race(attackers, () ->
                orderService.cancelOrder(fixture.orderId, fixture.customerId, false));

        assertEquals(1, outcome.succeeded.get(),
                "Exactly one of 100 concurrent cancellations may succeed");
        assertEquals(attackers - 1, outcome.failed.get(),
                "Every other cancellation must be rejected, not silently re-run");

        assertEquals(OrderStatus.CANCELLED, reloadOrder(fixture.orderId).getOrderStatus());
        assertInventoryRestoredExactlyOnce(fixture);
        assertRestoredFlagSet(fixture.orderId);
    }

    /**
     * TEST B - cancellation racing the stale-UPI expiry sweep.
     *
     * This is the actual production bug. Both paths believe this order's
     * stock should go back; only one may act on that. Before the fix the
     * order ended CANCELLED with its stock added back twice.
     */
    @Test
    void cancellationRacingUpiExpiryRestoresInventoryExactlyOnce() throws InterruptedException {
        Fixture fixture = newOrderWithStock();
        Payment payment = newPayment(fixture.orderId, PaymentMethod.UPI, PaymentStatus.PENDING,
                // Backdated well past the expiry window so the sweep
                // considers it genuinely abandoned.
                LocalDateTime.now().minusDays(1));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        pool.submit(() -> runRacer(ready, go, done, () ->
                orderService.cancelOrder(fixture.orderId, fixture.customerId, false)));
        pool.submit(() -> runRacer(ready, go, done, () ->
                paymentService.expireOneStalePayment(payment.getId())));

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "Both racers should finish within 30s");
        pool.shutdown();

        assertInventoryRestoredExactlyOnce(fixture);
        assertRestoredFlagSet(fixture.orderId);

        // Whichever path won, the payment must not still be sitting PENDING -
        // that is what made it re-eligible for the sweep in the first place.
        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertNotEquals(PaymentStatus.PENDING, after.getPaymentStatus(),
                "A cancelled or expired order's payment must reach a terminal state, "
                        + "otherwise the expiry sweep keeps rediscovering it");
    }

    /**
     * TEST C - cancellation racing UPI payment confirmation.
     *
     * These two disagree about where the order should end up, so the only
     * safe outcome is that one wins cleanly. What must never happen is
     * both applying: a confirmed-and-cancelled order, or stock restored
     * twice because cancellation ran while confirmation was mid-flight.
     */
    @Test
    void cancellationRacingPaymentConfirmationLeavesOneValidState() throws InterruptedException {
        Fixture fixture = newOrderWithStock();
        Payment payment = newPayment(fixture.orderId, PaymentMethod.UPI, PaymentStatus.PENDING,
                LocalDateTime.now());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        AtomicInteger cancelled = new AtomicInteger();
        AtomicInteger confirmed = new AtomicInteger();

        pool.submit(() -> runRacer(ready, go, done, () -> {
            orderService.cancelOrder(fixture.orderId, fixture.customerId, false);
            cancelled.incrementAndGet();
        }));
        pool.submit(() -> runRacer(ready, go, done, () -> {
            paymentService.confirmUpiPayment(fixture.orderId, "TXN-" + System.nanoTime());
            confirmed.incrementAndGet();
        }));

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "Both racers should finish within 30s");
        pool.shutdown();

        Order after = reloadOrder(fixture.orderId);
        Payment paymentAfter = paymentRepository.findById(payment.getId()).orElseThrow();

        if (after.getOrderStatus() == OrderStatus.CANCELLED) {
            // Cancellation won (possibly after confirmation committed, in
            // which case the payment is now awaiting refund rather than
            // simply failed). Either way the stock went back exactly once.
            assertInventoryRestoredExactlyOnce(fixture);
            assertTrue(
                    paymentAfter.getPaymentStatus() == PaymentStatus.FAILED
                            || paymentAfter.getPaymentStatus() == PaymentStatus.REFUND_PENDING,
                    "A cancelled order's payment must be FAILED or REFUND_PENDING, was "
                            + paymentAfter.getPaymentStatus());
        } else {
            // Confirmation won and cancellation was rejected - the order is
            // live, so its stock must NOT have been given back at all.
            assertEquals(PaymentStatus.SUCCESS, paymentAfter.getPaymentStatus());
            assertEquals(STOCK_AFTER_ORDER_PLACED, currentStock(fixture),
                    "A confirmed, non-cancelled order must not have released its stock");
            assertEquals(Boolean.FALSE, reloadOrder(fixture.orderId).getInventoryRestored());
        }

        // Deliberately NOT asserting that only one of the two succeeded.
        // Both succeeding is legitimate rather than a race: if confirmation
        // commits first, the order is then a paid, still-cancellable order,
        // and cancelling it afterwards is exactly the refund flow -
        // CANCELLED with the payment moved to REFUND_PENDING. What must
        // hold either way is that the FINAL state is coherent and the stock
        // moved at most once, which the branches above assert. An earlier
        // version of this test demanded cancelled + confirmed == 1 and
        // failed on that valid interleaving - the assertion was wrong, not
        // the code.
        assertTrue(cancelled.get() + confirmed.get() >= 1,
                "At least one of cancellation/confirmation must take effect");
    }

    /**
     * TEST D - 20 concurrent confirmations of the same UPI payment.
     *
     * Only one may transition PENDING -> SUCCESS. Retries (a
     * double-submitted admin form, a client retry after a timeout) must be
     * rejected rather than each re-running the transition and its audit
     * entry. Before the fix all of them read PENDING and all of them wrote
     * SUCCESS.
     */
    @Test
    void concurrentPaymentConfirmationsApplyExactlyOnce() throws InterruptedException {
        Fixture fixture = newOrderWithStock();
        Payment payment = newPayment(fixture.orderId, PaymentMethod.UPI, PaymentStatus.PENDING,
                LocalDateTime.now());

        int attackers = 20;
        Outcome outcome = race(attackers, () ->
                paymentService.confirmUpiPayment(fixture.orderId, "TXN-" + System.nanoTime()));

        assertEquals(1, outcome.succeeded.get(),
                "Exactly one confirmation may take effect");
        assertEquals(attackers - 1, outcome.failed.get(),
                "Every retry must be rejected as already-confirmed");

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.SUCCESS, after.getPaymentStatus());

        assertEquals(1, paymentRepository.findAll().stream()
                        .filter(p -> p.getOrder() != null && p.getOrder().getId().equals(fixture.orderId))
                        .count(),
                "Confirmation must never create an additional payment row");

        // A confirmed order is live - nothing here should have touched stock.
        assertEquals(STOCK_AFTER_ORDER_PLACED, currentStock(fixture));
    }

    // ---------- harness ----------

    private record Fixture(Long orderId, Long customerId, Long variantId) {
    }

    private static final class Outcome {
        final AtomicInteger succeeded = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
    }

    /**
     * Fires n threads at the same action simultaneously and counts how many
     * took effect. Every exception counts as "failed" deliberately: the
     * losers of these races legitimately surface as ConflictException, and
     * under real row-lock contention Postgres can also surface serialization
     * or lock-timeout failures. What matters is the count that succeeded and
     * the resulting database state, not which exception type the losers saw.
     */
    private Outcome race(int threads, Runnable action) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, 32));
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Outcome outcome = new Outcome();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    action.run();
                    outcome.succeeded.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException expectedForLosers) {
                    outcome.failed.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "All " + threads + " attempts should finish within 60s");
        pool.shutdown();
        return outcome;
    }

    private void runRacer(CountDownLatch ready, CountDownLatch go, CountDownLatch done, Runnable action) {
        try {
            ready.countDown();
            go.await();
            action.run();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException expectedForLoser) {
            // The losing path is supposed to be rejected - the assertions
            // that matter are on final state, made by the caller.
        } finally {
            done.countDown();
        }
    }

    private void assertInventoryRestoredExactlyOnce(Fixture fixture) {
        assertEquals(STOCK_AFTER_ORDER_PLACED + ORDERED_QUANTITY, currentStock(fixture),
                "Stock must be restored by exactly the ordered quantity ONCE - "
                        + "a double restore shows up here as too much stock");
    }

    private void assertRestoredFlagSet(Long orderId) {
        assertEquals(Boolean.TRUE, reloadOrder(orderId).getInventoryRestored(),
                "The exactly-once guard must be persisted, or a later sweep would restore again");
    }

    private int currentStock(Fixture fixture) {
        return inventoryRepository.findByProductVariantId(fixture.variantId).orElseThrow().getStock();
    }

    private Order reloadOrder(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow();
    }

    private Fixture newOrderWithStock() {
        Long variantId = createProductVariant();

        Inventory inventory = new Inventory();
        inventory.setProductVariant(productVariantRepository.findById(variantId).orElseThrow());
        inventory.setStock(STOCK_AFTER_ORDER_PLACED);
        inventoryRepository.save(inventory);

        Customer customer = new Customer();
        customer.setFullName("Restore Race Customer");
        customer.setEmail("restore-race-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber("9" + String.valueOf(System.nanoTime()).substring(0, 9));
        customer.setPassword("irrelevant-for-this-test");
        customer.setEnabled(true);
        customer.setActive(true);
        customer = customerRepository.save(customer);

        Order order = new Order();
        order.setOrderNumber("RESTORE-" + System.nanoTime());
        order.setCustomer(customer);
        order.setTotalAmount(new BigDecimal("360.00"));
        order.setOrderStatus(OrderStatus.CONFIRMED);
        order.setOrderDate(LocalDateTime.now());
        order.setActive(true);
        order = orderRepository.save(order);

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProductVariant(productVariantRepository.findById(variantId).orElseThrow());
        item.setQuantity(ORDERED_QUANTITY);
        item.setPrice(new BigDecimal("90.00"));
        item.setTotalPrice(new BigDecimal("360.00"));
        item.setActive(true);
        orderItemRepository.save(item);

        return new Fixture(order.getId(), customer.getId(), variantId);
    }

    private Payment newPayment(Long orderId, PaymentMethod method, PaymentStatus status, LocalDateTime paidAt) {
        Payment payment = new Payment();
        payment.setOrder(orderRepository.findById(orderId).orElseThrow());
        payment.setPaymentMethod(method);
        payment.setPaymentStatus(status);
        payment.setAmount(new BigDecimal("360.00"));
        payment.setPaymentDate(paidAt);
        payment.setActive(true);
        return paymentRepository.save(payment);
    }

    private Long createProductVariant() {
        Category category = new Category();
        category.setName("Restore Race Category " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        category = categoryRepository.save(category);

        Product product = new Product();
        product.setName("Restore Race Item " + System.nanoTime());
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
        return productVariantRepository.save(variant).getId();
    }
}
