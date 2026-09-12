package com.gpstore.platform.shopinfo;

import com.gpstore.platform.ShopOwned;
import com.gpstore.platform.ShopScopeFilter;
import com.gpstore.platform.TenantEntityListener;
import jakarta.persistence.*;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;

/**
 * One promise a shop makes to its customers, in the shopkeeper's own words.
 *
 * <p>ROWS, NOT COLUMNS. §3 names three today - delivery, cancellation,
 * returns - and a fourth should be a row rather than a migration on a
 * database somebody is trading on. It also keeps four paragraphs of prose out
 * of the shops row, which is read on every discovery call by every customer
 * who opens the app.
 *
 * <p>THE KIND IS A STRING, not an enum type in the database, for the same
 * reason. It is validated against {@link ShopPolicyKind} on the way in, so
 * the values are still closed - the flexibility is about deployment, not
 * about letting anything through.
 *
 * <p>NO DEFAULTS, EVER. A policy GP-STORE wrote and attributed to a
 * shopkeeper is a promise they did not make, and the first time it is tested
 * will be in front of a customer. A shop with no policy shows none.
 */
@Entity
@Table(name = "shop_policies",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_shop_policies_kind", columnNames = {"shop_id", "kind"}))
@Filter(name = ShopScopeFilter.NAME, condition = ShopScopeFilter.CONDITION)
@EntityListeners(TenantEntityListener.class)
public class ShopPolicy implements ShopOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id")
    private Long shopId;

    @Column(name = "kind", nullable = false, length = 40)
    private String kind;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

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

    public ShopPolicyKind kind() {
        return ShopPolicyKind.valueOf(kind);
    }

    public void setKind(ShopPolicyKind kind) {
        this.kind = kind.name();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    @Override
    public Long getShopId() { return shopId; }

    @Override
    public void setShopId(Long shopId) { this.shopId = shopId; }

    public String getKindName() { return kind; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
