package com.gpstore.store.hours;

import com.gpstore.platform.ShopOwned;
import com.gpstore.platform.ShopScopeFilter;
import com.gpstore.platform.TenantEntityListener;
import jakarta.persistence.*;
import org.hibernate.annotations.Filter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * "On the 14th we open late" - one date's hours, instead of that weekday's.
 *
 * NOT A WAY TO SAY "CLOSED". store_closures already says that, per shop since
 * V54, with a reason a customer sees and an audit trail. Two tables that can
 * both express a closed day would drift the first time somebody used the
 * other one, so these two do not overlap: a closure shuts the day, an
 * override changes the hours of a day that is open. Closure wins where both
 * exist, which is the only precedence rule anybody has to remember.
 *
 * Sessions, like the weekly pattern: a date can have several rows.
 */
@Entity
@Table(name = "shop_hours_override",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_shop_override_session",
                columnNames = {"shop_id", "on_date", "opens_at"}))
@Filter(name = ShopScopeFilter.NAME, condition = ShopScopeFilter.CONDITION)
@EntityListeners(TenantEntityListener.class)
public class ShopHoursOverride implements ShopOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id")
    private Long shopId;

    @Column(name = "on_date", nullable = false)
    private LocalDate onDate;

    @Column(name = "opens_at", nullable = false)
    private LocalTime opensAt;

    @Column(name = "closes_at", nullable = false)
    private LocalTime closesAt;

    /** Shown to customers, so it is written for them, not for the log. */
    @Column(name = "reason", length = 300)
    private String reason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 120)
    private String createdBy;

    @PrePersist
    void onInsert() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    @Override
    public Long getShopId() { return shopId; }

    @Override
    public void setShopId(Long shopId) { this.shopId = shopId; }

    public LocalDate getOnDate() { return onDate; }
    public void setOnDate(LocalDate onDate) { this.onDate = onDate; }

    public LocalTime getOpensAt() { return opensAt; }
    public void setOpensAt(LocalTime opensAt) { this.opensAt = opensAt; }

    public LocalTime getClosesAt() { return closesAt; }
    public void setClosesAt(LocalTime closesAt) { this.closesAt = closesAt; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
