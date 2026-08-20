package com.gpstore.service;

import com.gpstore.entity.Category;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Inventory;
import com.gpstore.entity.Order;
import com.gpstore.entity.OrderItem;
import com.gpstore.entity.OutboxEvent;
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
import com.gpstore.repository.OutboxEventRepository;
import com.gpstore.repository.PaymentRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The UPI-expiry state machine, end to end.
 *
 * The bug these tests were written against: the expiry sweep set the
 * payment to FAILED and handed the reserved stock back, then stopped. The
 * order was left at PENDING_CONFIRMATION - indistinguishable, to every
 * screen and query downstream, from an order that is genuinely waiting to
 * be packed. Its stock had already been returned to the shelf and possibly
 * re-sold, and its payment row was terminal so the customer could never
 * complete it. An admin picking that order out of the queue would be
 * packing goods that no longer exist.
 *
 * An abandoned UPI checkout therefore has exactly one correct end state,
 * and these tests assert all three parts of it together, on stored state:
 * payment FAILED, inventory restored exactly once, order CANCELLED - plus
 * the durable ORDER_CANCELLED event that cancels the invoice, because a
 * cancelled order with a live invoice is a sale on the books that never
 * happened.
 */
@SpringBootTest(properties = {
        // Timers pushed out, not disabled: the beans stay exactly as they
        // are in production, but nothing fires on its own schedule while
        // these tests inspect stored state. Without this the outbox worker
        // would drain the ORDER_CANCELLED events mid-assertion and the
        // expiry sweep could expire fixtures out from under a test.
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "outbox.purge-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "payment.expiry-interval-ms=3600000"
})
class UpiExpiryStateMachineTest {

    @Autowired private OrderService orderService;
    @Autowired private PaymentService paymentService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository productVariantRepository;

    private static final int ORDERED_QUANTITY = 4;
    private static final int STOCK_AFTER_ORDER_PLACED = 6;

    /**
     * The whole transition, in one assertion block. This is the test that
     * fails outright against the old code: it stopped after the first two
     * assertions and left the order PENDING_CONFIRMATION.
     */
    @Test
    void expiredUpiPaymentCancelsTheOrderAndReleasesItsStock() {
        Fixture fixture = newPendingUpiOrder();

        paymentService.expireOneStalePayment(fixture.paymentId);

        Payment payment = paymentRepository.findById(fixture.paymentId).orElseThrow();
        assertEquals(PaymentStatus.FAILED, payment.getPaymentStatus(),
                "An unconfirmed UPI payment past its window is terminal");

        Order order = orderRepository.findById(fixture.orderId).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, order.getOrderStatus(),
                "An order whose stock has gone back and whose payment can never "
                        + "succeed must not stay in the fulfilment queue");
        assertTrue(Boolean.TRUE.equals(order.getInventoryRestored()),
                "The exactly-once restore flag records that the stock went back");

        assertEquals(STOCK_AFTER_ORDER_PLACED + ORDERED_QUANTITY,
                inventoryRepository.findByProductVariantId(fixture.variantId).orElseThrow().getStock(),
                "Reserved stock returns to the shelf exactly once");

        assertEquals(1, cancellationEventsFor(fixture.orderId),
                "The invoice cancellation is durable, not best-effort");
    }

    /**
     * At-least-once by construction: the sweep's candidate query is an
     * unlocked read, so the same payment can legitimately be handed to this
     * method twice. The second pass must be a no-op on every axis - stock
     * especially, since a second restore silently inflates inventory.
     */
    @Test
    void expiringTheSamePaymentTwiceChangesNothingTheSecondTime() {
        Fixture fixture = newPendingUpiOrder();

        paymentService.expireOneStalePayment(fixture.paymentId);
        paymentService.expireOneStalePayment(fixture.paymentId);

        assertEquals(STOCK_AFTER_ORDER_PLACED + ORDERED_QUANTITY,
                inventoryRepository.findByProductVariantId(fixture.variantId).orElseThrow().getStock(),
                "A repeated sweep must not add the stock back a second time");
        assertEquals(1, cancellationEventsFor(fixture.orderId),
                "A repeated sweep must not queue a second invoice cancellation");
        assertEquals(OrderStatus.CANCELLED,
                orderRepository.findById(fixture.orderId).orElseThrow().getOrderStatus());
    }

    /**
     * A delivered order is never cancelled by a background sweep, whatever
     * its payment row says. This state is not reachable through the normal
     * flow, which is exactly why it is worth pinning: silently cancelling a
     * delivered order - reversing its invoice and inflating stock for goods
     * that are physically gone - is a far worse outcome than leaving one
     * odd payment row alone for a human to look at.
     */
    @Test
    void deliveredOrderIsNeverCancelledByTheSweep() {
        Fixture fixture = newPendingUpiOrder();
        Order order = orderRepository.findById(fixture.orderId).orElseThrow();
        order.setOrderStatus(OrderStatus.DELIVERED);
        orderRepository.save(order);

        paymentService.expireOneStalePayment(fixture.paymentId);

        assertEquals(OrderStatus.DELIVERED,
                orderRepository.findById(fixture.orderId).orElseThrow().getOrderStatus(),
                "The sweep must refuse to cancel a delivered order");
        assertEquals(0, cancellationEventsFor(fixture.orderId),
                "No invoice may be cancelled for a delivered order");
    }

    /**
     * Cancellation getting there first wins, and the sweep adds nothing on
     * top - no second cancellation event, no second restore. cancelOrder
     * makes a PENDING UPI payment terminal precisely so the sweep stops
     * rediscovering it; this asserts the sweep then bails out cleanly.
     */
    @Test
    void alreadyCancelledOrderIsLeftAloneBySweep() {
        Fixture fixture = newPendingUpiOrder();

        orderService.cancelOrder(fixture.orderId, fixture.customerId, false);
        long eventsAfterCancel = cancellationEventsFor(fixture.orderId);

        paymentService.expireOneStalePayment(fixture.paymentId);

        assertEquals(eventsAfterCancel, cancellationEventsFor(fixture.orderId),
                "The sweep must not duplicate a cancellation the customer already made");
        assertEquals(STOCK_AFTER_ORDER_PLACED + ORDERED_QUANTITY,
                inventoryRepository.findByProductVariantId(fixture.variantId).orElseThrow().getStock(),
                "Stock goes back once across both paths");
        assertNotEquals(PaymentStatus.PENDING,
                paymentRepository.findById(fixture.paymentId).orElseThrow().getPaymentStatus(),
                "A cancelled order's payment must be terminal or the sweep keeps finding it");
    }

    private long cancellationEventsFor(Long orderId) {
        List<OutboxEvent> events = outboxEventRepository.findAll();
        return events.stream()
                .filter(e -> OutboxWorker.AGGREGATE_ORDER.equals(e.getAggregateType()))
                .filter(e -> orderId.equals(e.getAggregateId()))
                .filter(e -> OutboxWorker.EVENT_ORDER_CANCELLED.equals(e.getEventType()))
                .count();
    }

    private record Fixture(Long orderId, Long customerId, Long variantId, Long paymentId) { }

    /**
     * An order sitting exactly where an abandoned UPI checkout leaves it:
     * PENDING_CONFIRMATION, stock already decremented at checkout, payment
     * PENDING and backdated well past the expiry window so the sweep
     * considers it genuinely abandoned.
     */
    private Fixture newPendingUpiOrder() {
        Long variantId = createProductVariant();

        Inventory inventory = new Inventory();
        inventory.setProductVariant(productVariantRepository.findById(variantId).orElseThrow());
        inventory.setStock(STOCK_AFTER_ORDER_PLACED);
        inventoryRepository.save(inventory);

        Customer customer = new Customer();
        customer.setFullName("Upi Expiry Customer");
        customer.setEmail("upi-expiry-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber("9" + String.valueOf(System.nanoTime()).substring(0, 9));
        customer.setPassword("irrelevant-for-this-test");
        customer.setEnabled(true);
        customer.setActive(true);
        customer = customerRepository.save(customer);

        Order order = new Order();
        order.setOrderNumber("UPIEXP-" + System.nanoTime());
        order.setCustomer(customer);
        order.setTotalAmount(new BigDecimal("360.00"));
        order.setOrderStatus(OrderStatus.PENDING_CONFIRMATION);
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

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.UPI);
        payment.setPaymentStatus(PaymentStatus.PENDING);
        payment.setAmount(new BigDecimal("360.00"));
        payment.setPaymentDate(LocalDateTime.now().minusDays(1));
        payment.setActive(true);
        payment = paymentRepository.save(payment);

        return new Fixture(order.getId(), customer.getId(), variantId, payment.getId());
    }

    private Long createProductVariant() {
        Category category = new Category();
        category.setName("Upi Expiry Category " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        category = categoryRepository.save(category);

        Product product = new Product();
        product.setName("Upi Expiry Item " + System.nanoTime());
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

        return variant.getId();
    }
}
