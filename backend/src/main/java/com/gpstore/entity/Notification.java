package com.gpstore.entity;

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

    @ManyToOne
    private Customer customer;

    private String title;

    private String message;

   @ManyToOne
@JoinColumn(name = "order_id")
private Order order;

@Enumerated(EnumType.STRING)
private NotificationType notificationType;

@Enumerated(EnumType.STRING)
private NotificationStatus notificationStatus;

private LocalDateTime sentAt;

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

public Boolean getActive() {
    return active;
}

public void setActive(Boolean active) {
    this.active = active;
}
}