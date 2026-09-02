package com.gpstore.service;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Order;
import com.gpstore.entity.Payment;
import com.gpstore.enums.OrderStatus;
import com.gpstore.enums.PaymentMethod;
import com.gpstore.enums.PaymentProvider;
import com.gpstore.enums.PaymentStatus;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.payment.gateway.PaymentGateway;
import com.gpstore.payment.gateway.PaymentGateway.GatewayRefund;
import com.gpstore.payment.gateway.PaymentGateway.GatewayRefundRequest;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A shop can send back part of an order without sending back all of it.
 *
 * WHY THIS IS WORTH BUILDING. A customer keeps three items out of five and
 * hands back the rest. Until now the only refund the shop could issue was
 * the whole order, so the shopkeeper's choices were to give back too much or
 * to settle the difference in cash off the books - and the second is how a
 * shop's records stop matching its bank.
 *
 * WHAT THE ASSERTIONS ARE REALLY ABOUT. One invariant, defended from every
 * direction a bad number can arrive from: a customer can never be sent back
 * more than they paid. The amount comes from a request body, so an admin
 * screen with a typo and a hand-rolled request look identical here, and both
 * are checked under the order and payment row locks before a rupee moves.
 */
@SpringBootTest(properties = {
        "cashfree.webhook-secret=partial-refund-test-secret",
        "refund.reconcile-initial-delay-ms=3600000",
        "refund.reconcile-interval-ms=3600000",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "payment.expiry-interval-ms=3600000"
})
@DisplayName("Part of an order can go back without all of it")
class PartialRefundsTest {

    private static final BigDecimal PAID = new BigDecimal("500.00");

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private CustomerRepository customerRepository;

    @MockitoSpyBean private PaymentGateway gateway;

    @Test
    @DisplayName("the provider is asked for the part, not the whole")
    void aPartialRefundSendsThePartialAmount() {
        Order order = persistedOrder();
        Payment payment = onlinePayment(order);
        stubRefund(payment, "cf_partial_1", GatewayRefund.State.PENDING);

        paymentService.refundPayment(order.getId(), new BigDecimal("200.00"));

        ArgumentCaptor<GatewayRefundRequest> sent = ArgumentCaptor.forClass(GatewayRefundRequest.class);
        verify(gateway).requestRefund(sent.capture());

        assertEquals(0, new BigDecimal("200.00").compareTo(sent.getValue().amount()),
                "The provider must be asked for what the shop decided, not the order total.");

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("200.00").compareTo(after.getRefundAmount()));
        assertEquals(0, PAID.compareTo(after.getAmount()),
                "What the customer paid is history and must not be rewritten by a refund.");
    }

    @Test
    @DisplayName("no amount at all still means the whole order")
    void omittingTheAmountRefundsEverything() {
        Order order = persistedOrder();
        Payment payment = onlinePayment(order);
        stubRefund(payment, "cf_partial_2", GatewayRefund.State.PENDING);

        // Every existing caller - cancellation, the plain Refund button - goes
        // through this path and must keep meaning what it always meant.
        paymentService.refundPayment(order.getId());

        ArgumentCaptor<GatewayRefundRequest> sent = ArgumentCaptor.forClass(GatewayRefundRequest.class);
        verify(gateway).requestRefund(sent.capture());
        assertEquals(0, PAID.compareTo(sent.getValue().amount()));
    }

    @Test
    @DisplayName("more than was paid is refused, and nothing is sent")
    void refundingMoreThanWasPaidIsRefused() {
        Order order = persistedOrder();
        Payment payment = onlinePayment(order);

        assertThrows(ConflictException.class,
                () -> paymentService.refundPayment(order.getId(), new BigDecimal("500.01")),
                "One paisa over what the customer paid is still over.");

        // THE ASSERTION THAT MATTERS MORE THAN THE EXCEPTION: refusing after
        // the money left would be no protection at all.
        verify(gateway, never()).requestRefund(any());
        assertEquals(PaymentStatus.SUCCESS,
                paymentRepository.findById(payment.getId()).orElseThrow().getPaymentStatus());
    }

    @Test
    @DisplayName("zero and negative amounts are refused")
    void zeroAndNegativeAreRefused() {
        Order order = persistedOrder();
        onlinePayment(order);

        assertThrows(BadRequestException.class,
                () -> paymentService.refundPayment(order.getId(), BigDecimal.ZERO));
        assertThrows(BadRequestException.class,
                () -> paymentService.refundPayment(order.getId(), new BigDecimal("-50.00")));

        // A negative refund would be a charge dressed as a refund.
        verify(gateway, never()).requestRefund(any());
    }

    @Test
    @DisplayName("a fraction of a paisa is rounded before it is checked, not after")
    void subPaisaAmountsAreRoundedThenChecked() {
        Order order = persistedOrder();
        Payment payment = onlinePayment(order);
        stubRefund(payment, "cf_partial_3", GatewayRefund.State.PENDING);

        // 499.999 rounds to 500.00, which is exactly what was paid - so it is
        // allowed, and what goes to the provider is the rounded figure. If the
        // check ran before the rounding, a request could be validated as one
        // number and sent as another.
        paymentService.refundPayment(order.getId(), new BigDecimal("499.999"));

        ArgumentCaptor<GatewayRefundRequest> sent = ArgumentCaptor.forClass(GatewayRefundRequest.class);
        verify(gateway).requestRefund(sent.capture());
        assertEquals(0, new BigDecimal("500.00").compareTo(sent.getValue().amount()),
                "What was validated has to be what is sent.");
    }

    @Test
    @DisplayName("rounding cannot be used to get back more than was paid")
    void roundingCannotExceedThePayment() {
        Order order = persistedOrder();
        onlinePayment(order);

        // 500.005 rounds half-up to 500.01, which is more than was paid. The
        // rounding must not become a way through the check.
        assertThrows(ConflictException.class,
                () -> paymentService.refundPayment(order.getId(), new BigDecimal("500.005")));
        verify(gateway, never()).requestRefund(any());
    }

    @Test
    @DisplayName("the exact amount paid is allowed")
    void theFullAmountIsAllowedExplicitly() {
        Order order = persistedOrder();
        Payment payment = onlinePayment(order);
        stubRefund(payment, "cf_partial_4", GatewayRefund.State.PENDING);

        paymentService.refundPayment(order.getId(), PAID);

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(0, PAID.compareTo(after.getRefundAmount()));
    }

    @Test
    @DisplayName("a second refund on the same payment is refused rather than half-supported")
    void aSecondRefundIsRefused() {
        Order order = persistedOrder();
        Payment payment = onlinePayment(order);
        stubRefund(payment, "cf_partial_5", GatewayRefund.State.PENDING);

        paymentService.refundPayment(order.getId(), new BigDecimal("200.00"));
        reset(gateway);

        // The refund id is derived from the payment id, so a second refund
        // would collide with the first at the provider. Refusing is the
        // honest answer until refunds are their own rows; silently reusing
        // the id could send the money twice.
        assertThrows(ConflictException.class,
                () -> paymentService.refundPayment(order.getId(), new BigDecimal("100.00")));
        verify(gateway, never()).requestRefund(any());
    }

    @Test
    @DisplayName("a partial refund settles as REFUNDED for the part that went back")
    void aPartialRefundSettles() {
        Order order = persistedOrder();
        Payment payment = onlinePayment(order);
        stubRefund(payment, "cf_partial_6", GatewayRefund.State.SUCCEEDED);

        paymentService.refundPayment(order.getId(), new BigDecimal("125.50"));

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.REFUNDED, after.getPaymentStatus());
        assertNotNull(after.getRefundedAt());
        assertEquals(0, new BigDecimal("125.50").compareTo(after.getRefundAmount()),
                "The row has to say how much went back, or the books cannot be reconciled.");
        assertEquals(0, PAID.compareTo(after.getAmount()));
    }

    @Test
    @DisplayName("a partial COD refund is recorded as cash without touching a provider")
    void partialCashRefund() {
        Order order = persistedOrder();
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.COD);
        payment.setPaymentStatus(PaymentStatus.SUCCESS);
        payment.setAmount(PAID);
        payment.setActive(true);
        payment = paymentRepository.save(payment);

        paymentService.refundPayment(order.getId(), new BigDecimal("80.00"));

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(Payment.RefundChannel.CASH, after.getRefundChannel());
        assertEquals(0, new BigDecimal("80.00").compareTo(after.getRefundAmount()));
        verify(gateway, never()).requestRefund(any());
    }

    // ------------------------------------------------------------ fixtures

    private void stubRefund(Payment payment, String providerRefundId, GatewayRefund.State state) {
        doAnswer(call -> {
            GatewayRefundRequest asked = call.getArgument(0);
            // Echo the asked-for amount back, the way a provider does - so a
            // test cannot pass by the stub quietly substituting the full one.
            return new GatewayRefund(PaymentService.refundIdFor(payment), providerRefundId,
                    state, asked.amount(), null);
        }).when(gateway).requestRefund(any());
    }

    private Order persistedOrder() {
        Customer customer = new Customer();
        customer.setFullName("Partial Refund Customer");
        customer.setEmail("partial-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber("9" + String.format("%09d", System.nanoTime() % 1_000_000_000L));
        customer.setPassword("irrelevant-for-this-test");
        customer.setEnabled(true);
        customer.setActive(true);
        customer = customerRepository.save(customer);

        Order order = new Order();
        order.setOrderNumber("PARTIAL-" + System.nanoTime());
        order.setCustomer(customer);
        order.setTotalAmount(PAID);
        order.setOrderStatus(OrderStatus.DELIVERED);
        order.setOrderDate(LocalDateTime.now());
        order.setActive(true);
        return orderRepository.save(order);
    }

    private Payment onlinePayment(Order order) {
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.ONLINE);
        payment.setPaymentStatus(PaymentStatus.SUCCESS);
        payment.setProvider(PaymentProvider.CASHFREE);
        payment.setProviderOrderId("GP-" + order.getId() + "-partial");
        payment.setAmount(PAID);
        payment.setCurrency("INR");
        payment.setActive(true);
        return paymentRepository.save(payment);
    }
}
