package com.gpstore.platform;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One storefront on the marketplace.
 *
 * WHAT THIS REPLACES. The shop is currently a set of environment variables -
 * STORE_LATITUDE, STORE_LONGITUDE, STORE_TIMEZONE, STORE_MAX_DELIVERY_RADIUS_KM,
 * STORE_SUPPORT_PHONE and the rest - plus two config rows keyed id = 1. That
 * works for exactly one shop and cannot describe a second, because a process
 * has one environment.
 *
 * SHOP #1 IS THE EXISTING SHOP. It is created from those same environment
 * values at startup (ShopBootstrap) so nothing about the running system
 * changes: the same coordinates, the same radius, the same support numbers,
 * now in a row that a second shop can sit beside.
 *
 * NO PAYMENT COLUMNS HERE, DELIBERATELY. Where the money goes - platform
 * collects and settles, or each merchant collects directly - is an open
 * business and regulatory question (decision W1), and a column added now
 * would encode an answer nobody has given. Payment routing is the last slice
 * for the same reason: it is the only one that touches a live payment path.
 *
 * NOT WIRED IN YET. Slice 0 creates this table and Shop #1; nothing reads it.
 */
@Entity
@Table(name = "shops")
public class Shop {

    /**
     * The shop the single-shop system has always been.
     *
     * Fixed rather than generated so a backfill, a test fixture and a
     * SINGLE_SHOP tenant resolution all name the same row without a lookup.
     */
    public static final long FIRST_SHOP_ID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Which business runs this storefront.
     *
     * A plain id, not a @ManyToOne, for now: Slice 0 must not add a lazy
     * association that some future query silently initialises before the
     * ownership rules exist to say who may read it.
     */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** Stable, human-typeable identifier. Never reused, never renamed. */
    @Column(nullable = false, length = 40, unique = true)
    private String code;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ShopStatus status = ShopStatus.DRAFT;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    // ---------------------------------------------------------------- where

    /**
     * Where the shop physically is, and how far it will deliver.
     *
     * SIMPLEST RELIABLE SOLUTION FIRST (§89): an origin point and a radius.
     * The polygon subzones the territory engine already draws remain
     * available to refine it, but a shop must be able to start trading
     * without anybody drawing a map.
     */
    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "max_delivery_radius_km", precision = 6, scale = 2)
    private BigDecimal maxDeliveryRadiusKm;

    /** IANA zone. Hours mean nothing without it once there is a second city. */
    @Column(name = "time_zone", length = 60)
    private String timeZone;

    @Column(name = "address_line", length = 300)
    private String addressLine;

    @Column(name = "locality", length = 120)
    private String locality;

    @Column(name = "city", length = 120)
    private String city;

    @Column(name = "state", length = 120)
    private String state;

    @Column(name = "pincode", length = 12)
    private String pincode;

    // -------------------------------------------------------------- contact

    @Column(name = "support_phone", length = 30)
    private String supportPhone;

    @Column(name = "support_email", length = 190)
    private String supportEmail;

    @Column(name = "support_whatsapp", length = 30)
    private String supportWhatsapp;

    // --------------------------------------------------------------- flags

    /** A seeded demo storefront, never a real business (§22). */
    @Column(name = "is_demo", nullable = false)
    private Boolean isDemo = Boolean.FALSE;

    @Column(nullable = false)
    private Boolean active = Boolean.TRUE;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Soft delete (§91). */
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

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public ShopStatus getStatus() { return status; }
    public void setStatus(ShopStatus status) { this.status = status; }

    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }

    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }

    public BigDecimal getMaxDeliveryRadiusKm() { return maxDeliveryRadiusKm; }
    public void setMaxDeliveryRadiusKm(BigDecimal km) { this.maxDeliveryRadiusKm = km; }

    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }

    public String getAddressLine() { return addressLine; }
    public void setAddressLine(String addressLine) { this.addressLine = addressLine; }

    public String getLocality() { return locality; }
    public void setLocality(String locality) { this.locality = locality; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getPincode() { return pincode; }
    public void setPincode(String pincode) { this.pincode = pincode; }

    public String getSupportPhone() { return supportPhone; }
    public void setSupportPhone(String supportPhone) { this.supportPhone = supportPhone; }

    public String getSupportEmail() { return supportEmail; }
    public void setSupportEmail(String supportEmail) { this.supportEmail = supportEmail; }

    public String getSupportWhatsapp() { return supportWhatsapp; }
    public void setSupportWhatsapp(String w) { this.supportWhatsapp = w; }

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
