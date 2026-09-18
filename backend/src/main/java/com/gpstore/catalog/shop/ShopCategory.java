package com.gpstore.catalog.shop;

import com.gpstore.platform.ShopOwned;
import com.gpstore.platform.ShopScopeFilter;
import com.gpstore.platform.TenantEntityListener;
import jakarta.persistence.*;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;

/**
 * A department that belongs to ONE shop.
 *
 * <p>THE DISTINCTION THIS EXISTS TO KEEP. {@code categories} is the PLATFORM's
 * taxonomy - one tree every merchant picks from, where renaming a row renames
 * it for everybody. That is why writing to it needs CATALOG_DEFINE the moment a
 * second shop exists, and why a phone shop tapping Add Category was told "You
 * don't have permission to do that".
 *
 * <p>But the merchant was not trying to rewrite the marketplace. They were
 * trying to organise their own shelf - to have somewhere to put chargers. That
 * is a shopkeeper's daily work and it was impossible, because the only concept
 * in the schema was the shared one.
 *
 * <p>IT IS SHOP-OWNED, so everything the tenant layer already does applies with
 * no further work: reads are filtered to the shop in scope, a row belonging to
 * another shop is refused even when fetched by primary key, and an insert is
 * stamped with the shop the credential resolved to rather than any id the
 * object arrived carrying. Deepak Phone Shop creating "charger" therefore
 * cannot put "charger" on GUPT SAREE's screen, and cannot rename theirs.
 *
 * <p>{@link #globalCategoryId} IS OPTIONAL AND IS THE BRIDGE. Null means "my
 * own department, mine alone". Set means "this is me stocking that platform
 * department" - which is how enabling an existing category and inventing a new
 * one stay one concept instead of two that drift. It is also the controlled
 * route by which the platform can later promote a merchant's word into the
 * official tree: point the link at the new official row, keep this one.
 */
@Entity
@Table(name = "shop_categories")
@Filter(name = ShopScopeFilter.NAME, condition = ShopScopeFilter.CONDITION)
@EntityListeners(TenantEntityListener.class)
public class ShopCategory implements ShopOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id")
    private Long shopId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    /** The platform department this one stands for, when it stands for one. */
    @Column(name = "global_category_id")
    private Long globalCategoryId;

    @Column(name = "display_order")
    private Integer displayOrder;

    @Column(nullable = false)
    private Boolean active = Boolean.TRUE;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onInsert() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (active == null) {
            active = Boolean.TRUE;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Override
    public Long getShopId() { return shopId; }

    @Override
    public void setShopId(Long shopId) { this.shopId = shopId; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Long getGlobalCategoryId() { return globalCategoryId; }
    public void setGlobalCategoryId(Long globalCategoryId) { this.globalCategoryId = globalCategoryId; }

    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
