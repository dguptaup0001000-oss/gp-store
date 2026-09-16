package com.gpstore.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Null for system-triggered actions (e.g. an automated status change with no human actor).
    private Long actorCustomerId;

    private String actorEmail;

    private String actorRole;

    // e.g. "ORDER_CANCELLED", "PAYMENT_REFUNDED", "ORDER_STATUS_CHANGED", "COUPON_CREATED"
    @Column(nullable = false)
    private String action;

    // e.g. "Order", "Payment", "Coupon"
    private String entityType;

    private Long entityId;

    private Long merchantId;

    private Long shopId;

    @Column(length = 500)
    private String previousState;

    @Column(length = 500)
    private String newState;

    @Column(length = 500)
    private String reason;

    @Column(length = 64)
    private String requestId;

    // Free-text context - e.g. "status: CONFIRMED -> PACKING" or "refund amount: 450.00"
    @Column(length = 1000)
    private String details;

    @Column(nullable = false)
    private LocalDateTime occurredAt;
}
