package com.gpstore.payment;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Order;
import com.gpstore.entity.Payment;
import com.gpstore.entity.PaymentProviderEvent;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
        "cashfree.webhook-secret=webhook-dedup-test-secret",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "payment.expiry-interval-ms=3600000"
})
class CashfreeWebhookDedupTest {

    private static final String SECRET = "webhook-dedup-test-secret";
    private static final BigDecimal AMOUNT = new BigDecimal("10.00");

    @Autowired private GatewayPaymentService service;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;

    @Test
    @DisplayName("a second delivery of the same Cashfree event does not apply twice")
    void duplicateWebhookIsRejectedWithoutASecondStateChange() {
        Order order = persistedOrder();
        String providerOrderId = "GP-" + order.getId() + "-dedup";
        persistedPayment(order, providerOrderId);

        String rawBody = "{\"type\":\"PAYMENT_SUCCESS_WEBHOOK\","
                + "\"data\":{\"order\":{\"order_id\":\"" + providerOrderId + "\"},"
                + "\"payment\":{\"cf_payment_id\":\"cf_dedup_" + order.getId() + "\","
                + "\"payment_status\":\"SUCCESS\",\"payment_amount\":10.00,"
                + "\"payment_currency\":\"INR\"}}}";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = sign(timestamp + rawBody);

        GatewayPaymentService.WebhookResult first = service.applyWebhook(rawBody, signature, timestamp);
        assertEquals(PaymentProviderEvent.Outcome.APPLIED, first.outcome());

        assertThrows(GatewayPaymentService.DuplicateEventException.class,
                () -> service.applyWebhook(rawBody, signature, timestamp));

        Payment payment = paymentRepository.findByProviderOrderId(providerOrderId).orElseThrow();
        assertEquals(PaymentStatus.SUCCESS, payment.getPaymentStatus(),
                "rollback of the duplicate must not un-apply the first delivery");
    }

    private Order persistedOrder() {
        Customer customer = new Customer();
        customer.setFullName("Webhook Dedup Customer");
        customer.setEmail("webhook-dedup-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber("7" + String.valueOf(System.nanoTime()).substring(0, 9));
        customer.setPassword("irrelevant-for-this-test");
        customer.setEnabled(true);
        customer.setActive(true);
        customer = customerRepository.save(customer);

        Order order = new Order();
        order.setOrderNumber("WHDEDUP-" + System.nanoTime());
        order.setCustomer(customer);
        order.setTotalAmount(AMOUNT);
        order.setOrderStatus(OrderStatus.CONFIRMED);
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
