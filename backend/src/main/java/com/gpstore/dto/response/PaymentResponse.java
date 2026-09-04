package com.gpstore.dto.response;

import com.gpstore.entity.Payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The raw Payment entity's nested Order field has no JSON protection at
 * all - not a crash risk (Order's own fields already terminate safely
 * elsewhere), but it would drag the full order and every item along with
 * every single payment record, which is wasteful for an admin list that
 * just needs to know which order and customer a payment belongs to.
 */
public class PaymentResponse {

    private final Long id;
    private final Long orderId;
    private final String orderNumber;
    private final String customerName;
    private final BigDecimal amount;
    private final String paymentMethod;
    private final String paymentStatus;
    private final String transactionId;
    private final LocalDateTime paymentDate;
    private final String refundChannel;
    private final BigDecimal refundAmount;
    private final LocalDateTime refundedAt;
    private final String refundFailureReason;
    // How a cash-on-delivery order was actually settled. Null means the split
    // was never recorded (settled automatically, or before this existed) -
    // which a screen must show as "not recorded", never as zero.
    private final java.math.BigDecimal codCashAmount;
    private final java.math.BigDecimal codUpiAmount;

    public PaymentResponse(Long id, Long orderId, String orderNumber, String customerName, BigDecimal amount,
                            String paymentMethod, String paymentStatus, String transactionId,
                            LocalDateTime paymentDate,
                            String refundChannel, BigDecimal refundAmount,
                            LocalDateTime refundedAt, String refundFailureReason) {
        this.id = id;
        this.orderId = orderId;
        this.orderNumber = orderNumber;
        this.customerName = customerName;
        this.amount = amount;
        this.paymentMethod = paymentMethod;
        this.paymentStatus = paymentStatus;
        this.transactionId = transactionId;
        this.paymentDate = paymentDate;
        this.refundChannel = refundChannel;
        this.refundAmount = refundAmount;
        this.refundedAt = refundedAt;
        this.refundFailureReason = refundFailureReason;
        this.codCashAmount = null;
        this.codUpiAmount = null;
    }

    /**
     * Kept as a separate constructor rather than extra parameters on the one
     * above: that signature has other callers, and widening it would make
     * every one of them pass two nulls to say nothing.
     */
    public PaymentResponse(Long id, Long orderId, String orderNumber, String customerName, BigDecimal amount,
                            String paymentMethod, String paymentStatus, String transactionId,
                            LocalDateTime paymentDate,
                            String refundChannel, BigDecimal refundAmount,
                            LocalDateTime refundedAt, String refundFailureReason,
                            java.math.BigDecimal codCashAmount, java.math.BigDecimal codUpiAmount) {
        this.id = id;
        this.orderId = orderId;
        this.orderNumber = orderNumber;
        this.customerName = customerName;
        this.amount = amount;
        this.paymentMethod = paymentMethod;
        this.paymentStatus = paymentStatus;
        this.transactionId = transactionId;
        this.paymentDate = paymentDate;
        this.refundChannel = refundChannel;
        this.refundAmount = refundAmount;
        this.refundedAt = refundedAt;
        this.refundFailureReason = refundFailureReason;
        this.codCashAmount = codCashAmount;
        this.codUpiAmount = codUpiAmount;
    }

    public static PaymentResponse from(Payment payment) {
        var order = payment.getOrder();
        var customer = order != null ? order.getCustomer() : null;

        return new PaymentResponse(
                payment.getId(),
                order != null ? order.getId() : null,
                order != null ? order.getOrderNumber() : null,
                customer != null ? customer.getFullName() : null,
                payment.getAmount(),
                payment.getPaymentMethod() != null ? payment.getPaymentMethod().name() : null,
                payment.getPaymentStatus() != null ? payment.getPaymentStatus().name() : null,
                payment.getTransactionId(),
                payment.getPaymentDate(),
                payment.getRefundChannel() != null ? payment.getRefundChannel().name() : null,
                payment.getRefundAmount(),
                payment.getRefundedAt(),
                payment.getRefundFailureReason(),
                payment.getCodCashAmount(),
                payment.getCodUpiAmount()
        );
    }

    public java.math.BigDecimal getCodCashAmount() { return codCashAmount; }
    public java.math.BigDecimal getCodUpiAmount() { return codUpiAmount; }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public String getOrderNumber() { return orderNumber; }
    public String getCustomerName() { return customerName; }
    public BigDecimal getAmount() { return amount; }
    public String getPaymentMethod() { return paymentMethod; }
    public String getPaymentStatus() { return paymentStatus; }
    public String getTransactionId() { return transactionId; }
    public LocalDateTime getPaymentDate() { return paymentDate; }

    // WHY THE REFUND FACTS ARE ON THE WIRE. A refund that the provider
    // refused leaves the payment REFUND_PENDING, which on its own is
    // indistinguishable from one still travelling. The shop needs the reason
    // to act, and refundedAt is the difference between "we asked" and "they
    // have it".
    public String getRefundChannel() { return refundChannel; }
    public BigDecimal getRefundAmount() { return refundAmount; }
    public LocalDateTime getRefundedAt() { return refundedAt; }
    public String getRefundFailureReason() { return refundFailureReason; }
}
