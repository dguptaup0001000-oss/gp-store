package com.gpstore.notify;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * The record that an order has already been announced.
 *
 * <p>ITS ONLY JOB IS THE UNIQUE INDEX. {@code ux_order_alert_once} covers
 * (order_id, kind), so the second attempt to announce an order fails to insert
 * instead of reaching a phone. That matters because there are at least six ways
 * one order can arrive at the dispatcher twice - a customer retrying checkout, a
 * duplicated HTTP request, a replayed Cashfree webhook, an outbox retry, a
 * database transaction retry, and FCM's own redelivery - and a shop counter that
 * shouts twice for one order stops being trusted.
 *
 * <p>NOT SHOP-OWNED: it is written by the dispatcher on a platform-scoped thread
 * after the order's transaction has committed, and it records which shop was
 * notified rather than being filtered by it.
 */
@Entity
@Table(name = "order_alerts_sent")
public class OrderAlertSent {

    /** The only kind so far. Named rather than implied, so a second kind is additive. */
    public static final String NEW_ORDER = "NEW_ORDER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false, length = 40)
    private String kind = NEW_ORDER;

    @Column(name = "shop_id")
    private Long shopId;

    @Column(nullable = false)
    private Integer recipients = 0;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @PrePersist
    void onCreate() {
        if (this.sentAt == null) {
            this.sentAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Long getShopId() {
        return shopId;
    }

    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }

    public Integer getRecipients() {
        return recipients;
    }

    public void setRecipients(Integer recipients) {
        this.recipients = recipients;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }
}
