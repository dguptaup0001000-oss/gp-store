package com.gpstore.platform;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * The business behind one or more shops.
 *
 * SEPARATE FROM THE SHOP ON PURPOSE (§5). Raj Kumar may run Raj Hardware, Raj
 * Electrical and Raj Home Supplies; those are three storefronts and one
 * business, with one KYC file, one approval and one suspension. Modelling
 * user = shop - which is what this codebase does today, with Role as a column
 * on customers - makes a second shop impossible without a second login and
 * makes "suspend this business" mean "suspend one of its three shops".
 *
 * SEPARATE FROM THE USER TOO. The people who log in are a later slice
 * (merchant_user); a merchant is the business, not an account. Keeping them
 * apart is what lets a shopkeeper hand day-to-day access to a manager without
 * handing over the business record.
 *
 * NOT WIRED IN YET. Slice 0 creates this table and Shop #1; nothing reads it.
 * See docs/architecture/02-parity-architecture-migration.md.
 */
@Entity
@Table(name = "merchants")
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The name on the paperwork. */
    @Column(name = "legal_name", nullable = false, length = 200)
    private String legalName;

    /** What customers see, when it differs from the legal name. */
    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(name = "contact_name", length = 160)
    private String contactName;

    @Column(name = "contact_email", length = 190)
    private String contactEmail;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MerchantStatus status = MerchantStatus.APPLICATION;

    /**
     * Why the merchant is in this status, in words a person wrote.
     *
     * Required by §21: no merchant may be suspended or removed without a
     * recorded reason. The reason CODE lives with the audit entry; this is
     * the current human-readable state so a support agent does not have to
     * read the log to answer "why can I not trade".
     */
    @Column(name = "status_reason", length = 500)
    private String statusReason;

    /**
     * A seeded demo merchant, not a real business (§22).
     *
     * Demo merchants run the real architecture - real tables, real
     * authorization, real orders - because a demo built on a separate fake
     * path proves nothing about the system a real shopkeeper would join.
     * The flag exists so nothing ever presents them as real traction.
     */
    @Column(name = "is_demo", nullable = false)
    private Boolean isDemo = Boolean.FALSE;

    @Column(nullable = false)
    private Boolean active = Boolean.TRUE;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Soft delete (§91). Financial records outlive the relationship. */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getLegalName() { return legalName; }
    public void setLegalName(String legalName) { this.legalName = legalName; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }

    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }

    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }

    public MerchantStatus getStatus() { return status; }
    public void setStatus(MerchantStatus status) { this.status = status; }

    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }

    public Boolean getIsDemo() { return isDemo; }
    public void setIsDemo(Boolean isDemo) { this.isDemo = isDemo; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}
