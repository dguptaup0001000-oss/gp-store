package com.gpstore.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One refund. Not "the refund on this payment" - one of however many there
 * are.
 *
 * WHY THIS IS A TABLE AND NOT SIX MORE COLUMNS. A payment used to carry one
 * refund id, one amount and one timestamp, which is exactly enough room for
 * one refund. A customer returning two items on separate days would have had
 * the first refund's record overwritten by the second, leaving the shop's
 * books claiming less went back than actually did - invisible until somebody
 * reconciles against the bank.
 *
 * THE PAYMENT'S COLUMNS ARE NOW A SUMMARY of these rows and still mean what
 * they always did: refundAmount is the total that has gone back, refundedAt
 * is when the last one landed. Everything reading them keeps working.
 *
 * THE MONEY INVARIANT lives one level up, in PaymentService: the sum of the
 * rows that have not failed can never exceed what the customer paid. It
 * cannot live here, because a single row has no view of its siblings.
 */
@Entity
@Table(name = "refunds")
@Getter
@Setter
public class Refund {

    /** What a refund is doing right now. */
    public enum Status {
        /** Sent, or recorded for cash, and not yet confirmed. */
        PENDING,
        /** The provider said SUCCESS, or a shopkeeper handed the notes back. */
        SUCCEEDED,
        /**
         * The provider refused it. Money did NOT go back and will not on its
         * own - this is the state the money-health alert exists to shout
         * about, because the customer is still owed.
         */
        FAILED
    }

    /** Same meaning as Payment.RefundChannel: did this touch a provider at all. */
    public enum Channel { CASH, GATEWAY }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    /**
     * Which refund this is against the payment: 1, 2, 3.
     *
     * Drives the refund id, so it is stable once written. A unique index on
     * (payment_id, sequence_no) is what stops two concurrent refunds both
     * claiming to be number two.
     */
    @Column(name = "sequence_no", nullable = false)
    private Integer sequenceNo;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private Channel channel;

    /**
     * Our id for this refund, and the key the provider deduplicates on.
     *
     * Deterministic, which is the entire safety property: a request that
     * timed out and is retried carries an id the provider has already seen
     * and is refused there, instead of sending the shop's money twice.
     */
    @Column(name = "refund_id", nullable = false, length = 64)
    private String refundId;

    @Column(name = "provider_refund_id", length = 64)
    private String providerRefundId;

    /** The shopkeeper's own words - "two atta returned". Never shown to the customer. */
    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    /**
     * When the provider took the request, and nothing else.
     *
     * This is the clock the stuck-refund alert measures against, so it is
     * stamped once - not at the intention, not at settlement. Null for a
     * refund that has not been sent yet.
     */
    @Column(name = "requested_at")
    private LocalDateTime requestedAt;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void stampCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
