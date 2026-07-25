package com.gpstore.controller;

import com.gpstore.entity.Notification;
import com.gpstore.security.CurrentUser;
import com.gpstore.service.NotificationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final CurrentUser currentUser;

    public NotificationController(NotificationService notificationService, CurrentUser currentUser) {
        this.notificationService = notificationService;
        this.currentUser = currentUser;
    }

    // Admin only (enforced in SecurityConfig) - manual notification creation,
    // e.g. a broadcast message not tied to an order status change.
    @PostMapping
    public Notification createNotification(@RequestBody Notification notification) {
        return notificationService.sendNotification(notification);
    }

    // Admin only (enforced in SecurityConfig) - store-wide announcement,
    // one real notification row per active customer.
    @PostMapping("/broadcast")
    public String broadcast(@RequestBody java.util.Map<String, String> request) {
        int count = notificationService.broadcastToAll(request.get("title"), request.get("message"));
        return "Sent to " + count + " customer" + (count == 1 ? "" : "s");
    }

    // Admin only (enforced in SecurityConfig).
    @GetMapping
    public List<Notification> getAllNotifications() {
        return notificationService.getAllNotifications();
    }

    // The actual "order tracking" feed a customer's app screen should call -
    // their own notifications, newest first.
    @GetMapping("/mine")
    public List<Notification> getMyNotifications() {
        return notificationService.getNotificationsByCustomerId(currentUser.customerId());
    }

    // Ownership verified server-side - can only mark the caller's own notification.
    @PutMapping("/{id}/read")
    public String markAsRead(@PathVariable Long id) {
        notificationService.markAsRead(id, currentUser.customerId());
        return "Marked as read";
    }

    // Only ever affects the caller's own notifications.
    @PutMapping("/read-all")
    public String markAllAsRead() {
        notificationService.markAllAsRead(currentUser.customerId());
        return "All notifications marked as read";
    }

    // Ownership verified server-side - can only delete the caller's own notification.
    @DeleteMapping("/{id}")
    public String deleteMyNotification(@PathVariable Long id) {
        notificationService.deleteOwnNotification(id, currentUser.customerId());
        return "Notification removed";
    }
}