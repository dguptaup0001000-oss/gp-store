package com.gpstore.controller;

import com.gpstore.entity.Notification;
import com.gpstore.security.CurrentUser;
import com.gpstore.service.NotificationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
    public Page<Notification> getAllNotifications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        return notificationService.getAllNotifications(pageable);
    }

    // The actual "order tracking" feed a customer's app screen should call -
    // their own notifications, newest first.
    @GetMapping("/mine")
    public Page<Notification> getMyNotifications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return notificationService.getNotificationsByCustomerId(currentUser.customerId(), pageable);
    }

    // Lightweight badge-count query - doesn't require paging through the
    // customer's full notification history just to count unread ones.
    @GetMapping("/unread-count")
    public long getUnreadCount() {
        return notificationService.getUnreadCount(currentUser.customerId());
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