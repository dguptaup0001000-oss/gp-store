package com.gpstore.payment;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Order;
import com.gpstore.entity.Payment;
import com.gpstore.enums.OrderStatus;
import com.gpstore.enums.PaymentMethod;
import com.gpstore.enums.PaymentProvider;
import com.gpstore.enums.PaymentStatus;
import com.gpstore.payment.gateway.PaymentGateway;
import com.gpstore.payment.gateway.PaymentGateway.GatewayRefund;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.PaymentRepository;
import com.gpstore.service.OrderService;
import com.gpstore.service.OutboxWorker;
import com.gpstore.service.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Cancelling a paid order is how most refunds actually start.
 *
 * THE HOLE THIS CLOSES, WHICH SURVIVED THE FIRST REFUND FIX.
 * RefundReachesTheProviderTest covers the admin's Refund button. It does not
 * cover cancellation, and cancellation is the common path: a customer
 * cancels, or the shop cancels for them, and the order was already paid.
 *
 * cancelOrder set a SUCCESS payment to REFUND_PENDING and stopped. Nothing
 * was sent to the provider, no refund id was recorded, and no channel. What
 * that left behind was worse than doing nothing:
 *
 *   - refundPayment refused the order outright ("a refund has already been
 *     requested"), so the shop could not send it by any means the app
 *     offered.
 *   - completeRefund saw a null refundChannel, decided that meant cash, and
 *     marked the payment REFUNDED with a refundedAt timestamp - for money
 *     that had never left Cashfree.
 *
 * That is the original bug, on the path that carries most of the traffic:
 * the record said the customer had been paid back, and the money had not
 * moved. So the assertions here are about the provider call and about what
 * completeRefund refuses to do, never about the status alone.
 *
 * WHY THE GATEWAY CALL IS NOT MADE INSIDE cancelOrder. That method holds
 * locks on the order, the payment and the inventory rows. An HTTP call to
 * Cashfree while holding them would let a slow provider stall every other
 * write touching that order. The cancellation records the intent durably in
 * the outbox and commits; the outbox worker sends it with no lock held, and
 * retries if the provider is down. The refund id is derived from the payment
 * (see PaymentService.refundIdFor), so an at-least-once redelivery reaches
 * the same refund rather than sending the money twice.
 */
@SpringBootTest(properties = {
        "cashfree.webhook-secret=cancel-refund-test-secret",
        // The outbox worker must not drain in the background here: these
        // tests drive the handler themselves so they can assert on what it
        // did. A scheduled drain racing them would make the assertions
        // depend on timing.
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "payment.expiry-interval-ms=3600000"
})
@DisplayName("Cancelling a prepaid order sends the money back")
class CancellingAPrepaidOrderRefundsItTest {

    private static final BigDecimal AMOUNT = new BigDecimal("310.00");

    @Autowired private OrderService orderService;
    @Autowired private PaymentService paymentService;
    @Autowired private OutboxWorker outboxWorker;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private com.gpstore.repository.OutboxEventRepository outboxEventRepository;

    @MockitoSpyBean private PaymentGateway gateway;

    @Test
    @DisplayName("cancelling a paid online order queues a real refund, not just a status")
    void cancellationQueuesTheRefund() {
        Order order = persistedOrder(OrderStatus.CONFIRMED);
        Payment payment = onlinePayment(order, PaymentStatus.SUCCESS);

        orderService.cancelOrder(order.getId(), order.getCustomer().getId(), true);

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.REFUND_PENDING, after.getPaymentStatus());

        // THE ASSERTION THE OLD CODE COULD NOT PASS. A REFUND_PENDING with no
        // channel is not a refund in progress, it is a note to self that
        // nothing in the system acts on.
        assertEquals(Payment.RefundChannel.GATEWAY, after.getRefundChannel(),
                "A cancelled prepaid order owes money back through the provider, "
                        + "and the row has to say so or nothing will ever send it.");
        assertEquals(0, AMOUNT.compareTo(after.getRefundAmount()));

        assertTrue(hasQueuedRefund(order.getId()),
                "The refund has to be durable before the transaction commits - "
                        + "otherwise a crash here loses it silently.");
    }

    @Test
    @DisplayName("the queued refund is what actually reaches the provider")
    void theOutboxSendsIt() {
        Order order = persistedOrder(OrderStatus.CONFIRMED);
        Payment payment = onlinePayment(order, PaymentStatus.SUCCESS);

        doReturn(new GatewayRefund(PaymentService.refundIdFor(payment), "cf_cancel_1",
                GatewayRefund.State.PENDING, AMOUNT, null))
                .when(gateway).requestRefund(any());

        orderService.cancelOrder(order.getId(), order.getCustomer().getId(), true);
        outboxWorker.drain();

        // MATCHED ON THIS ORDER, not counted globally. drain() empties the
        // whole outbox, so it also sends the refunds the other tests in this
        // class queued and left there - a bare verify(gateway) would count
        // those too and fail for a reason that has nothing to do with this
        // test. What matters is that exactly one refund went out for THIS
        // order, carrying its own provider order id.
        verify(gateway, times(1)).requestRefund(argThat(
                r -> payment.getProviderOrderId().equals(r.providerOrderId())));

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals("cf_cancel_1", after.getProviderRefundId());
        assertEquals(PaymentService.refundIdFor(payment), after.getRefundId());
        assertNull(after.getRefundedAt(),
                "PENDING at the provider is not money in the customer's account.");
    }

    @Test
    @DisplayName("a redelivered refund cannot send the money a second time")
    void redeliveryIsIdempotent() {
        Order order = persistedOrder(OrderStatus.CONFIRMED);
        Payment payment = onlinePayment(order, PaymentStatus.SUCCESS);

        doReturn(new GatewayRefund(PaymentService.refundIdFor(payment), "cf_cancel_2",
                GatewayRefund.State.PENDING, AMOUNT, null))
                .when(gateway).requestRefund(any());

        orderService.cancelOrder(order.getId(), order.getCustomer().getId(), true);

        // Outbox delivery is at-least-once by design, so the handler WILL be
        // run more than once in production. Running it again must be a no-op
        // rather than a second refund.
        paymentService.sendRefundToProvider(order.getId());
        paymentService.sendRefundToProvider(order.getId());

        verify(gateway, times(1)).requestRefund(any());
    }

    @Test
    @DisplayName("completing a cancellation refund cannot mark it paid back as cash")
    void completingCannotFakeAPrepaidRefund() {
        Order order = persistedOrder(OrderStatus.CONFIRMED);
        Payment payment = onlinePayment(order, PaymentStatus.SUCCESS);

        // The provider is unreachable, so the cancellation records the intent
        // and the refund never goes out. This is the state the shop is in
        // when Cashfree is down - and the state in which the old code would
        // happily call it refunded.
        doThrow(new com.gpstore.exception.BadRequestException("provider unreachable"))
                .when(gateway).requestRefund(any());

        orderService.cancelOrder(order.getId(), order.getCustomer().getId(), true);

        // THE ASSERTION THAT MATTERS. Pressing "complete" on a prepaid refund
        // the provider never took must not produce a REFUNDED row. A shop
        // clicking a button is not a bank transfer.
        assertThrows(com.gpstore.exception.ConflictException.class,
                () -> paymentService.completeRefund(order.getId()),
                "Nothing was sent to the provider, so nothing may be marked refunded.");

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertNotEquals(PaymentStatus.REFUNDED, after.getPaymentStatus());
        assertNull(after.getRefundedAt());
    }

    @Test
    @DisplayName("a cancelled COD order that was never paid owes nothing")
    void unpaidCodOwesNothing() {
        Order order = persistedOrder(OrderStatus.PENDING_CONFIRMATION);
        Payment payment = codPayment(order, PaymentStatus.COD_PENDING);

        orderService.cancelOrder(order.getId(), order.getCustomer().getId(), true);

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.FAILED, after.getPaymentStatus(),
                "Money that was never collected cannot be refunded.");
        assertNull(after.getRefundChannel());
        assertFalse(hasQueuedRefund(order.getId()));
        verify(gateway, never()).requestRefund(any());
    }

    @Test
    @DisplayName("a cancelled COD order the rider already collected is a cash refund")
    void collectedCodIsCash() {
        Order order = persistedOrder(OrderStatus.CONFIRMED);
        Payment payment = codPayment(order, PaymentStatus.SUCCESS);

        orderService.cancelOrder(order.getId(), order.getCustomer().getId(), true);

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.REFUND_PENDING, after.getPaymentStatus());
        assertEquals(Payment.RefundChannel.CASH, after.getRefundChannel(),
                "Cash never went through the provider, so there is nothing to ask it for.");
        assertFalse(hasQueuedRefund(order.getId()),
                "Queueing a gateway refund for cash would fail forever at the provider.");
        verify(gateway, never()).requestRefund(any());
    }

    // ------------------------------------------------------------ fixtures

    private boolean hasQueuedRefund(Long orderId) {
        return outboxEventRepository.findAll().stream()
                .anyMatch(e -> OutboxWorker.EVENT_REFUND_REQUESTED.equals(e.getEventType())
                        && orderId.equals(e.getAggregateId()));
    }

    private Order persistedOrder(OrderStatus status) {
        Customer customer = new Customer();
        customer.setFullName("Cancel Refund Customer");
        customer.setEmail("cancelrefund-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber("9" + String.format("%09d", System.nanoTime() % 1_000_000_000L));
        customer.setPassword("irrelevant-for-this-test");
        customer.setEnabled(true);
        customer.setActive(true);
        customer = customerRepository.save(customer);

        Order order = new Order();
        order.setOrderNumber("CANCELREFUND-" + System.nanoTime());
        order.setCustomer(customer);
        order.setTotalAmount(AMOUNT);
        order.setOrderStatus(status);
        order.setOrderDate(LocalDateTime.now());
        order.setActive(true);
        return orderRepository.save(order);
    }

    private Payment onlinePayment(Order order, PaymentStatus status) {
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.ONLINE);
        payment.setPaymentStatus(status);
        payment.setProvider(PaymentProvider.CASHFREE);
        payment.setProviderOrderId("GP-" + order.getId() + "-cancelrefund");
        payment.setAmount(AMOUNT);
        payment.setCurrency("INR");
        payment.setActive(true);
        return paymentRepository.save(payment);
    }

    private Payment codPayment(Order order, PaymentStatus status) {
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.COD);
        payment.setPaymentStatus(status);
        payment.setAmount(AMOUNT);
        payment.setActive(true);
        return paymentRepository.save(payment);
    }
}
