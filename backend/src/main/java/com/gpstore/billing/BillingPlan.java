package com.gpstore.billing;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * What one tier costs, from one date.
 *
 * <p>VERSIONED BY DATE RATHER THAN EDITED, and that is the whole design. A
 * merchant's invoice for a week in March has to be reproducible in October,
 * and it cannot be if the plan row it was computed from has since been
 * overwritten with a new fee. Changing a price is a new row with a new
 * effective_from; the old row stays and the old invoices still add up.
 *
 * <p>THE COMMISSION RATE IS BASIS POINTS, NOT A PERCENTAGE DOUBLE. 250 means
 * 2.50%. Money rates held as doubles disagree with themselves on the fourth
 * decimal place, and a marketplace reconciles thousands of these a week
 * against real bank statements.
 *
 * <p>PLATFORM DATA, not shop-owned: a plan belongs to GP-STORE and applies
 * across merchants, so it is deliberately not tenant-filtered. Nothing here
 * is readable by a merchant except through their own statement.
 */
@Entity
@Table(name = "billing_plan")
public class BillingPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tier", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private MerchantTier tier;

    /** P in §6, per week. */
    @Column(name = "weekly_fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal weeklyFee;

    /** C's rate. 250 = 2.50%. */
    @Column(name = "commission_bps", nullable = false)
    private Integer commissionBps;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** Null while this is the current plan for the tier. */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 120)
    private String createdBy;

    @Column(name = "note", length = 500)
    private String note;

    @PrePersist
    void onInsert() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /**
     * Commission on an amount, at this plan's rate.
     *
     * <p>HALF_UP AT TWO PLACES, decided here and nowhere else. Rounding
     * applied inconsistently across a ledger is how a statement ends up a
     * paisa away from the sum of its own rows - which is the kind of
     * discrepancy that costs a day to find and all of a merchant's trust.
     */
    public BigDecimal commissionOn(BigDecimal base) {
        if (base == null || base.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return base.multiply(BigDecimal.valueOf(commissionBps))
                .divide(BigDecimal.valueOf(10_000), 2, RoundingMode.HALF_UP);
    }

    /** Whether this plan is the one in force on a given day. */
    public boolean coversDate(LocalDate date) {
        return date != null
                && !date.isBefore(effectiveFrom)
                && (effectiveTo == null || date.isBefore(effectiveTo));
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public MerchantTier getTier() { return tier; }
    public void setTier(MerchantTier tier) { this.tier = tier; }

    public BigDecimal getWeeklyFee() { return weeklyFee; }
    public void setWeeklyFee(BigDecimal weeklyFee) { this.weeklyFee = weeklyFee; }

    public Integer getCommissionBps() { return commissionBps; }
    public void setCommissionBps(Integer commissionBps) { this.commissionBps = commissionBps; }

    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }

    public LocalDate getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(LocalDate effectiveTo) { this.effectiveTo = effectiveTo; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
