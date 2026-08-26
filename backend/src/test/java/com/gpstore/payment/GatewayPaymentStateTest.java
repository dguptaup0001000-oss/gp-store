package com.gpstore.payment;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Order;
import com.gpstore.entity.Payment;
import com.gpstore.enums.OrderStatus;
import com.gpstore.enums.PaymentMethod;
import com.gpstore.enums.PaymentProvider;
import com.gpstore.enums.PaymentStatus;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "cashfree.webhook-secret=webhook-state-test-secret",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "payment.expiry-interval-ms=3600000"
})
class GatewayPaymentStateTest {

    private static final String SECRET = "webhook-state-test-secret";
    private static final BigDecimal AMOUNT = new BigDecimal("10.00");

    @Autowired private GatewayPaymentService service;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;

    @Test
    @DisplayName("an underpayment webhook fails the payment and leaves the order pending")
    void underpaymentDoesNotConfirmTheOrder() {
        Order order = persistedOrder(OrderStatus.PENDING_CONFIRMATION);
        String providerOrderId = "GP-" + order.getId() + "-under";
        persistedPayment(order, providerOrderId);

        apply("PAYMENT_FAILED_WEBHOOK", providerOrderId, "SUCCESS", 1.00, "cf_under_" + order.getId());

        Payment payment = paymentRepository.findByProviderOrderId(providerOrderId).orElseThrow();
        assertEquals(PaymentStatus.FAILED, payment.getPaymentStatus());
        assertEquals(OrderStatus.PENDING_CONFIRMATION,
                orderRepository.findById(order.getId()).orElseThrow().getOrderStatus());
    }

    @Test
    @DisplayName("EXPIRED does not cancel the order")
    void expiredWebhookDoesNotCancelTheOrder() {
        Order order = persistedOrder(OrderStatus.PENDING_CONFIRMATION);
        String providerOrderId = "GP-" + order.getId() + "-exp";
        persistedPayment(order, providerOrderId);

        apply("PAYMENT_FAILED_WEBHOOK", providerOrderId, "EXPIRED", 10.00, "cf_exp_" + order.getId());

        Payment payment = paymentRepository.findByProviderOrderId(providerOrderId).orElseThrow();
        assertEquals(PaymentStatus.EXPIRED, payment.getPaymentStatus());
        assertEquals(OrderStatus.PENDING_CONFIRMATION,
                orderRepository.findById(order.getId()).orElseThrow().getOrderStatus());
    }

    @Test
    @DisplayName("TERMINATED cancels the payment attempt, not the order")
    void terminatedWebhookDoesNotCancelTheOrder() {
        Order order = persistedOrder(OrderStatus.PENDING_CONFIRMATION);
        String providerOrderId = "GP-" + order.getId() + "-term";
        persistedPayment(order, providerOrderId);

        apply("PAYMENT_FAILED_WEBHOOK", providerOrderId, "TERMINATED", 10.00, "cf_term_" + order.getId());

        Payment payment = paymentRepository.findByProviderOrderId(providerOrderId).orElseThrow();
        assertEquals(PaymentStatus.CANCELLED, payment.getPaymentStatus());
        assertEquals(OrderStatus.PENDING_CONFIRMATION,
                orderRepository.findById(order.getId()).orElseThrow().getOrderStatus());
    }

    @Test
    @DisplayName("PENDING leaves payment and order untouched")
    void pendingWebhookChangesNothing() {
        Order order = persistedOrder(OrderStatus.PENDING_CONFIRMATION);
        String providerOrderId = "GP-" + order.getId() + "-pend";
        persistedPayment(order, providerOrderId);

        apply("PAYMENT_PENDING_WEBHOOK", providerOrderId, "PENDING", 10.00, "cf_pend_" + order.getId());

        Payment payment = paymentRepository.findByProviderOrderId(providerOrderId).orElseThrow();
        assertEquals(PaymentStatus.PENDING, payment.getPaymentStatus());
        assertEquals(OrderStatus.PENDING_CONFIRMATION,
                orderRepository.findById(order.getId()).orElseThrow().getOrderStatus());
    }

    private void apply(String type, String providerOrderId, String paymentStatus, double amount, String cfPaymentId) {
        String rawBody = "{\"type\":\"" + type + "\","
                + "\"data\":{\"order\":{\"order_id\":\"" + providerOrderId + "\"},"
                + "\"payment\":{\"cf_payment_id\":\"" + cfPaymentId + "\","
                + "\"payment_status\":\"" + paymentStatus + "\",\"payment_amount\":" + amount + ","
                + "\"payment_currency\":\"INR\"}}}";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        service.applyWebhook(rawBody, sign(timestamp + rawBody), timestamp);
    }

    private Order persistedOrder(OrderStatus status) {
        Customer customer = new Customer();
        customer.setFullName("Gateway State Customer");
        customer.setEmail("gp-state-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber("7" + String.valueOf(System.nanoTime()).substring(0, 9));
        customer.setPassword("irrelevant-for-this-test");
        customer.setEnabled(true);
        customer.setActive(true);
        customer = customerRepository.save(customer);

        Order order = new Order();
        order.setOrderNumber("GPSTATE-" + System.nanoTime());
        order.setCustomer(customer);
        order.setTotalAmount(AMOUNT);
        order.setOrderStatus(status);
        order.setOrderDate(LocalDateTime.now());
        order.setActive(true);
        return orderRepository.save(order);
    }

    private void persistedPayment(Order order, String providerOrderId) {
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.ONLINE);
        payment.setPaymentStatus(PaymentStatus.PENDING);
        payment.setProvider(PaymentProvider.CASHFREE);
        payment.setProviderOrderId(providerOrderId);
        payment.setAmount(AMOUNT);
        payment.setActive(true);
        paymentRepository.save(payment);
    }

    private static String sign(String signedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
