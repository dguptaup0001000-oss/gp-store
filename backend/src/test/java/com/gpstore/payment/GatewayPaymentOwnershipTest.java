package com.gpstore.payment;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Order;
import com.gpstore.entity.Payment;
import com.gpstore.enums.OrderStatus;
import com.gpstore.enums.PaymentMethod;
import com.gpstore.enums.PaymentStatus;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class GatewayPaymentOwnershipTest {

    @Autowired private GatewayPaymentService gatewayPaymentService;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;

    @Test
    @DisplayName("another customer cannot start or verify a checkout - same 404 as a missing order")
    void nonOwnerSeesTheSameNotFoundAsAMissingOrder() {
        Order order = persistedOrder(PaymentMethod.ONLINE, PaymentStatus.PENDING);
        Customer stranger = newCustomer("stranger");

        ResourceNotFoundException missing = assertThrows(ResourceNotFoundException.class,
                () -> gatewayPaymentService.startCheckout(8_888_888_888L, order.getCustomer().getId()));
        ResourceNotFoundException foreignCheckout = assertThrows(ResourceNotFoundException.class,
                () -> gatewayPaymentService.startCheckout(order.getId(), stranger.getId()));
        ResourceNotFoundException foreignVerify = assertThrows(ResourceNotFoundException.class,
                () -> gatewayPaymentService.reconcile(order.getId(), stranger.getId()));

        assertEquals("Order not found", missing.getMessage());
        assertEquals(missing.getMessage(), foreignCheckout.getMessage());
        assertEquals(missing.getMessage(), foreignVerify.getMessage());
    }

    @Test
    @DisplayName("a COD order cannot open a Cashfree session")
    void codOrderCannotStartGatewayCheckout() {
        Order order = persistedOrder(PaymentMethod.COD, PaymentStatus.COD_PENDING);

        assertThrows(ConflictException.class,
                () -> gatewayPaymentService.startCheckout(order.getId(), order.getCustomer().getId()));

        Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertEquals(PaymentStatus.COD_PENDING, payment.getPaymentStatus(),
                "refusing checkout must not rewrite a COD payment into PENDING");
        assertEquals(PaymentMethod.COD, payment.getPaymentMethod());
    }

    private Order persistedOrder(PaymentMethod method, PaymentStatus status) {
        Customer customer = newCustomer("owner");

        Order order = new Order();
        order.setOrderNumber("GPOWN-" + System.nanoTime());
        order.setCustomer(customer);
        order.setTotalAmount(new BigDecimal("10.00"));
        order.setOrderStatus(OrderStatus.PENDING_CONFIRMATION);
        order.setOrderDate(LocalDateTime.now());
        order.setActive(true);
        order = orderRepository.save(order);

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(method);
        payment.setPaymentStatus(status);
        payment.setAmount(order.getTotalAmount());
        payment.setActive(true);
        paymentRepository.save(payment);
        return order;
    }

    private Customer newCustomer(String label) {
        Customer customer = new Customer();
        customer.setFullName("Gateway Owner " + label);
        customer.setEmail("gp-own-" + label + "-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber("7" + String.valueOf(System.nanoTime()).substring(0, 9));
        customer.setPassword("irrelevant-for-this-test");
        customer.setEnabled(true);
        customer.setActive(true);
        return customerRepository.save(customer);
    }
}
