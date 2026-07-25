package com.gpstore.service;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Order;
import com.gpstore.entity.Payment;
import com.gpstore.enums.OrderStatus;
import com.gpstore.enums.PaymentMethod;
import com.gpstore.enums.PaymentStatus;
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
        paymentService = new PaymentService(
                paymentRepository, orderRepository, auditLogService, upiPaymentService, orderService, 30);
    }

    @Test
    void confirmingUpiPaymentAdvancesOrderStillPendingConfirmation() {
        Order order = orderWithStatus(1L, OrderStatus.PENDING_CONFIRMATION);
        Payment payment = pendingUpiPayment(order);

        when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment result = paymentService.confirmUpiPayment(1L, "TXN123");

        assertEquals(PaymentStatus.SUCCESS, result.getPaymentStatus());
        assertEquals("TXN123", result.getTransactionId());
    }

    @Test
    void cannotConfirmUpiPaymentThatIsNotPending() {
        Order order = orderWithStatus(1L, OrderStatus.CONFIRMED);
        Payment payment = pendingUpiPayment(order);
        payment.setPaymentStatus(PaymentStatus.SUCCESS); // already confirmed once

        when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.of(payment));

        assertThrows(ConflictException.class, () -> paymentService.confirmUpiPayment(1L, "TXN999"));
    }

    @Test
    void cannotConfirmUpiOnACodPayment() {
        Order order = orderWithStatus(1L, OrderStatus.PENDING_CONFIRMATION);
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.COD);
        payment.setPaymentStatus(PaymentStatus.COD_PENDING);

        when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.of(payment));

        assertThrows(ConflictException.class, () -> paymentService.confirmUpiPayment(1L, "TXN1"));
    }

    @Test
    void completingCodPaymentMarksReceived() {
        Order order = orderWithStatus(2L, OrderStatus.PENDING_CONFIRMATION);
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.COD);
        payment.setPaymentStatus(PaymentStatus.COD_PENDING);

        when(paymentRepository.findByOrderId(2L)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment result = paymentService.completeCodPayment(2L);

        assertEquals(PaymentStatus.COD_RECEIVED, result.getPaymentStatus());
    }

    @Test
    void refundNotAllowedForUnpaidCod() {
        Order order = orderWithStatus(3L, OrderStatus.CONFIRMED);
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.COD);
        payment.setPaymentStatus(PaymentStatus.COD_PENDING);

        when(paymentRepository.findByOrderId(3L)).thenReturn(Optional.of(payment));

        assertThrows(ConflictException.class, () -> paymentService.refundPayment(3L));
    }

    @Test
    void cannotRefundAlreadyRefundedPayment() {
        Order order = orderWithStatus(4L, OrderStatus.CANCELLED);
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.UPI);
        payment.setPaymentStatus(PaymentStatus.REFUNDED);

        when(paymentRepository.findByOrderId(4L)).thenReturn(Optional.of(payment));

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

        when(paymentRepository.findByOrderId(5L)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment result = paymentService.refundPayment(5L);

        assertEquals(PaymentStatus.REFUND_PENDING, result.getPaymentStatus());
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
