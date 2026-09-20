package com.gpstore.engagement;

import com.gpstore.catalog.shop.CommerceMode;
import com.gpstore.platform.ShopOwned;
import com.gpstore.platform.ShopScopeFilter;
import com.gpstore.platform.TenantEntityListener;

import jakarta.persistence.*;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;

/**
 * One thing a customer did about one listing.
 *
 * <p>An online sale records itself - an order, a payment, a receipt - and the
 * merchant's dashboard counts them. A Visit-to-Buy listing and a service have
 * none of that: the customer sees the card, taps Directions, walks in and pays
 * in cash, and GP-STORE never learns whether any of it happened. So a merchant
 * who lists their showroom stock has no idea whether it is working.
 *
 * <p>THIS IS THE ANSWER, AND IT IS A NARROW ONE. It records interest, because
 * interest is the part GP-STORE genuinely saw. It is NOT a sales record, must
 * never be presented as one, and nothing built on it may claim a rupee of
 * trade: "400 people asked for directions" is true, and "this merchant sold
 * Rs 1 lakh" is a number nobody measured.
 *
 * <p>SHOP-OWNED, so a merchant reads their own rows through the ordinary
 * tenant filter rather than a hand-written predicate somebody has to remember.
 */
@Entity
@Table(name = "listing_engagement_events")
@Filter(name = ShopScopeFilter.NAME, condition = ShopScopeFilter.CONDITION)
@EntityListeners(TenantEntityListener.class)
public class ListingEngagementEvent implements ShopOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id")
    private Long shopId;

    @Column(name = "product_variant_id", nullable = false)
    private Long productVariantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private EngagementKind kind;

    /**
     * The mode the listing was in WHEN THIS HAPPENED.
     *
     * <p>Copied rather than joined on purpose: a merchant who switches a
     * listing from Visit-to-Buy to online next month must not retroactively
     * rewrite what last month's interest was interest in.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "commerce_mode", nullable = false, length = 24)
    private CommerceMode commerceMode;

    /**
     * NULL FOR AN ANONYMOUS BROWSER, and that is the point - anonymous
     * browsing is most of a marketplace's traffic and an anonymous tap is
     * still a real signal. It is the only identifier kept: no device id, no
     * IP, no session token, nothing that could reconstruct one person's walk
     * through the app.
     */
    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt = LocalDateTime.now();

    @Override
    public Long getShopId() {
        return shopId;
    }

    @Override
    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }

    public Long getId() {
        return id;
    }

    public Long getProductVariantId() {
        return productVariantId;
    }

    public void setProductVariantId(Long productVariantId) {
        this.productVariantId = productVariantId;
    }

    public EngagementKind getKind() {
        return kind;
    }

    public void setKind(EngagementKind kind) {
        this.kind = kind;
    }

    public CommerceMode getCommerceMode() {
        return commerceMode;
    }

    public void setCommerceMode(CommerceMode commerceMode) {
        this.commerceMode = commerceMode;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }
}
