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
}