package com.gpstore.payment;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Order;
import com.gpstore.entity.Payment;
import com.gpstore.entity.PaymentProviderEvent;
import com.gpstore.payment.gateway.PaymentGateway;
import com.gpstore.enums.OrderStatus;
import com.gpstore.enums.PaymentMethod;
import com.gpstore.enums.PaymentProvider;
import com.gpstore.enums.PaymentStatus;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.PaymentRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Deadlock avoidance for the gateway paths: ORDER row first, PAYMENT row
 * second, everywhere, without exception.
 *
 * The sibling of InventoryLockOrderingTest, and it exists for the same
 * reason: the rule is invisible at the call site, so nothing stops the next
 * person writing the two lines in the convenient order rather than the safe
 * one. That is exactly how the bug this test pins got written.
 *
 * WHAT WENT WRONG. applyWebhook looked its payment up directly - the
 * obvious thing to do when a webhook hands you a provider order id and
 * nothing else - and only reached the order later, via
 * advanceOrderIfStillPending. So the webhook took PAYMENT then ORDER while
 * prepareCheckout, reconcile and PaymentService.lockOrderThenPayment all
 * take ORDER then PAYMENT. Two transactions, two locks, opposite orders:
 *
 *   webhook for order 42 arrives   -> holds payment(42), wants order(42)
 *   admin refunds order 42         -> holds order(42),   wants payment(42)
 *
 * Postgres breaks the cycle by killing one of them.
 *
 * The webhook half would have healed itself, which is what makes this the
 * kind of bug that survives review: a killed webhook is a 500, Cashfree
 * redelivers, and the dedup makes redelivery safe, so it would have read as
 * log noise. The customer-facing half would NOT have healed - /verify shares
 * applyVerdict, and a deadlock there is a failed request while somebody
 * watches their payment screen.
 *
 * The fix is that the order id is derivable from the provider order id we
 * minted (GP-<orderId>-<random>), so the order can be locked FIRST without
 * reading the payment at all.
 *
 * TWO THINGS IN THIS FIXTURE ARE LOAD-BEARING, and both were wrong in my
 * first draft in a way that made the test pass its own premise while
 * asserting nothing:
 *
 * 1. THE ORDER AND PAYMENT MUST REALLY EXIST. A webhook naming an order
 *    that is not in the database is correctly short-circuited the moment
 *    the order lock comes back empty - there is nothing to apply a verdict
 *    to - so the payment row is never reached and an inOrder() assertion
 *    about the pair can only fail. Asserting a SEQUENCE requires both
 *    locks to actually be taken, which requires real rows.
 *
 * 2. A REAL SIGNATURE IS COMPUTED. Verification runs before any lookup, so
 *    a test posting an unsigned body is rejected at the door and takes no
 *    locks at all - again asserting nothing, but silently.
 */
@SpringBootTest(properties = {
        // Test-only value. Not a credential, not used anywhere else, and the
        // real one never appears in this repository.
        "cashfree.webhook-secret=lock-ordering-test-secret",
        // The background workers are irrelevant here and only add noise.
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "payment.expiry-interval-ms=3600000"
})
class GatewayLockOrderingTest {

    private static final String SECRET = "lock-ordering-test-secret";
    private static final BigDecimal AMOUNT = new BigDecimal("10.00");

    @Autowired private GatewayPaymentService service;
    @Autowired private CustomerRepository customerRepository;

    // Spies, not mocks: the real locking queries must still run. These only
    // record which row was asked for, and in what sequence.
    @MockitoSpyBean private OrderRepository orderRepository;
    @MockitoSpyBean private PaymentRepository paymentRepository;

    // A spy so the sandbox gateway stays real for every other test in this
    // class, and only the one call below is answered by the test.
    @MockitoSpyBean private PaymentGateway gateway;

    /**
     * The regression this file was written for.
     *
     * inOrder() is the assertion that matters: both calls happening is not
     * the property under test, the sequence is.
     */
    @Test
    @DisplayName("a webhook locks the order row before the payment row, never after")
    void webhookLocksOrderBeforePayment() {
        Order order = persistedOrder();
        String providerOrderId = "GP-" + order.getId() + "-lockcheck";
        persistedPayment(order, providerOrderId);

        String rawBody = "{\"type\":\"PAYMENT_SUCCESS_WEBHOOK\","
                + "\"data\":{\"order\":{\"order_id\":\"" + providerOrderId + "\"},"
                + "\"payment\":{\"cf_payment_id\":\"cf_lock_test_" + order.getId() + "\","
                + "\"payment_status\":\"SUCCESS\",\"payment_amount\":10.00,"
                + "\"payment_currency\":\"INR\"}}}";

        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        GatewayPaymentService.WebhookResult result =
                service.applyWebhook(rawBody, sign(timestamp + rawBody), timestamp);

        // Not the property under test, but if this is not APPLIED the
        // webhook stopped somewhere early and the assertion below would be
        // measuring the wrong run.
        assertEquals(PaymentProviderEvent.Outcome.APPLIED, result.outcome(),
                "The webhook must have been applied for the lock sequence below to mean anything");

        InOrder locks = inOrder(orderRepository, paymentRepository);
        locks.verify(orderRepository).findByIdForUpdate(order.getId());
        locks.verify(paymentRepository).findByProviderOrderIdForUpdate(providerOrderId);
    }

    /**
     * The same rule from the customer-facing side.
     *
     * THIS TEST MOVED WHEN reconcile WAS SPLIT, and the reason is worth
     * writing down. reconcile used to be one transaction that locked the
     * order, locked the payment, and then made a ten-second call to Cashfree
     * while holding both. It is now three parts - a short read to authorise
     * the caller, the network call with nothing held, then a short write
     * transaction - so the locks live in applyReconciledVerdict and that is
     * where the ordering rule now has to be checked.
     *
     * The mechanism is the same as before: the order lookup finds nothing
     * and throws, which is itself the proof that the payment row was never
     * reached and therefore cannot have been locked first.
     */
    @Test
    @DisplayName("applying a gateway verdict locks the order row first and never the payment without it")
    void reconcileLocksOrderFirst() {
        try {
            service.applyReconciledVerdict(999_999_999L, null);
        } catch (RuntimeException expected) {
            // Order not found. It was still looked up - and locked - first.
        }

        verify(orderRepository).findByIdForUpdate(999_999_999L);
        verify(paymentRepository, never()).findByOrderIdForUpdate(anyLong());
    }

    /**
     * The reason the split was made, asserted rather than described.
     *
     * A transaction open across an outbound HTTPS call holds a pooled
     * database connection for the whole of it. The pool is ten wide; the app
     * calls this on every return from checkout. Ten customers coming back
     * during a Cashfree slowdown was the whole shop down - browse and search
     * included, because there were no connections left for anything.
     *
     * isActualTransactionActive() is checked INSIDE the gateway call, which
     * is the only place the question means anything: it is asking what was
     * true at the moment the ten seconds would have been spent.
     */
    @Test
    @DisplayName("the gateway is called with no transaction and no row lock held")
    void reconcileDoesNotCallTheGatewayInsideATransaction() {
        Order order = persistedOrder();
        String providerOrderId = "GP-" + order.getId() + "-nolock";
        persistedPayment(order, providerOrderId);

        java.util.concurrent.atomic.AtomicBoolean sawTransaction =
                new java.util.concurrent.atomic.AtomicBoolean(true);

        org.mockito.Mockito.doAnswer(invocation -> {
            sawTransaction.set(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            // ACTIVE, so applyVerdict does nothing and this test measures the
            // transaction boundary rather than a state change.
            return new PaymentGateway.GatewayOrderStatus(
                    providerOrderId, null,
                    PaymentGateway.GatewayOrderStatus.State.ACTIVE,
                    AMOUNT, "INR", null);
        }).when(gateway).fetchOrderStatus(providerOrderId);

        service.reconcile(order.getId(), order.getCustomer().getId());

        org.junit.jupiter.api.Assertions.assertFalse(sawTransaction.get(),
                "The gateway was called with a transaction open. That holds a Hikari connection - one "
                        + "of ten - for the length of a third party's response, which is how a slow "
                        + "Cashfree takes the entire application down rather than just payment.");
    }

    private Order persistedOrder() {
        Customer customer = new Customer();
        customer.setFullName("Gateway Lock Order Customer");
        customer.setEmail("gateway-lock-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber("9" + String.valueOf(System.nanoTime()).substring(0, 9));
        customer.setPassword("irrelevant-for-this-test");
        customer.setEnabled(true);
        customer.setActive(true);
        customer = customerRepository.save(customer);

        Order order = new Order();
        order.setOrderNumber("GWLOCK-" + System.nanoTime());
        order.setCustomer(customer);
        order.setTotalAmount(AMOUNT);
        // CONFIRMED, not PENDING_CONFIRMATION: advanceOrderIfStillPending is
        // then a no-op, so this test exercises the locking and nothing else.
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
