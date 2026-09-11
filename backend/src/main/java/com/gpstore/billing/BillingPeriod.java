package com.gpstore.billing;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One merchant's billing week.
 *
 * <p>THREE STATES, AND THE MIDDLE ONE IS WHERE DISPUTES LIVE. OPEN while the
 * week is running; CLOSED once its entries are written and it has a number on
 * it; SETTLED once the money has actually moved. Collapsing CLOSED and SETTLED
 * would make "billed but not collected" - which is most of a marketplace's
 * accounts at any moment - unrepresentable.
 *
 * <p>ONE PER MERCHANT PER WEEK, and the database says so rather than the job
 * that writes them. A weekly biller that runs twice (a retry, two instances, a
 * person) must not be able to bill the same week twice, and a unique index is
 * the difference between that being impossible and being unlikely.
 */
@Entity
@Table(name = "billing_period",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_billing_period_merchant_week",
                columnNames = {"merchant_id", "starts_on"}))
public class BillingPeriod {

    public enum Status {
        /** The week is running. Entries may still arrive. */
        OPEN,
        /** Totalled. Has a number on it, and has not been collected. */
        CLOSED,
        /** The money moved. */
        SETTLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private Status status = Status.OPEN;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void onInsert() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /** The Monday-to-Sunday week a date falls in. */
    public static BillingPeriod weekOf(Long merchantId, LocalDate anyDayInTheWeek) {
        LocalDate monday = anyDayInTheWeek.with(
                java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        BillingPeriod period = new BillingPeriod();
        period.merchantId = merchantId;
        period.startsOn = monday;
        period.endsOn = monday.plusDays(6);
        return period;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public LocalDate getStartsOn() { return startsOn; }
    public void setStartsOn(LocalDate startsOn) { this.startsOn = startsOn; }

    public LocalDate getEndsOn() { return endsOn; }
    public void setEndsOn(LocalDate endsOn) { this.endsOn = endsOn; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }

    public LocalDateTime getSettledAt() { return settledAt; }
    public void setSettledAt(LocalDateTime settledAt) { this.settledAt = settledAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
