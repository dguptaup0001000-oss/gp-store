package com.gpstore.service;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Order;
import com.gpstore.entity.Payment;
import com.gpstore.dto.response.PaymentResponse;
import com.gpstore.enums.OrderStatus;
import com.gpstore.enums.PaymentMethod;
import com.gpstore.enums.PaymentStatus;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private UpiPaymentService upiPaymentService;
    @Mock private OrderService orderService;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        // `self` is this bean's own Spring proxy in production, used only so
        // the expiry sweep's per-payment REQUIRES_NEW transaction actually
        // applies (see PaymentService.self). Null here on purpose, along
        // with the outbox repository and notification service the sweep
        // uses: none of the tests in this class go through the sweep, and a
        // null that would NPE loudly is better than a stub that quietly
        // pretends transaction boundaries exist in a plain-constructed
        // instance where they cannot. The sweep's real behaviour is covered
        // against a live database in UpiExpiryStateMachineTest and
        // InventoryRestorationConcurrencyTest instead.
        paymentService = new PaymentService(
                paymentRepository, orderRepository, auditLogService, upiPaymentService, orderService,
                null, null, new com.gpstore.payment.gateway.CashfreeProperties(), null, null, null, null, 30, 60, 100, 50);
    }

    @Test
    void confirmingUpiPaymentAdvancesOrderStillPendingConfirmation() {
        Order order = orderWithStatus(1L, OrderStatus.PENDING_CONFIRMATION);
        Payment payment = pendingUpiPayment(order);

        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(payment.getOrder()));
        when(paymentRepository.findByOrderIdForUpdate(1L)).thenReturn(Optional.of(payment));
        // saveAndFlush, not save - matching what confirmUpiPayment actually
        // calls. It flushes deliberately, so that a duplicate transaction id
        // surfaces as a ConflictException inside the method rather than as a
        // raw DataIntegrityViolationException at commit (see the comment on
        // that call). Stubbing save() here left saveAndFlush unstubbed and
        // returning null, which is a mock-shaped NPE rather than anything
        // wrong with the code under test.
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse result = paymentService.confirmUpiPayment(1L, "TXN123");

        assertEquals(PaymentStatus.SUCCESS.name(), result.getPaymentStatus());
        assertEquals("TXN123", result.getTransactionId());
    }

    @Test
    void cannotConfirmUpiPaymentThatIsNotPending() {
        Order order = orderWithStatus(1L, OrderStatus.CONFIRMED);
        Payment payment = pendingUpiPayment(order);
        payment.setPaymentStatus(PaymentStatus.SUCCESS); // already confirmed once

        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(payment.getOrder()));
        when(paymentRepository.findByOrderIdForUpdate(1L)).thenReturn(Optional.of(payment));

        assertThrows(ConflictException.class, () -> paymentService.confirmUpiPayment(1L, "TXN999"));
    }

    @Test
    void cannotConfirmUpiOnACodPayment() {
        Order order = orderWithStatus(1L, OrderStatus.PENDING_CONFIRMATION);
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.COD);
        payment.setPaymentStatus(PaymentStatus.COD_PENDING);

        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(payment.getOrder()));
        when(paymentRepository.findByOrderIdForUpdate(1L)).thenReturn(Optional.of(payment));

        assertThrows(ConflictException.class, () -> paymentService.confirmUpiPayment(1L, "TXN1"));
    }

    @Test
    void completingCodPaymentMarksReceived() {
        Order order = orderWithStatus(2L, OrderStatus.PENDING_CONFIRMATION);
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.COD);
        payment.setPaymentStatus(PaymentStatus.COD_PENDING);

        when(orderRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(payment.getOrder()));
        when(paymentRepository.findByOrderIdForUpdate(2L)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse result = paymentService.completeCodPayment(2L);

        assertEquals(PaymentStatus.COD_RECEIVED.name(), result.getPaymentStatus());
    }

    @Test
    void onlineIsRejectedWhenCashfreeIsNotConfigured() {
        assertThrows(BadRequestException.class,
                () -> paymentService.parsePaymentMethod("ONLINE"));
    }

    @Test
    void onlineIsAllowedWhenCashfreeIsConfigured() {
        var props = new com.gpstore.payment.gateway.CashfreeProperties();
        props.setAppId("cf_test_app");
        props.setSecretKey("cf_test_secret");
        paymentService = new PaymentService(
                paymentRepository, orderRepository, auditLogService, upiPaymentService, orderService,
                // The extra null is the PaymentGateway: refunds are the only
                // thing that uses it, and no test in this class refunds.
                null, null, props, null, null, null, null, 30, 60, 100, 50);

        assertEquals(PaymentMethod.ONLINE, paymentService.parsePaymentMethod("ONLINE"));
        assertEquals(PaymentMethod.COD, paymentService.parsePaymentMethod("COD"));
    }

    @Test
    void refundNotAllowedForUnpaidCod() {
        Order order = orderWithStatus(3L, OrderStatus.CONFIRMED);
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.COD);
        payment.setPaymentStatus(PaymentStatus.COD_PENDING);

        when(orderRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(payment.getOrder()));
        when(paymentRepository.findByOrderIdForUpdate(3L)).thenReturn(Optional.of(payment));

        assertThrows(ConflictException.class, () -> paymentService.refundPayment(3L));
    }

    @Test
    void cannotRefundAlreadyRefundedPayment() {
        Order order = orderWithStatus(4L, OrderStatus.CANCELLED);
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.UPI);
        payment.setPaymentStatus(PaymentStatus.REFUNDED);

        when(orderRepository.findByIdForUpdate(4L)).thenReturn(Optional.of(payment.getOrder()));
        when(paymentRepository.findByOrderIdForUpdate(4L)).thenReturn(Optional.of(payment));

        assertThrows(ConflictException.class, () -> paymentService.refundPayment(4L));
    }

    @Test
    void refundMovesSuccessfulPaymentToPending() {
        Order order = orderWithStatus(5L, OrderStatus.CANCELLED);
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.UPI);
        payment.setPaymentStatus(PaymentStatus.SUCCESS);
        payment.setAmount(new BigDecimal("200"));

        when(orderRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(payment.getOrder()));
        when(paymentRepository.findByOrderIdForUpdate(5L)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse result = paymentService.refundPayment(5L);

        assertEquals(PaymentStatus.REFUND_PENDING.name(), result.getPaymentStatus());
    }

    private Order orderWithStatus(Long id, OrderStatus status) {
        Order order = new Order();
        order.setOrderStatus(status);
        order.setCustomer(new Customer());
        return order;
    }

    private Payment pendingUpiPayment(Order order) {
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.UPI);
        payment.setPaymentStatus(PaymentStatus.PENDING);
        payment.setAmount(new BigDecimal("100"));
        return payment;
    }
}
