package com.gpstore.service;

import com.gpstore.entity.Category;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Order;
import com.gpstore.entity.OrderItem;
import com.gpstore.entity.OutboxEvent;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.enums.OrderStatus;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.InvoiceRepository;
import com.gpstore.repository.OrderItemRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.OutboxEventRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 10: the outbox's actual guarantees.
 *
 * Post-order side effects used to live only in an in-memory executor. That
 * executor is bounded and applies backpressure correctly, but it is not
 * durable - anything queued or mid-flight when the JVM stops is gone
 * silently. This deployment restarts on every push to main and spins down
 * after ~15 minutes idle, so "the process stopped between committing an
 * order and generating its invoice" is an ordinary event here, not a rare
 * one. A missing invoice is a GST/accounting record the business is
 * required to have.
 *
 * These tests assert the three properties that make the outbox worth having:
 * the event is durable, processing is idempotent under at-least-once
 * redelivery, and a failing event retries with backoff rather than being
 * lost or spinning.
 */
@SpringBootTest
class OutboxDurabilityTest {

    @Autowired private OutboxWorker outboxWorker;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private InvoiceRepository invoiceRepository;

    /**
     * The core durability property: a claimed event that is processed twice
     * - which at-least-once delivery makes inevitable - must not produce two
     * invoices.
     *
     * This is the scenario where the process dies after the handler ran but
     * before the row was marked PROCESSED. The event is still PENDING on
     * restart, so it runs again. If generateForOrder's "already exists"
     * ConflictException were not treated as success, the event would retry
     * until it dead-lettered and an order WITH an invoice would be reported
     * as permanently failed.
     */
    @Test
    void reprocessingAnEventDoesNotDuplicateWork() {
        Long orderId = anOrderIdWithNoInvoice();

        OutboxEvent event = outboxEventRepository.save(OutboxEvent.of(
                OutboxWorker.AGGREGATE_ORDER, orderId, OutboxWorker.EVENT_ORDER_PLACED));

        outboxWorker.processClaimedEvent(event.getId());
        assertEquals(OutboxEvent.Status.PROCESSED,
                outboxEventRepository.findById(event.getId()).orElseThrow().getStatus());
        assertTrue(invoiceRepository.findByOrderId(orderId).isPresent(),
                "First processing must actually generate the invoice");

        // Simulate the crash-before-acknowledge case: the same work is
        // delivered again.
        OutboxEvent redelivered = outboxEventRepository.save(OutboxEvent.of(
                OutboxWorker.AGGREGATE_ORDER, orderId, OutboxWorker.EVENT_ORDER_PLACED));
        outboxWorker.processClaimedEvent(redelivered.getId());

        assertEquals(OutboxEvent.Status.PROCESSED,
                outboxEventRepository.findById(redelivered.getId()).orElseThrow().getStatus(),
                "A redelivered event whose work was already done must be treated as processed, "
                        + "not retried until it dead-letters");

        assertEquals(1, invoiceRepository.findAll().stream()
                        .filter(i -> i.getOrder() != null && i.getOrder().getId().equals(orderId))
                        .count(),
                "At-least-once delivery must never produce a second invoice for the same order");
    }

    /**
     * An event whose handler cannot succeed must back off and stay
     * recoverable, not spin and not vanish.
     *
     * An unknown event type is used as the permanent failure because it is
     * deterministic - a code/data mismatch, exactly the shape of failure
     * that must not be silently marked done.
     */
    @Test
    void aFailingEventBacksOffAndRemainsRecoverable() {
        OutboxEvent event = outboxEventRepository.save(OutboxEvent.of(
                OutboxWorker.AGGREGATE_ORDER, -1L, "NO_SUCH_EVENT_TYPE"));

        LocalDateTime before = LocalDateTime.now();
        outboxWorker.processClaimedEvent(event.getId());

        OutboxEvent after = outboxEventRepository.findById(event.getId()).orElseThrow();

        assertEquals(1, after.getAttempts(), "The attempt must be counted, or retries loop forever");
        assertEquals(OutboxEvent.Status.PENDING, after.getStatus(),
                "One failure must not dead-letter the event - transient faults are the common case");
        assertNotNull(after.getLastError(), "The failure reason must be recorded for diagnosis");
        assertTrue(after.getNextAttemptAt().isAfter(before),
                "Backoff must push the next attempt into the future, otherwise a permanently "
                        + "failing event spins the worker at full speed");
    }

    /**
     * Repeated failure eventually dead-letters rather than retrying forever -
     * but the row is KEPT. A permanently failed ORDER_PLACED means an order
     * with no invoice, which someone has to resolve; deleting it is how that
     * goes unnoticed.
     */
    @Test
    void anEventThatKeepsFailingIsEventuallyDeadLetteredButNotDeleted() {
        OutboxEvent event = outboxEventRepository.save(OutboxEvent.of(
                OutboxWorker.AGGREGATE_ORDER, -1L, "NO_SUCH_EVENT_TYPE"));

        // Drive it past maxAttempts. Each pass resets next_attempt_at into
        // the future, so it is reset here rather than waiting out the real
        // backoff.
        for (int i = 0; i < 12; i++) {
            OutboxEvent current = outboxEventRepository.findById(event.getId()).orElseThrow();
            if (current.getStatus() != OutboxEvent.Status.PENDING) {
                break;
            }
            current.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
            outboxEventRepository.save(current);
            outboxWorker.processClaimedEvent(event.getId());
        }

        OutboxEvent after = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertEquals(OutboxEvent.Status.FAILED, after.getStatus(),
                "An event that can never succeed must stop being retried");
        assertNotNull(after.getLastError());
    }

    /**
     * The claim query must be bounded. A backlog - a deploy pause, an outage,
     * a handler that failed for an hour - has to drain in steady batches
     * rather than being loaded into memory at once, which is the failure mode
     * that turns a recoverable backlog into an outage of its own.
     */
    @Test
    void claimingIsBoundedByBatchSize() {
        int inserted = 120;
        for (int i = 0; i < inserted; i++) {
            outboxEventRepository.save(OutboxEvent.of(
                    OutboxWorker.AGGREGATE_ORDER, -2L, "NO_SUCH_EVENT_TYPE"));
        }

        List<Long> claimed = outboxWorker.claimBatch();

        assertTrue(claimed.size() <= 50,
                "A single claim must never exceed the configured batch size (50), was " + claimed.size());
        assertTrue(claimed.size() > 0, "There is due work, so the claim must return some of it");
    }

    /**
     * A committed order with real line items and no invoice yet - enough for
     * InvoiceService.generateForOrder to compute a genuine total/tax
     * breakdown rather than operating on a stub that would not exercise the
     * same paths.
     */
    private Long anOrderIdWithNoInvoice() {
        Category category = new Category();
        category.setName("Outbox Category " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        category = categoryRepository.save(category);

        Product product = new Product();
        product.setName("Outbox Item " + System.nanoTime());
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

        Customer customer = new Customer();
        customer.setFullName("Outbox Test Customer");
        customer.setEmail("outbox-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber("9" + String.valueOf(System.nanoTime()).substring(0, 9));
        customer.setPassword("irrelevant-for-this-test");
        customer.setEnabled(true);
        customer.setActive(true);
        customer = customerRepository.save(customer);

        Order order = new Order();
        order.setOrderNumber("OUTBOX-" + System.nanoTime());
        order.setCustomer(customer);
        order.setTotalAmount(new BigDecimal("180.00"));
        order.setDeliveryFee(new BigDecimal("15.00"));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setOrderStatus(OrderStatus.CONFIRMED);
        order.setOrderDate(LocalDateTime.now());
        order.setActive(true);
        order = orderRepository.save(order);

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProductVariant(variant);
        item.setQuantity(2);
        item.setPrice(new BigDecimal("90.00"));
        item.setTotalPrice(new BigDecimal("180.00"));
        item.setGstRate(new BigDecimal("5"));
        item.setActive(true);
        orderItemRepository.save(item);

        return order.getId();
    }

    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository productVariantRepository;
}
