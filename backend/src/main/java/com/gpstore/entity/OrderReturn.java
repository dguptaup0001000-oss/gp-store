package com.gpstore.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A customer asking to hand something back, and the shop's answer.
 *
 * A REQUEST ABOUT GOODS, NOT ABOUT MONEY. The refund is a separate thing with
 * its own row in {@link Refund}, because a return can be approved while its
 * refund is still in flight, refused by the provider, or being retried - all
 * of which happen. Folding the money in here would make this row describe a
 * payment that had not occurred. {@link #refundId} is the link, set when the
 * refund is actually opened.
 */
@Entity
@Table(name = "order_returns")
@Getter
@Setter
public class OrderReturn {

    public enum Status {
        /** The customer has asked. Nobody has looked yet. */
        REQUESTED,
        /** The shop agreed. Stock is back and a refund has been opened. */
        APPROVED,
        /** The shop said no, and said why. */
        REJECTED,
        /** The customer changed their mind before anyone decided. */
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /**
     * Denormalised from the order deliberately: "my returns" is the customer
     * app's only query here, and joining through orders for it buys nothing.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    /** The customer's own words, kept verbatim. */
    @Column(name = "reason", length = 500)
    private String reason;

    /**
     * The shopkeeper's answer. Required on a rejection - a refusal with no
     * reason is how a customer decides the shop is dishonest.
     */
    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    /** Computed from the order's own line prices at approval, never from the request. */
    @Column(name = "refund_amount", precision = 12, scale = 2)
    private BigDecimal refundAmount;

    /** The refunds row this opened, if any. Null for a rejection. */
    @Column(name = "refund_id", length = 64)
    private String refundId;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** Which staff account decided. A returns decision is money, so it is attributable. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private Customer decidedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "orderReturn", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderReturnItem> items = new ArrayList<>();

    @PrePersist
    void stampCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
