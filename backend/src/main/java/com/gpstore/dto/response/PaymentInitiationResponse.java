package com.gpstore.dto.response;

import com.gpstore.entity.Payment;

import java.math.BigDecimal;

/**
 * Mirrors the frontend's PaymentInitiationResult/PaymentDetails models
 * exactly: a flat "payment" object (id, paymentMethod, paymentStatus,
 * amount) plus upiPaymentLink. This used to nest the raw Payment entity,
 * which drags along the full Order -> orderItems -> each item's
 * productVariant. Under open-in-view, serializing that meant one extra
 * lazy-load query per order item on the checkout critical path - real,
 * avoidable latency on top of the two sequential API calls checkout
 * already makes. The client already has orderId/orderNumber from
 * placeOrder()'s response, so nothing here needs the order at all.
 */
public class PaymentInitiationResponse {

    private final PaymentSummary payment;
    // Only set for UPI payments - null for COD (nothing to scan/click for COD).
    private final String upiPaymentLink;

    public PaymentInitiationResponse(Payment payment, String upiPaymentLink) {
        this.payment = new PaymentSummary(payment);
        this.upiPaymentLink = upiPaymentLink;
    }

    public PaymentSummary getPayment() { return payment; }
    public String getUpiPaymentLink() { return upiPaymentLink; }

    public static class PaymentSummary {
        private final Long id;
        private final String paymentMethod;
        private final String paymentStatus;
        private final BigDecimal amount;

        PaymentSummary(Payment payment) {
            this.id = payment.getId();
            this.paymentMethod = payment.getPaymentMethod() != null ? payment.getPaymentMethod().name() : null;
            this.paymentStatus = payment.getPaymentStatus() != null ? payment.getPaymentStatus().name() : null;
            this.amount = payment.getAmount();
        }

        public Long getId() { return id; }
        public String getPaymentMethod() { return paymentMethod; }
        public String getPaymentStatus() { return paymentStatus; }
        public BigDecimal getAmount() { return amount; }
    }
}
