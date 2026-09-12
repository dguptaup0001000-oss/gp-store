package com.gpstore.catalog.shop;

import com.gpstore.platform.ShopOwned;
import com.gpstore.platform.ShopScopeFilter;
import com.gpstore.platform.TenantEntityListener;
import jakarta.persistence.*;
import org.hibernate.annotations.Filter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * What one shop charges for one catalogue item.
 *
 * THE CATALOGUE IS NOT COPIED PER SHOP. products and product_variants hold one
 * row per real-world item and are shared: two kiranas selling Aashirvaad atta
 * 5 kg point at the same variant. What differs between them - the price, whether
 * they list it at all, their own shelf order - is this row. Adding Shop N adds
 * rows, never tables and never code.
 *
 * IT IS SHOP-OWNED, so everything Slice 1 built applies to it without any
 * further work: reads are filtered to the shop in scope, a row from another
 * shop is refused even when fetched by primary key, and an insert is stamped
 * with the shop the credential resolved to rather than any shop_id the object
 * arrived carrying. That is the whole answer to "Shop A must not manipulate
 * Shop B's commercial data" - a competitor's price is not something A can read,
 * edit or delete, and not something A can create on B's behalf either.
 *
 * WHY STOCK IS NOT HERE, given inventory is keyed the same way. The two have
 * opposite write patterns: a price is edited by a person, occasionally; stock
 * is decremented under a row lock on every single checkout. Keeping them apart
 * keeps a price edit out of contention with live orders, and keeps every stock
 * decrement from rewriting the price columns beside it.
 *
 * ROW EXISTS means "this shop sells this". Deleting it delists the item; it does
 * not touch the catalogue, and no other shop notices.
 */
@Entity
@Table(name = "shop_product_variants",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_shop_product_variant",
                columnNames = {"shop_id", "product_variant_id"}))
@Filter(name = ShopScopeFilter.NAME, condition = ShopScopeFilter.CONDITION)
@EntityListeners(TenantEntityListener.class)
public class ShopProductVariant implements ShopOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id")
    private Long shopId;

    /**
     * The catalogue item, by id rather than by @ManyToOne.
     *
     * A plain id keeps this row loadable and writable without dragging a
     * ProductVariant - and its Product, and that product's category - into
     * every price lookup on a twenty-item cart. The catalogue is fetched
     * separately, once, by the code that actually needs its names and photos.
     */
    @Column(name = "product_variant_id", nullable = false)
    private Long productVariantId;

    @Column(name = "selling_price", nullable = false)
    private BigDecimal sellingPrice;

    /** What this shop paid. Never leaves the server - see ProductVariant.costPrice. */
    @Column(name = "cost_price")
    private BigDecimal costPrice;

    /** This shop's printed price, when it differs from the catalogue's. Usually null. */
    private BigDecimal mrp;

    /** Listed and orderable here. Distinct from stock: a shop can delist an item it still holds. */
    private Boolean available;

    private Boolean active;

    @Column(name = "display_order")
    private Integer displayOrder;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onInsert() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** Listed, active, and therefore something a customer may put in a basket. */
    public boolean isOrderable() {
        return Boolean.TRUE.equals(available)
                && Boolean.TRUE.equals(active)
                && sellingPrice != null
                && sellingPrice.compareTo(BigDecimal.ZERO) > 0;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    @Override
    public Long getShopId() { return shopId; }

    @Override
    public void setShopId(Long shopId) { this.shopId = shopId; }

    public Long getProductVariantId() { return productVariantId; }
    public void setProductVariantId(Long productVariantId) { this.productVariantId = productVariantId; }

    public BigDecimal getSellingPrice() { return sellingPrice; }
    public void setSellingPrice(BigDecimal sellingPrice) { this.sellingPrice = sellingPrice; }

    public BigDecimal getCostPrice() { return costPrice; }
    public void setCostPrice(BigDecimal costPrice) { this.costPrice = costPrice; }

    public BigDecimal getMrp() { return mrp; }
    public void setMrp(BigDecimal mrp) { this.mrp = mrp; }

    public Boolean getAvailable() { return available; }
    public void setAvailable(Boolean available) { this.available = available; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
