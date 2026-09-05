package com.gpstore.ordergroup;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One checkout, which may be several shops' orders.
 *
 * WHAT THE CUSTOMER THINKS THEY PLACED. They filled one basket and pressed
 * one button; that is this row. What each shop packs, prices, delivers and is
 * paid for is a separate Order underneath it, and each of those has its own
 * lifecycle - one can be cancelled or refunded while the other is out for
 * delivery.
 *
 * NO shop_id, DELIBERATELY. Spanning shops is its entire job, so it is
 * platform-level like the customer and the address it names (§4). What keeps
 * one customer out of another's group is the ownership check that already
 * guards every customer-owned row, not the tenant filter.
 *
 * IT EXISTS EVEN FOR ONE SHOP. A single-shop basket gets a group with one
 * order in it. Two code paths - one for "a shop" and one for "several" - is
 * how the rare one rots, and this way the split is exercised by every
 * checkout the shop takes today.
 */
@Entity
@Table(name = "order_groups")
public class OrderGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_number", nullable = false, unique = true, length = 40)
    private String groupNumber;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "address_id")
    private Long addressId;

    /**
     * What the customer agreed to pay, across every shop, as it stood at
     * checkout.
     *
     * The authoritative per-shop amounts live on the orders. This is kept
     * because a total recomputed later from orders that have since been
     * cancelled or refunded is not the number the customer agreed to.
     */
    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "shop_count", nullable = false)
    private Integer shopCount = 1;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
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

    public String getGroupNumber() { return groupNumber; }
    public void setGroupNumber(String groupNumber) { this.groupNumber = groupNumber; }

    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }

    public Long getAddressId() { return addressId; }
    public void setAddressId(Long addressId) { this.addressId = addressId; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public Integer getShopCount() { return shopCount; }
    public void setShopCount(Integer shopCount) { this.shopCount = shopCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
