package com.gpstore.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gpstore.enums.NotificationStatus;
import com.gpstore.enums.NotificationType;
import java.time.LocalDateTime;
import jakarta.persistence.*;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Hidden - this is always "me" (the caller viewing their own
    // notifications), no reason to renest their full profile here.
    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Customer customer;

    private String title;

    private String message;

   @ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "order_id")
// Trimmed to essentials (id/orderNumber/status) rather than the full order
// with all its items - a notification just needs enough to link back to
// the order, not to re-render its entire contents.
@JsonIgnoreProperties({"orderItems"})
private Order order;

@Enumerated(EnumType.STRING)
private NotificationType notificationType;

@Enumerated(EnumType.STRING)
private NotificationStatus notificationStatus;

private LocalDateTime sentAt;

    // Separate from NotificationStatus above - that tracks delivery
    // (SMS/push sent successfully or not), this tracks whether the
    // customer has actually seen it in the app. Didn't exist before -
    // there was no way to distinguish read from unread at all.
    private Boolean isRead = false;

    private Boolean active;
    
    public Notification() {
}

public Long getId() {
    return id;
}

public void setId(Long id) {
    this.id = id;
}

public Customer getCustomer() {
    return customer;
}

public void setCustomer(Customer customer) {
    this.customer = customer;
}

public String getTitle() {
    return title;
}

public void setTitle(String title) {
    this.title = title;
}

public String getMessage() {
    return message;
}

public void setMessage(String message) {
    this.message = message;
}

public Order getOrder() {
    return order;
}

public void setOrder(Order order) {
    this.order = order;
}

public NotificationType getNotificationType() {
    return notificationType;
}

public void setNotificationType(NotificationType notificationType) {
    this.notificationType = notificationType;
}

public NotificationStatus getNotificationStatus() {
    return notificationStatus;
}

public void setNotificationStatus(NotificationStatus notificationStatus) {
    this.notificationStatus = notificationStatus;
}

public LocalDateTime getSentAt() {
    return sentAt;
}

public void setSentAt(LocalDateTime sentAt) {
    this.sentAt = sentAt;
}

public Boolean getIsRead() {
    return isRead;
}

public void setIsRead(Boolean isRead) {
    this.isRead = isRead;
}

public Boolean getActive() {
    return active;
}

public void setActive(Boolean active) {
    this.active = active;
}
}