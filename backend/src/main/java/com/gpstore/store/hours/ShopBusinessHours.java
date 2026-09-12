package com.gpstore.store.hours;

import com.gpstore.platform.ShopOwned;
import com.gpstore.platform.ShopScopeFilter;
import com.gpstore.platform.TenantEntityListener;
import jakarta.persistence.*;
import org.hibernate.annotations.Filter;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * One stretch of one weekday that this shop is open.
 *
 * A SESSION, NOT A DAY. A kirana that shuts between one and four is two rows
 * on that weekday, not a second pair of columns on one row - because the day
 * a shopkeeper wants to describe has however many stretches it has, and a
 * schema that allows exactly two is a schema that will need a third.
 *
 * A WEEKDAY WITH NO ROWS IS CLOSED. That is what makes "we do not open on
 * Sundays" expressible without a flag: the absence of a session is the
 * absence of trading. It also means the weekly pattern is written as a whole
 * week at a time (see ShopHoursService.replaceWeek), never row by row, so a
 * half-saved edit cannot leave a shop accidentally shut on Tuesdays.
 *
 * A SHOP WITH NO ROWS AT ALL has not configured its hours, and trades on the
 * deployment's configuration - which is exactly what every shop did before
 * this table existed, and is what keeps Shop #1 open at the hours it was open
 * at yesterday (§19). The distinction between "no rows for Sunday" and "no
 * rows at all" is the whole of the backward compatibility argument, and it is
 * why {@link ShopHours#isConfigured()} exists.
 */
@Entity
@Table(name = "shop_business_hours",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_shop_hours_session",
                columnNames = {"shop_id", "day_of_week", "opens_at"}))
@Filter(name = ShopScopeFilter.NAME, condition = ShopScopeFilter.CONDITION)
@EntityListeners(TenantEntityListener.class)
public class ShopBusinessHours implements ShopOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id")
    private Long shopId;

    /** ISO-8601: 1 = Monday, matching {@link DayOfWeek#getValue()}. */
    @Column(name = "day_of_week", nullable = false)
    private Short dayOfWeek;

    @Column(name = "opens_at", nullable = false)
    private LocalTime opensAt;

    @Column(name = "closes_at", nullable = false)
    private LocalTime closesAt;

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

    /** The weekday as java.time sees it, so callers never juggle the numbering. */
    public DayOfWeek day() {
        return DayOfWeek.of(dayOfWeek);
    }

    public void setDay(DayOfWeek day) {
        this.dayOfWeek = (short) day.getValue();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    @Override
    public Long getShopId() { return shopId; }

    @Override
    public void setShopId(Long shopId) { this.shopId = shopId; }

    public Short getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(Short dayOfWeek) { this.dayOfWeek = dayOfWeek; }

    public LocalTime getOpensAt() { return opensAt; }
    public void setOpensAt(LocalTime opensAt) { this.opensAt = opensAt; }

    public LocalTime getClosesAt() { return closesAt; }
    public void setClosesAt(LocalTime closesAt) { this.closesAt = closesAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
