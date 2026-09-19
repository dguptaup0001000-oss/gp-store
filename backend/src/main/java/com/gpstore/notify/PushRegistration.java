package com.gpstore.notify;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One app install that may be pushed to.
 *
 * <p>NOT SHOP-OWNED, AND THAT IS DELIBERATE. A device belongs to an account, not
 * to a shop - the same person may manage two shops from one phone, and a manager
 * removed from a shop keeps their device. Which shop's orders reach this row is
 * therefore decided at dispatch time, from live {@code shop_staff} rows, rather
 * than frozen into the registration. See {@link NewOrderAlerts}.
 *
 * <p>THE TOKEN IS UNIQUE PLATFORM-WIDE. An FCM token identifies one install; if
 * the same token arrives for a different account the row MOVES, because the
 * phone has been handed over or a second merchant has signed in on it. Leaving
 * the old row behind is precisely how one merchant would keep hearing another
 * merchant's orders.
 */
@Entity
@Table(name = "push_registrations")
public class PushRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(nullable = false, length = 32)
    private String app;

    @Column(nullable = false, length = 16)
    private String platform = "ANDROID";

    @Column(nullable = false)
    private String token;

    @Column(name = "device_id", length = 128)
    private String deviceId;

    @Column(nullable = false)
    private Boolean enabled = Boolean.TRUE;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.lastSeenAt == null) {
            this.lastSeenAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public String getApp() {
        return app;
    }

    public void setApp(String app) {
        this.app = app;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(LocalDateTime lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }
}
