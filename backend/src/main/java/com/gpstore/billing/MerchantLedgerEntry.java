package com.gpstore.billing;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One line of a merchant's account with GP-STORE.
 *
 * <p>APPEND ONLY, AND THE DATABASE ENFORCES IT. A trigger refuses UPDATE and
 * DELETE on this table; a correction is a new row whose reversalOfId points
 * at what it undoes. §9 asks for ledger history rather than mutable totals,
 * and the reason is practical: when a merchant disputes a charge four months
 * later, "you owe 812" is unanswerable, while a list of rows with dates,
 * reasons and the order each came from is an answer.
 *
 * <p>THE SIGN: positive means the merchant owes GP-STORE, negative means
 * GP-STORE owes the merchant. Stated on {@link LedgerEntryType} per type, so
 * no caller has to remember it.
 *
 * <p>NO BALANCE COLUMN ANYWHERE. A balance is the sum of the rows, computed
 * when asked. Storing it would create a second version of the truth that can
 * drift from the first, and the first is the one with the evidence attached.
 */
@Entity
@Table(name = "merchant_ledger_entry")
public class MerchantLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** Null for an entry that does not belong to a week - an intervention, say. */
    @Column(name = "billing_period_id")
    private Long billingPeriodId;

    @Column(name = "entry_type", nullable = false, length = 40)
    @Enumerated(EnumType.STRING)
    private LedgerEntryType entryType;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    /** Groupable. "How much did we credit for no-order weeks" has to be answerable. */
    @Column(name = "reason_code", nullable = false, length = 60)
    private String reasonCode;

    @Column(name = "description", length = 500)
    private String description;

    /** The sale this came from, where there is one. */
    @Column(name = "order_id")
    private Long orderId;

    /** What this row undoes, for a correction. */
    @Column(name = "reversal_of_id")
    private Long reversalOfId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** Who caused it: a job name, or an administrator. Never blank. */
    @Column(name = "created_by", nullable = false, length = 120)
    private String createdBy;

    @PrePersist
    void onInsert() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /**
     * A new entry, with everything a later reader needs to check it.
     *
     * <p>A factory rather than a builder because the arguments are not
     * optional: a ledger row with no reason code, or no actor, is a charge
     * nobody can explain, and the time to notice that is at the call site.
     */
    public static MerchantLedgerEntry of(Long merchantId, Long periodId, LedgerEntryType type,
                                         BigDecimal amount, String reasonCode, String actor) {
        MerchantLedgerEntry entry = new MerchantLedgerEntry();
        entry.merchantId = merchantId;
        entry.billingPeriodId = periodId;
        entry.entryType = type;
        entry.amount = amount;
        entry.reasonCode = reasonCode;
        entry.createdBy = actor;
        return entry;
    }

    public MerchantLedgerEntry describedAs(String description) {
        this.description = description;
        return this;
    }

    public MerchantLedgerEntry forOrder(Long orderId) {
        this.orderId = orderId;
        return this;
    }

    public MerchantLedgerEntry reversing(Long entryId) {
        this.reversalOfId = entryId;
        return this;
    }

    public Long getId() { return id; }
    public Long getMerchantId() { return merchantId; }
    public Long getBillingPeriodId() { return billingPeriodId; }
    public LedgerEntryType getEntryType() { return entryType; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getReasonCode() { return reasonCode; }
    public String getDescription() { return description; }
    public Long getOrderId() { return orderId; }
    public Long getReversalOfId() { return reversalOfId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
}
