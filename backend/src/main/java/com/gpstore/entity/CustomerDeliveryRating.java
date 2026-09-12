package com.gpstore.entity;

import com.gpstore.platform.ShopOwned;
import com.gpstore.platform.TenantEntityListener;
import com.gpstore.platform.ShopScopeFilter;
import org.hibernate.annotations.Filter;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * What the rider thought of a delivery, 1-10.
 *
 * ADMIN EYES ONLY. This is never returned on a customer-facing endpoint,
 * never shown to the person rated, and never used to decide anything about
 * their orders - not pricing, not delivery priority, not whether an order is
 * accepted. It exists so a shopkeeper can see a pattern across many
 * deliveries (never at home, address impossible to find, abusive to riders)
 * instead of relying on one rider remembering one bad afternoon.
 *
 * Recording it against the ORDER, not just the customer, is what makes it
 * checkable later: a rating with no delivery behind it is an opinion, and the
 * unique index on order_id stops one rider moving an average with repeated
 * taps on the same delivery.
 *
 * partnerId is a plain id rather than a relation on purpose - the rating has
 * to survive the rider leaving the roster, like every other record here that
 * outlives the person in it.
 */
@Entity
@Table(name = "customer_delivery_ratings")
@Filter(name = ShopScopeFilter.NAME, condition = ShopScopeFilter.CONDITION)
@EntityListeners(TenantEntityListener.class)
public class CustomerDeliveryRating implements ShopOwned {
    // ------------------------------------------------------- which shop
    //
    // Written once, at insert time, by TenantEntityListener - never by a
    // request. Read back through the "shopScope" filter (see the @Filter
    // above), which Hibernate turns into an extra "and shop_id = ?" on
    // every query against this table while a shop scope is active.
    //
    // Nullable in the column definition only because V46 added it to
    // tables that already had rows; every row is backfilled and the
    // migration refuses to complete otherwise.
    @Column(name = "shop_id")
    private Long shopId;


    public static final int MIN_SCORE = 1;
    public static final int MAX_SCORE = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "partner_id")
    private Long partnerId;

    @Column(nullable = false)
    private Integer score;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void stampCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }

    public Long getPartnerId() { return partnerId; }
    public void setPartnerId(Long partnerId) { this.partnerId = partnerId; }

    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @Override
    public Long getShopId() {
        return shopId;
    }

    @Override
    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }
}
