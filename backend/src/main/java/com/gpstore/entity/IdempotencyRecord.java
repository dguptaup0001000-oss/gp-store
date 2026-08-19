package com.gpstore.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Backs checkout idempotency: the client sends an Idempotency-Key header
 * (a UUID it generates once per checkout attempt, re-sent unchanged on any
 * retry of that same attempt). The unique constraint on
 * (customer_id, idempotency_key) is what actually prevents two concurrent
 * requests with the same key from both creating an order - a plain
 * application-level check-then-insert isn't safe against a genuine race,
 * only a real DB constraint is.
 *
 * orderId is null while the checkout this key belongs to is still being
 * processed, and set once that checkout completes - so its presence alone
 * doubles as a status flag without needing a separate enum column.
 */
@Entity
@Table(
        name = "idempotency_records",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_idempotency_customer_key",
                columnNames = {"customer_id", "idempotency_key"}
        )
)
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    // Named idempotencyKey (not just "key") to avoid any confusion with the
    // JPA @Id itself - this is a client-supplied value, not the row's identity.
    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    // Null = checkout for this key is still in progress (or failed and rolled
    // back). Non-null = the order that resulted from this exact checkout
    // attempt; a repeat request with the same key returns this order instead
    // of creating a second one.
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * SHA-256 of the canonical form of the checkout this key was first used
     * for - see OrderService.computeRequestFingerprint for exactly which
     * fields go into it.
     *
     * Without this, an idempotency key only answered "has this key been
     * used", so reusing one key for a genuinely different checkout would
     * silently replay the FIRST order and the customer would never receive
     * the second. Storing the fingerprint lets a reused key be told apart
     * from a retried one: same key + same request replays, same key +
     * different request is a client bug and gets a 409 instead of a wrong
     * order.
     *
     * Nullable because rows written before this column existed have no
     * fingerprint to compare against; those are treated as "cannot verify"
     * and replayed rather than rejected, which preserves the old behaviour
     * for in-flight keys across the deploy instead of failing real
     * customers' retries.
     */
    @Column(name = "request_fingerprint", length = 64)
    private String requestFingerprint;

    public IdempotencyRecord() {
    }

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public void setRequestFingerprint(String requestFingerprint) {
        this.requestFingerprint = requestFingerprint;
    }
}
