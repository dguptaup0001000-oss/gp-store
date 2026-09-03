package com.gpstore.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One line of an order coming back, and how many of it.
 *
 * POINTS AT THE ORDER LINE, NOT THE PRODUCT. Two lines can carry the same
 * variant at different prices - a coupon applied to one, a price change
 * between orders - so refunding "the product" would pick one of them at
 * random and be wrong half the time.
 */
@Entity
@Table(name = "order_return_items")
@Getter
@Setter
public class OrderReturnItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_return_id", nullable = false)
    private OrderReturn orderReturn;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    /**
     * What these units were actually charged at, copied at request time so
     * the return's own record survives a later change to the order.
     */
    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void stampCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
