package com.gpstore.payment;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Order;
import com.gpstore.entity.Payment;
import com.gpstore.enums.OrderStatus;
import com.gpstore.enums.PaymentMethod;
import com.gpstore.enums.PaymentProvider;
import com.gpstore.enums.PaymentStatus;
import com.gpstore.exception.ConflictException;
import com.gpstore.payment.gateway.PaymentGateway;
import com.gpstore.payment.gateway.PaymentGateway.GatewayRefund;
import com.gpstore.payment.gateway.PaymentGateway.GatewayRefundRequest;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.PaymentRepository;
import com.gpstore.service.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * A refund has to move money, not just a row.
 *
 * WHAT THIS FILE EXISTS TO STOP COMING BACK. refundPayment used to set
 * REFUND_PENDING, completeRefund used to set REFUNDED, and no request ever
 * left the building. The shop's screen said REFUNDED, the audit log said
 * REFUNDED, and a prepaid customer's money was still sitting at Cashfree
 * until somebody remembered to do it by hand. The record and the money
 * disagreed, and the customer was the one who found out.
 *
 * So the assertions here are about the provider call and the timestamp, not
 * about the status alone: REFUNDED without a confirmed provider refund is the
 * exact bug, and a test that only checked the status would have passed
 * happily against the broken version.
 */
@SpringBootTest(properties = {
        // Test-only value. Not a credential, and the real one is never here.
        "cashfree.webhook-secret=refund-test-secret",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "payment.expiry-interval-ms=3600000"
})
@DisplayName("A refund reaches the provider before it is called done")
class RefundReachesTheProviderTest {

    private static final String SECRET = "refund-test-secret";
    private static final BigDecimal AMOUNT = new BigDecimal("249.50");

    @Autowired private PaymentService paymentService;
    @Autowired private GatewayPaymentService gatewayPaymentService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private CustomerRepository customerRepository;

    /** A spy, so every other test in the suite keeps the real gateway. */
    @MockitoSpyBean private PaymentGateway gateway;

    // ------------------------------------------------------------ the fix

    @Test
    @DisplayName("a prepaid refund is actually sent to the provider")
    void prepaidRefundCallsTheGateway() {
        Order order = persistedOrder();
        Payment payment = onlinePayment(order, PaymentStatus.SUCCESS);

        doReturn(new GatewayRefund("gpsr-" + payment.getId(), "cf_ref_1",
                GatewayRefund.State.PENDING, AMOUNT, null))
                .when(gateway).requestRefund(any());

        paymentService.refundPayment(order.getId());

        ArgumentCaptor<GatewayRefundRequest> sent = ArgumentCaptor.forClass(GatewayRefundRequest.class);
        verify(gateway).requestRefund(sent.capture());

        // THE ASSERTION THE OLD CODE COULD NOT PASS: something left the
        // building, and it carried the shop's figure rather than anyone's
        // guess.
        assertEquals(payment.getProviderOrderId(), sent.getValue().providerOrderId());
        assertEquals(0, AMOUNT.compareTo(sent.getValue().amount()),
                "The refund must be for the amount actually taken.");

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.REFUND_PENDING, after.getPaymentStatus());
        assertEquals(Payment.RefundChannel.GATEWAY, after.getRefundChannel());
        assertNull(after.getRefundedAt(),
                "PENDING at the provider is not money in the customer's account, so "
                        + "nothing may claim it landed yet.");
    }

    @Test
    @DisplayName("a refund that times out and is retried cannot send the money twice")
    void retryReusesTheSameRefundId() {
        Order order = persistedOrder();
        Payment payment = onlinePayment(order, PaymentStatus.SUCCESS);

        // First attempt: the provider never answers.
        doThrow(new com.gpstore.exception.BadRequestException("provider timed out"))
                .when(gateway).requestRefund(any());
        assertThrows(RuntimeException.class, () -> paymentService.refundPayment(order.getId()));

        // NOTHING WAS LEFT BEHIND. A REFUND_PENDING row for a request the
        // provider never took would sit in the reconciliation report forever.
        Payment afterFailure = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.SUCCESS, afterFailure.getPaymentStatus(),
                "A refund the provider never accepted must leave the payment untouched.");
        assertNull(afterFailure.getRefundId());

        // Second attempt succeeds.
        reset(gateway);
        doReturn(new GatewayRefund("gpsr-" + payment.getId(), "cf_ref_2",
                GatewayRefund.State.PENDING, AMOUNT, null))
                .when(gateway).requestRefund(any());
        paymentService.refundPayment(order.getId());

        ArgumentCaptor<GatewayRefundRequest> sent = ArgumentCaptor.forClass(GatewayRefundRequest.class);
        verify(gateway).requestRefund(sent.capture());

        // THE IDEMPOTENCY KEY IS DERIVED FROM THE PAYMENT, so a retry - here,
        // or by a shopkeeper pressing the button again after a timeout -
        // carries an id the provider has already seen and is deduplicated
        // there. A random id would refund the customer twice with the shop's
        // money, and no test of the status alone would notice.
        assertEquals("gpsr-" + payment.getId(), sent.getValue().refundId());
        assertEquals(PaymentService.refundIdFor(payment), sent.getValue().refundId());
    }

    @Test
    @DisplayName("asking twice does not send a second refund")
    void secondRequestIsRefused() {
        Order order = persistedOrder();
        Payment payment = onlinePayment(order, PaymentStatus.SUCCESS);
        doReturn(new GatewayRefund("gpsr-" + payment.getId(), "cf_ref_3",
                GatewayRefund.State.PENDING, AMOUNT, null))
                .when(gateway).requestRefund(any());

        paymentService.refundPayment(order.getId());
        assertThrows(ConflictException.class, () -> paymentService.refundPayment(order.getId()));

        verify(gateway, times(1)).requestRefund(any());
    }

    // ------------------------------------------- what "completed" now means

    @Test
    @DisplayName("a shopkeeper cannot mark a gateway refund done while the provider still says pending")
    void completingIsNotAllowedWhileTheProviderIsPending() {
        Order order = persistedOrder();
        Payment payment = onlinePayment(order, PaymentStatus.SUCCESS);
        doReturn(new GatewayRefund("gpsr-" + payment.getId(), "cf_ref_4",
                GatewayRefund.State.PENDING, AMOUNT, null))
                .when(gateway).requestRefund(any());
        paymentService.refundPayment(order.getId());

        doReturn(new GatewayRefund("gpsr-" + payment.getId(), "cf_ref_4",
                GatewayRefund.State.PENDING, AMOUNT, null))
                .when(gateway).fetchRefund(anyString(), anyString());

        // Somebody clicking a button is not a bank transfer. This is the whole
        // point of the change: the record may not run ahead of the money.
        assertThrows(ConflictException.class, () -> paymentService.completeRefund(order.getId()));

        assertEquals(PaymentStatus.REFUND_PENDING,
                paymentRepository.findById(payment.getId()).orElseThrow().getPaymentStatus());
    }

    @Test
    @DisplayName("the provider confirming is what marks it refunded")
    void providerConfirmationSettlesIt() {
        Order order = persistedOrder();
        Payment payment = onlinePayment(order, PaymentStatus.SUCCESS);
        doReturn(new GatewayRefund("gpsr-" + payment.getId(), "cf_ref_5",
                GatewayRefund.State.PENDING, AMOUNT, null))
                .when(gateway).requestRefund(any());
        paymentService.refundPayment(order.getId());

        doReturn(new GatewayRefund("gpsr-" + payment.getId(), "cf_ref_5",
                GatewayRefund.State.SUCCEEDED, AMOUNT, null))
                .when(gateway).fetchRefund(anyString(), anyString());

        paymentService.completeRefund(order.getId());

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.REFUNDED, after.getPaymentStatus());
        assertNotNull(after.getRefundedAt(),
                "REFUNDED without a timestamp is the state the old code could reach "
                        + "with no money having moved.");
        assertEquals(0, AMOUNT.compareTo(after.getRefundAmount()));
    }

    @Test
    @DisplayName("a refund the provider rejected is reported, not silently marked done")
    void providerFailureIsSurfaced() {
        Order order = persistedOrder();
        Payment payment = onlinePayment(order, PaymentStatus.SUCCESS);
        doReturn(new GatewayRefund("gpsr-" + payment.getId(), "cf_ref_6",
                GatewayRefund.State.PENDING, AMOUNT, null))
                .when(gateway).requestRefund(any());
        paymentService.refundPayment(order.getId());

        doReturn(new GatewayRefund("gpsr-" + payment.getId(), "cf_ref_6",
                GatewayRefund.State.FAILED, AMOUNT, "Bank rejected the transfer"))
                .when(gateway).fetchRefund(anyString(), anyString());

        // RECORDED AND RETURNED, NOT THROWN, and that is deliberate: throwing
        // rolls back the very row that remembers why, so the shop would get an
        // error toast and nothing to look at tomorrow. The reason is part of
        // the answer instead.
        var response = paymentService.completeRefund(order.getId());
        assertEquals("Bank rejected the transfer", response.getRefundFailureReason(),
                "The shopkeeper needs the provider's own words to act on.");
        assertEquals(PaymentStatus.REFUND_PENDING.name(), response.getPaymentStatus(),
                "A refund the provider refused is still outstanding, not done.");

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertNotEquals(PaymentStatus.REFUNDED, after.getPaymentStatus());
        assertNull(after.getRefundedAt(),
                "Nothing may claim the money moved when the provider said it did not.");
        assertEquals("Bank rejected the transfer", after.getRefundFailureReason(),
                "and it must still be there after the transaction commits.");
    }

    // ------------------------------------------------------ cash is not the gateway

    @Test
    @DisplayName("a COD refund never touches the provider")
    void codRefundIsCash() {
        Order order = persistedOrder();
        Payment payment = codPayment(order);

        paymentService.refundPayment(order.getId());
        paymentService.completeRefund(order.getId());

        // Cash went back at the door. Asking Cashfree to refund an order it
        // has never heard of would fail, loudly, on a shopkeeper doing the
        // right thing.
        verify(gateway, never()).requestRefund(any());
        verify(gateway, never()).fetchRefund(anyString(), anyString());

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.REFUNDED, after.getPaymentStatus());
        assertEquals(Payment.RefundChannel.CASH, after.getRefundChannel());
        assertNotNull(after.getRefundedAt());
    }

    @Test
    @DisplayName("an unpaid COD order has nothing to refund")
    void unpaidCodIsRefused() {
        Order order = persistedOrder();
        Payment payment = codPayment(order);
        payment.setPaymentStatus(PaymentStatus.COD_PENDING);
        paymentRepository.save(payment);

        assertThrows(ConflictException.class, () -> paymentService.refundPayment(order.getId()));
        verify(gateway, never()).requestRefund(any());
    }

    // ------------------------------------------------------------- webhook

    @Test
    @DisplayName("a refund webhook settles a refund that landed days later")
    void refundWebhookSettlesIt() {
        Order order = persistedOrder();
        Payment payment = onlinePayment(order, PaymentStatus.SUCCESS);
        String refundId = "gpsr-" + payment.getId();
        doReturn(new GatewayRefund(refundId, "cf_ref_7", GatewayRefund.State.PENDING, AMOUNT, null))
                .when(gateway).requestRefund(any());
        paymentService.refundPayment(order.getId());

        // A REFUND WEBHOOK CARRIES data.refund, NOT data.payment. Before this
        // was handled it fell through the payment path, read a null status and
        // recorded UNKNOWN - so a refund that had actually landed stayed
        // REFUND_PENDING forever and nothing could tell it from a stuck one.
        String body = "{\"type\":\"REFUND_STATUS_WEBHOOK\",\"data\":{"
                + "\"order\":{\"order_id\":\"" + payment.getProviderOrderId() + "\"},"
                + "\"refund\":{\"refund_id\":\"" + refundId + "\",\"cf_refund_id\":\"cf_ref_7\","
                + "\"refund_status\":\"SUCCESS\",\"refund_amount\":249.50}}}";
        String ts = String.valueOf(Instant.now().getEpochSecond());

        gatewayPaymentService.applyWebhook(body, sign(ts + body), ts);

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.REFUNDED, after.getPaymentStatus());
        assertNotNull(after.getRefundedAt());
        assertEquals("cf_ref_7", after.getProviderRefundId());
    }

    @Test
    @DisplayName("a refund webhook for somebody else's refund id changes nothing")
    void mismatchedRefundWebhookIsIgnored() {
        Order order = persistedOrder();
        Payment payment = onlinePayment(order, PaymentStatus.SUCCESS);
        doReturn(new GatewayRefund("gpsr-" + payment.getId(), "cf_ref_8",
                GatewayRefund.State.PENDING, AMOUNT, null))
                .when(gateway).requestRefund(any());
        paymentService.refundPayment(order.getId());

        // A refund raised by hand in the provider's dashboard, or one for a
        // different order. Guessing which payment it belongs to would mark the
        // wrong order refunded.
        String body = "{\"type\":\"REFUND_STATUS_WEBHOOK\",\"data\":{"
                + "\"order\":{\"order_id\":\"" + payment.getProviderOrderId() + "\"},"
                + "\"refund\":{\"refund_id\":\"someone-elses-refund\","
                + "\"refund_status\":\"SUCCESS\",\"refund_amount\":249.50}}}";
        String ts = String.valueOf(Instant.now().getEpochSecond());

        gatewayPaymentService.applyWebhook(body, sign(ts + body), ts);

        assertEquals(PaymentStatus.REFUND_PENDING,
                paymentRepository.findById(payment.getId()).orElseThrow().getPaymentStatus());
    }

    // ------------------------------------------------------------ fixtures

    private Order persistedOrder() {
        Customer customer = new Customer();
        customer.setFullName("Refund Test Customer");
        customer.setEmail("refund-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber("9" + String.valueOf(System.nanoTime()).substring(0, 9));
        customer.setPassword("irrelevant-for-this-test");
        customer.setEnabled(true);
        customer.setActive(true);
        customer = customerRepository.save(customer);

        Order order = new Order();
        order.setOrderNumber("REFUND-" + System.nanoTime());
        order.setCustomer(customer);
        order.setTotalAmount(AMOUNT);
        order.setOrderStatus(OrderStatus.CONFIRMED);
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
        payment.setProviderOrderId("GP-" + order.getId() + "-refundtest");
        payment.setAmount(AMOUNT);
        payment.setCurrency("INR");
        payment.setActive(true);
        return paymentRepository.save(payment);
    }

    private Payment codPayment(Order order) {
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.COD);
        payment.setPaymentStatus(PaymentStatus.COD_RECEIVED);
        payment.setAmount(AMOUNT);
        payment.setActive(true);
        return paymentRepository.save(payment);
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
