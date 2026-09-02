package com.gpstore.payment.gateway;

import com.gpstore.enums.PaymentProvider;

import java.math.BigDecimal;

/**
 * What this application needs from a payment gateway, and nothing more.
 *
 * WHY AN INTERFACE FOR ONE IMPLEMENTATION. Not speculative generality - it
 * draws the line that keeps provider concepts out of the order system.
 * PaymentService talks in orders, amounts and states; only the class behind
 * this interface knows what a payment session is, which header carries the
 * API version, or that Cashfree calls its identifier order_id. Adding a
 * second provider later means writing one class, not unpicking gateway
 * vocabulary from checkout.
 *
 * It is also what makes the payment logic testable at all. Every duplicate,
 * mismatch and race test in this codebase runs against a stub of this
 * interface - the alternative is a test suite that needs a live sandbox and
 * therefore never runs in CI.
 */
public interface PaymentGateway {

    PaymentProvider provider();

    /**
     * Registers an order with the gateway and returns what the client needs
     * to open its checkout.
     *
     * [amount] is the backend's authoritative figure. This method never
     * takes an amount from a request body, and there is no overload that
     * does - the client cannot influence what gets charged, by signature
     * rather than by discipline.
     */
    GatewaySession createSession(GatewaySessionRequest request);

    /**
     * Asks the gateway what it thinks the state of an order is.
     *
     * The recovery path, and the reason a lost webhook is not a lost
     * payment. Called when the client returns from checkout and when an
     * order is opened later - the answer comes from the provider's servers,
     * never from the client.
     */
    GatewayOrderStatus fetchOrderStatus(String providerOrderId);

    /**
     * Asks the gateway to send money back.
     *
     * IDEMPOTENT BY THE REFUND ID, which is this application's own and is
     * derived from the payment rather than generated fresh. A retry after a
     * timeout therefore reaches the SAME refund at the provider instead of
     * creating a second one - which is the only defence that matters here,
     * because the failure mode is refunding a customer twice with the shop's
     * money.
     */
    GatewayRefund requestRefund(GatewayRefundRequest request);

    /**
     * What the gateway now says about a refund we asked for.
     *
     * A refund is not instant - it settles through the customer's bank over
     * days - so the state at the end of requestRefund is PENDING, and this is
     * how "did it actually land" gets answered without waiting on a webhook.
     */
    GatewayRefund fetchRefund(String providerOrderId, String refundId);

    record GatewayRefundRequest(
            String providerOrderId,
            /** This application's id for the refund. Doubles as the idempotency key. */
            String refundId,
            BigDecimal amount,
            String note) {}

    /** The provider's view of one refund, normalised. */
    record GatewayRefund(
            String refundId,
            String providerRefundId,
            State state,
            BigDecimal amount,
            String failureReason) {

        /**
         * PENDING is the normal answer immediately after asking, and is
         * deliberately not success - marking a payment REFUNDED here would
         * tell the shop the money is back before the bank has moved it.
         */
        public enum State { PENDING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN }
    }

    /** The session the client is given. Deliberately carries no credential. */
    record GatewaySession(String providerOrderId, String paymentSessionId) {}

    record GatewaySessionRequest(
            String providerOrderId,
            BigDecimal amount,
            String currency,
            String customerId,
            String customerPhone,
            String customerName,
            String customerEmail,
            String returnUrl,
            String notifyUrl) {}

    /** The provider's own verdict, normalised. */
    record GatewayOrderStatus(
            String providerOrderId,
            String providerPaymentId,
            State state,
            BigDecimal amount,
            String currency,
            String failureReason) {

        public enum State { PAID, ACTIVE, EXPIRED, FAILED, CANCELLED, UNKNOWN }
    }
}
