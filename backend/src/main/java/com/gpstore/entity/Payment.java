package com.gpstore.entity;

import com.gpstore.enums.PaymentMethod;
import com.gpstore.enums.PaymentProvider;
import com.gpstore.enums.PaymentStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.*;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Deliberately left EAGER: PaymentController.getPaymentById()/
    // getPaymentByOrderId()/getPaymentByTransactionId() all return raw
    // Optional<Payment> with no @Transactional on the service methods, so
    // Jackson serializes after the session closes - LAZY here throws
    // LazyInitializationException on those three endpoints. Revisit alongside
    // a DTO refactor of those lookups (getAllPayments/refundPayment already
    // map to PaymentResponse and would be safe to convert independently).
    @OneToOne(fetch = FetchType.LAZY)
        private Order order;

    private BigDecimal amount;

  @Enumerated(EnumType.STRING)
private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
private PaymentStatus paymentStatus;

    // unique=true still allows multiple NULLs in Postgres (COD and
    // not-yet-confirmed UPI payments have no transaction ID yet) - it only
    // rejects a second row with the SAME non-null value, which is exactly
    // what prevents two payments from ever claiming the same real UPI
    // transaction.
    @Column(unique = true)
    private String transactionId;

    private LocalDateTime paymentDate;

    private Boolean active;

    // ---- Gateway fields. Null on every COD and direct-UPI payment, and
    // that is the accurate state rather than a gap: those never touch a
    // provider. See V14.

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private PaymentProvider provider;

    /**
     * The provider's id for the ORDER we asked it to collect - what we send.
     *
     * Unique at the database level (V14). Two of our orders can never share
     * one Cashfree order, and the constraint is what enforces that rather
     * than a check somewhere that a concurrent request could pass at the
     * same moment.
     */
    @Column(name = "provider_order_id", length = 120, unique = true)
    private String providerOrderId;

    /**
     * The provider's id for the actual PAYMENT - what comes back.
     *
     * Also unique. A single real payment at Cashfree can never be banked
     * against two of our payments, however many times a webhook is retried
     * or a client callback races it.
     */
    @Column(name = "provider_payment_id", length = 120, unique = true)
    private String providerPaymentId;

    /**
     * Stored rather than assumed. Every gateway event echoes a currency, and
     * one that does not match what we asked for is rejected - a payment
     * settled in a currency nobody quoted is not this order being paid.
     */
    @Column(length = 3)
    private String currency;

    /** The provider's own words on why a payment failed, for support. */
    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ------------------------------------------------------------ refunds
    //
    // A REFUND IS A THING THAT HAPPENED, not just a status. The status alone
    // could say REFUNDED while the money was still at the provider, because
    // nothing ever left the building. These carry the evidence.

    /**
     * This application's id for the refund, and the key the provider dedups
     * on. Derived from the payment, never random - a retry after a timeout
     * must reach the same refund rather than send the money twice.
     */
    @Column(name = "refund_id", length = 64)
    private String refundId;

    /** The provider's own id, for reconciling against their dashboard. */
    @Column(name = "provider_refund_id", length = 64)
    private String providerRefundId;

    @Column(name = "refund_amount", precision = 12, scale = 2)
    private BigDecimal refundAmount;

    /**
     * Set when the provider confirms, or when a shopkeeper records handing
     * cash back. Never set at the moment of asking - that is the difference
     * between "we asked" and "they have it".
     */
    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    /**
     * When the provider was asked, which is what makes a stuck refund
     * visible. Distinct from updatedAt, which any write to this row moves -
     * an unrelated status touch would otherwise reset a stuck refund's age
     * to zero, hiding exactly the case worth catching.
     *
     * Null for refunds that predate the column, and for cash.
     */
    @Column(name = "refund_requested_at")
    private LocalDateTime refundRequestedAt;

    @Column(name = "refund_failure_reason", length = 255)
    private String refundFailureReason;

    /**
     * CASH or GATEWAY. A COD refund never touches the provider and must not
     * be reconciled against it; without this the two are indistinguishable
     * afterwards.
     */
    @Column(name = "refund_channel", length = 16)
    @Enumerated(EnumType.STRING)
    private RefundChannel refundChannel;

    public enum RefundChannel { CASH, GATEWAY }

    public Payment() {
    }

    public String getRefundId() { return refundId; }
    public void setRefundId(String refundId) { this.refundId = refundId; }

    public String getProviderRefundId() { return providerRefundId; }
    public void setProviderRefundId(String providerRefundId) { this.providerRefundId = providerRefundId; }

    public BigDecimal getRefundAmount() { return refundAmount; }
    public void setRefundAmount(BigDecimal refundAmount) { this.refundAmount = refundAmount; }

    public LocalDateTime getRefundedAt() { return refundedAt; }
    public void setRefundedAt(LocalDateTime refundedAt) { this.refundedAt = refundedAt; }

    public LocalDateTime getRefundRequestedAt() { return refundRequestedAt; }
    public void setRefundRequestedAt(LocalDateTime at) { this.refundRequestedAt = at; }

    public String getRefundFailureReason() { return refundFailureReason; }
    public void setRefundFailureReason(String reason) { this.refundFailureReason = reason; }

    public RefundChannel getRefundChannel() { return refundChannel; }
    public void setRefundChannel(RefundChannel refundChannel) { this.refundChannel = refundChannel; }

    public Long getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order order) {
        this.order = order;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

 public PaymentMethod getPaymentMethod() {
    return paymentMethod;
}

public void setPaymentMethod(PaymentMethod paymentMethod) {
    this.paymentMethod = paymentMethod;
}

  public PaymentStatus getPaymentStatus() {
    return paymentStatus;
}

public void setPaymentStatus(PaymentStatus paymentStatus) {
    this.paymentStatus = paymentStatus;
}

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public LocalDateTime getPaymentDate() {
        return paymentDate;
    }

    public void setPaymentDate(LocalDateTime paymentDate) {
        this.paymentDate = paymentDate;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public PaymentProvider getProvider() { return provider; }
    public void setProvider(PaymentProvider provider) { this.provider = provider; }

    public String getProviderOrderId() { return providerOrderId; }
    public void setProviderOrderId(String providerOrderId) { this.providerOrderId = providerOrderId; }

    public String getProviderPaymentId() { return providerPaymentId; }
    public void setProviderPaymentId(String providerPaymentId) { this.providerPaymentId = providerPaymentId; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
