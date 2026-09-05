package com.gpstore.platform;

import jakarta.persistence.*;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;

/**
 * Which shops a staff account may work in.
 *
 * ONE ROW PER (SHOP, ACCOUNT). A merchant running three kiranas has three
 * rows and may operate exactly those three; a manager hired for one has one.
 * Nothing about a fourth shop needs code.
 *
 * READ EVERY REQUEST, NEVER CARRIED IN A TOKEN. This is the same decision the
 * codebase already made for permissions - JwtFilter derives authorities from
 * the live account row rather than from a claim, so a demotion applies on the
 * next request instead of whenever the token expires. A shop claim inside a
 * JWT has the identical hole: move a manager between shops, or remove them
 * from one, and their existing token goes on working against the old shop.
 *
 * IT IS ALSO SHOP-OWNED, so a shop cannot read another shop's staff list -
 * who works where is exactly the kind of thing a competitor should not be
 * able to enumerate. The membership LOOKUP itself deliberately runs outside
 * the filter, because resolving "which shop am I in" cannot depend on already
 * knowing.
 */
@Entity
@Table(name = "shop_staff",
        uniqueConstraints = @UniqueConstraint(name = "uk_shop_staff",
                columnNames = {"shop_id", "customer_id"}))
@Filter(name = ShopScopeFilter.NAME, condition = ShopScopeFilter.CONDITION)
@EntityListeners(TenantEntityListener.class)
public class ShopStaff implements ShopOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id")
    private Long shopId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /**
     * Where this account lands when the request names no shop.
     *
     * Exactly one per account, enforced by a partial unique index rather than
     * by hope: "which shop am I in" has to have one answer, and two rows
     * claiming to be the default is a coin toss between two merchants' data.
     */
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = Boolean.FALSE;

    @Column(nullable = false)
    private Boolean active = Boolean.TRUE;

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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    @Override
    public Long getShopId() { return shopId; }

    @Override
    public void setShopId(Long shopId) { this.shopId = shopId; }

    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }

    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
