package com.gpstore.service;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * A refund that goes out and never comes back has to be noticed.
 *
 * WHAT THIS FILE EXISTS TO STOP. A refund settles through a bank over days,
 * so REFUND_PENDING is a normal state to sit in for a while. The failure
 * that hides inside it is the one that never leaves: the provider took the
 * refund, the webhook that would have confirmed it was lost or never sent,
 * and the row stays REFUND_PENDING for ever.
 *
 * V37 added an index for precisely the query that finds those - and nothing
 * ever issued it. So the shop's books could say a customer was owed money
 * that had been returned days earlier, or, the way round that actually
 * hurts, could say a refund was travelling when the provider had rejected
 * it and nobody was looking.
 *
 * The webhook stays the fast path. This is the one that cannot be lost: a
 * push that never arrives is invisible, while a poll that fails just runs
 * again.
 *
 * IN THE SERVICE PACKAGE, beside UpiExpiryStateMachineTest, because it
 * drives reconcileRefundsNow - the sweep without its ShedLock wrapper.
 * Calling the scheduled method twice in one test class would have the second
 * call silently skipped by lockAtLeastFor, leaving a test asserting against a
 * method body that never ran.
 */
@SpringBootTest(properties = {
        "cashfree.webhook-secret=stuck-refund-test-secret",
        // The sweep must not fire on its own here - these tests call it
        // directly so they can assert on what one run did. A scheduled run
        // racing them would make the assertions depend on timing.
        "refund.reconcile-initial-delay-ms=3600000",
        "refund.reconcile-interval-ms=3600000",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "payment.expiry-interval-ms=3600000"
})
@DisplayName("A refund the provider never confirmed gets chased")
class StuckRefundsGetChasedTest {

    private static final BigDecimal AMOUNT = new BigDecimal("175.00");

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private CustomerRepository customerRepository;

    @MockitoSpyBean private PaymentGateway gateway;

    @Test
    @DisplayName("a refund the provider has settled is picked up without any webhook")
    void aLandedRefundIsFoundByPolling() {
        Payment payment = refundInFlight(hoursAgo(2));

        doReturn(new GatewayRefund(payment.getRefundId(), "cf_stuck_1",
                GatewayRefund.State.SUCCEEDED, AMOUNT, null))
                .when(gateway).fetchRefund(any(), eq(payment.getRefundId()));

        assertTrue(paymentService.reconcileOneRefund(payment.getId()),
                "The provider says the money landed, so this run settled it.");

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.REFUNDED, after.getPaymentStatus());
        assertNotNull(after.getRefundedAt(),
                "Settling without a timestamp is the state the whole refund "
                        + "work exists to make impossible.");
    }

    @Test
    @DisplayName("a refund the provider rejected surfaces its reason instead of settling")
    void aRejectedRefundIsRecordedNotSettled() {
        Payment payment = refundInFlight(hoursAgo(5));

        doReturn(new GatewayRefund(payment.getRefundId(), "cf_stuck_2",
                GatewayRefund.State.FAILED, AMOUNT, "Beneficiary account closed"))
                .when(gateway).fetchRefund(any(), eq(payment.getRefundId()));

        assertFalse(paymentService.reconcileOneRefund(payment.getId()),
                "A rejection is not a settlement - the money did not go back.");

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.REFUND_PENDING, after.getPaymentStatus());
        assertNull(after.getRefundedAt());
        assertEquals("Beneficiary account closed", after.getRefundFailureReason(),
                "The shop cannot act on a rejection it cannot see.");
    }

    @Test
    @DisplayName("a refund still travelling is left alone")
    void aTravellingRefundIsLeftAlone() {
        Payment payment = refundInFlight(hoursAgo(1));

        doReturn(new GatewayRefund(payment.getRefundId(), "cf_stuck_3",
                GatewayRefund.State.PENDING, AMOUNT, null))
                .when(gateway).fetchRefund(any(), eq(payment.getRefundId()));

        assertFalse(paymentService.reconcileOneRefund(payment.getId()));

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.REFUND_PENDING, after.getPaymentStatus());
        assertNull(after.getRefundedAt());
        assertNull(after.getRefundFailureReason(),
                "Days at the bank is normal, not a failure to report.");
    }

    @Test
    @DisplayName("a refund already settled is not asked about again")
    void anAlreadySettledRefundIsNotReAsked() {
        Payment payment = refundInFlight(hoursAgo(3));
        payment.setPaymentStatus(PaymentStatus.REFUNDED);
        payment.setRefundedAt(LocalDateTime.now());
        paymentRepository.save(payment);

        assertFalse(paymentService.reconcileOneRefund(payment.getId()));

        // A webhook or a shopkeeper may settle a refund between the sweep's
        // query and this call. Their transition wins, and asking the provider
        // again would be noise on someone else's rate limit.
        verify(gateway, never()).fetchRefund(any(), eq(payment.getRefundId()));
    }

    @Test
    @DisplayName("cash refunds are never chased at a provider that never had the money")
    void cashRefundsAreNotChased() {
        Order order = persistedOrder();
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.COD);
        payment.setPaymentStatus(PaymentStatus.REFUND_PENDING);
        payment.setRefundChannel(Payment.RefundChannel.CASH);
        payment.setRefundAmount(AMOUNT);
        payment.setAmount(AMOUNT);
        payment.setActive(true);
        payment = paymentRepository.save(payment);

        // No refundId, because nothing was ever sent anywhere.
        assertFalse(paymentService.reconcileOneRefund(payment.getId()));
        verify(gateway, never()).fetchRefund(any(), any());
    }

    @Test
    @DisplayName("the sweep finds an unconfirmed refund on its own")
    void theSweepFindsIt() {
        Payment payment = refundInFlight(hoursAgo(4));

        doReturn(new GatewayRefund(payment.getRefundId(), "cf_stuck_4",
                GatewayRefund.State.SUCCEEDED, AMOUNT, null))
                .when(gateway).fetchRefund(any(), eq(payment.getRefundId()));

        // The whole point: nobody told the sweep about this payment. It has
        // to find it by asking the database which refunds never landed.
        paymentService.reconcileRefundsNow();

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.REFUNDED, after.getPaymentStatus());
        assertNotNull(after.getRefundedAt());
    }

    @Test
    @DisplayName("a provider that is down does not stop the sweep reaching the next refund")
    void oneProviderFailureDoesNotEndTheRun() {
        Payment broken = refundInFlight(hoursAgo(6));
        Payment fine = refundInFlight(hoursAgo(6));

        doThrow(new com.gpstore.exception.BadRequestException("provider unreachable"))
                .when(gateway).fetchRefund(any(), eq(broken.getRefundId()));
        doReturn(new GatewayRefund(fine.getRefundId(), "cf_stuck_5",
                GatewayRefund.State.SUCCEEDED, AMOUNT, null))
                .when(gateway).fetchRefund(any(), eq(fine.getRefundId()));

        paymentService.reconcileRefundsNow();

        // A sweep that gives up on the first error is a sweep that stops
        // working the first busy afternoon.
        assertEquals(PaymentStatus.REFUNDED,
                paymentRepository.findById(fine.getId()).orElseThrow().getPaymentStatus(),
                "The refund after the failing one must still be reconciled.");
        assertEquals(PaymentStatus.REFUND_PENDING,
                paymentRepository.findById(broken.getId()).orElseThrow().getPaymentStatus());
    }

    @Test
    @DisplayName("a refund sent moments ago is too young to be worth asking about")
    void aFreshRefundIsNotAskedAboutYet() {
        Payment fresh = refundInFlight(LocalDateTime.now());

        paymentService.reconcileRefundsNow();

        // Asking the provider about a refund it took thirty seconds ago tells
        // nobody anything and spends a rate limit that the stuck ones need.
        verify(gateway, never()).fetchRefund(any(), eq(fresh.getRefundId()));
    }

    // ------------------------------------------------------------ fixtures

    private static LocalDateTime hoursAgo(int hours) {
        return LocalDateTime.now().minusHours(hours);
    }

    /** A gateway refund that went out and has not been confirmed landed. */
    private Payment refundInFlight(LocalDateTime requestedAt) {
        Order order = persistedOrder();

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.ONLINE);
        payment.setPaymentStatus(PaymentStatus.REFUND_PENDING);
        payment.setProvider(PaymentProvider.CASHFREE);
        payment.setProviderOrderId("GP-" + order.getId() + "-stuckrefund");
        payment.setAmount(AMOUNT);
        payment.setCurrency("INR");
        payment.setActive(true);
        payment.setRefundChannel(Payment.RefundChannel.GATEWAY);
        payment.setRefundAmount(AMOUNT);
        payment.setRefundRequestedAt(requestedAt);
        payment = paymentRepository.save(payment);

        // Matches PaymentService.refundIdFor, which derives the id from the
        // payment so a retry cannot send the money twice.
        payment.setRefundId(PaymentService.refundIdFor(payment));
        return paymentRepository.save(payment);
    }

    private Order persistedOrder() {
        Customer customer = new Customer();
        customer.setFullName("Stuck Refund Customer");
        customer.setEmail("stuckrefund-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber("9" + String.format("%09d", System.nanoTime() % 1_000_000_000L));
        customer.setPassword("irrelevant-for-this-test");
        customer.setEnabled(true);
        customer.setActive(true);
        customer = customerRepository.save(customer);

        Order order = new Order();
        order.setOrderNumber("STUCKREFUND-" + System.nanoTime());
        order.setCustomer(customer);
        order.setTotalAmount(AMOUNT);
        order.setOrderStatus(OrderStatus.CANCELLED);
        order.setOrderDate(LocalDateTime.now());
        order.setActive(true);
        return orderRepository.save(order);
    }
}
