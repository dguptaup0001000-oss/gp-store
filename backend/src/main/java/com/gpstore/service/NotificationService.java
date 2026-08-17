package com.gpstore.service;

import com.gpstore.entity.Notification;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Delivery;
import com.gpstore.entity.DeliveryPartner;
import com.gpstore.entity.Order;
import com.gpstore.enums.NotificationStatus;
import com.gpstore.enums.NotificationType;
import com.gpstore.enums.OrderStatus;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.NotificationRepository;
import com.gpstore.repository.OrderRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final AuditLogService auditLogService;
    private final PushNotificationService pushNotificationService;

    public NotificationService(
            NotificationRepository notificationRepository,
            CustomerRepository customerRepository,
            OrderRepository orderRepository,
            AuditLogService auditLogService,
            PushNotificationService pushNotificationService) {

        this.notificationRepository = notificationRepository;
        this.customerRepository = customerRepository;
        this.orderRepository = orderRepository;
        this.auditLogService = auditLogService;
        this.pushNotificationService = pushNotificationService;
    }

    public Notification sendNotification(Notification notification) {

        if (notification == null) {
            throw new BadRequestException("Notification cannot be null");
        }

        if (notification.getCustomer() == null || notification.getCustomer().getId() == null) {
            throw new BadRequestException("Customer ID is required");
        }

        Customer customer = customerRepository.findById(notification.getCustomer().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        notification.setCustomer(customer);

        if (notification.getOrder() != null && notification.getOrder().getId() != null) {
            Order order = orderRepository.findById(notification.getOrder().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
            notification.setOrder(order);
        }

        if (notification.getNotificationStatus() == null) {
            notification.setNotificationStatus(NotificationStatus.PENDING);
        }

        if (notification.getSentAt() == null) {
            notification.setSentAt(LocalDateTime.now());
        }

        if (notification.getActive() == null) {
            notification.setActive(true);
        }

        return notificationRepository.save(notification);
    }

    /**
     * Store-wide announcement (e.g. "New Year Sale!") - didn't exist before,
     * only single-customer notification did. Only reaches active accounts -
     * a deactivated account shouldn't be pinged with new content. Creates
     * one real notification row per customer (not a single shared one),
     * consistent with how every other notification in this app works -
     * each customer can independently mark their own copy read/delete it
     * without affecting anyone else's.
     */
    @org.springframework.transaction.annotation.Transactional
    public int broadcastToAll(String title, String message) {
        if (title == null || title.isBlank() || message == null || message.isBlank()) {
            throw new BadRequestException("Title and message are required");
        }

        List<Customer> activeCustomers = customerRepository.findAll().stream()
                .filter(c -> Boolean.TRUE.equals(c.getActive()))
                .toList();

        LocalDateTime now = LocalDateTime.now();

        for (Customer customer : activeCustomers) {
            Notification notification = new Notification();
            notification.setCustomer(customer);
            notification.setTitle(title);
            notification.setMessage(message);
            notification.setNotificationType(NotificationType.PUSH);
            notification.setNotificationStatus(NotificationStatus.PENDING);
            notification.setSentAt(now);
            notification.setActive(true);
            notificationRepository.save(notification);

            pushNotificationService.sendPush(customer.getFcmToken(), title, message, Map.of("type", "ANNOUNCEMENT"));
        }

        return activeCustomers.size();
    }

    /**
     * The actual "real-time order tracking" trigger - called automatically by
     * OrderService/DeliveryService whenever a status changes, instead of
     * requiring someone to manually POST a notification every time. Creates
     * the in-app record AND sends a real FCM push (via
     * PushNotificationService) if the customer has a registered device
     * token and push is configured - see application.properties'
     * firebase.push-enabled for why this can be a silent no-op until a real
     * Firebase project is set up.
     */
    public void notifyOrderStatusChange(Order order, OrderStatus status) {
        // Defensive isolation - this is called from every critical order
        // flow (placement, cancellation, every status change), all of which
        // are @Transactional. This method has no known failure mode today,
        // but if creating a notification record ever did throw for any
        // reason, without this it would currently roll back whichever
        // critical business operation triggered it - a customer's paid
        // order failing because of an unrelated notification bug would be
        // a much worse outcome than the customer simply not getting
        // notified this one time.
        try {
            notifyOrderStatusChangeInternal(order, status);
        } catch (Exception ex) {
            auditLogService.log("NOTIFICATION_FAILED", "Order", order != null ? order.getId() : null,
                    "Failed to create status-change notification: " + ex.getMessage());
        }
    }

    private void notifyOrderStatusChangeInternal(Order order, OrderStatus status) {
        String title;
        String message;

        switch (status) {
            case PENDING_CONFIRMATION -> {
                title = "Order Placed";
                message = "Your order " + order.getOrderNumber() + " has been placed successfully.";
            }
            case CONFIRMED -> {
                title = "Order Confirmed";
                message = "Your order " + order.getOrderNumber() + " has been confirmed.";
            }
            case PACKING -> {
                title = "Order Being Packed";
                message = "Your order " + order.getOrderNumber() + " is being packed.";
            }
            case READY_TO_DISPATCH -> {
                title = "Order Ready";
                message = "Your order " + order.getOrderNumber() + " is ready for dispatch.";
            }
            case OUT_FOR_DELIVERY -> {
                title = "Out for Delivery";
                message = "Your order " + order.getOrderNumber() + " is out for delivery.";
            }
            case DELIVERED -> {
                title = "Order Delivered";
                message = "Your order " + order.getOrderNumber() + " has been delivered. Enjoy!";
            }
            case CANCELLED -> {
                title = "Order Cancelled";
                message = "Your order " + order.getOrderNumber() + " has been cancelled.";
            }
            default -> {
                title = "Order Update";
                message = "Your order " + order.getOrderNumber() + " status changed to " + status + ".";
            }
        }

        Notification notification = new Notification();
        notification.setCustomer(order.getCustomer());
        notification.setOrder(order);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setNotificationType(NotificationType.PUSH);
        notification.setNotificationStatus(NotificationStatus.PENDING);
        notification.setSentAt(LocalDateTime.now());
        notification.setActive(true);

        notificationRepository.save(notification);

        String fcmToken = order.getCustomer() != null ? order.getCustomer().getFcmToken() : null;
        pushNotificationService.sendPush(fcmToken, title, message,
                Map.of("type", "ORDER_STATUS", "orderId", String.valueOf(order.getId())));
    }

    /**
     * A delivery partner's own push - "you have a new delivery" - the
     * natural counterpart to the customer-facing notifications above.
     * Called from DeliveryService.assignDelivery(). Same defensive
     * isolation as notifyOrderStatusChange: a notification failure must
     * never roll back a real delivery assignment, so every exception is
     * caught here, never rethrown.
     */
    public void notifyPartnerNewAssignment(DeliveryPartner partner, Delivery delivery) {
        try {
            if (partner == null || partner.getAccount() == null) {
                return;
            }

            Order order = delivery.getOrder();
            String title = "New Delivery Assigned";
            String message = order != null && order.getOrderNumber() != null
                    ? "You have a new delivery for order " + order.getOrderNumber() + "."
                    : "You have a new delivery.";

            Notification notification = new Notification();
            notification.setCustomer(partner.getAccount());
            notification.setOrder(order);
            notification.setTitle(title);
            notification.setMessage(message);
            notification.setNotificationType(NotificationType.PUSH);
            notification.setNotificationStatus(NotificationStatus.PENDING);
            notification.setSentAt(LocalDateTime.now());
            notification.setActive(true);
            notificationRepository.save(notification);

            pushNotificationService.sendPush(partner.getAccount().getFcmToken(), title, message,
                    Map.of("type", "NEW_ASSIGNMENT", "deliveryId", String.valueOf(delivery.getId())));
        } catch (Exception ex) {
            auditLogService.log("NOTIFICATION_FAILED", "Delivery", delivery != null ? delivery.getId() : null,
                    "Failed to create new-assignment notification: " + ex.getMessage());
        }
    }

    public org.springframework.data.domain.Page<Notification> getAllNotifications(
            org.springframework.data.domain.Pageable pageable) {
        return notificationRepository.findAll(pageable);
    }

    public Optional<Notification> getNotificationById(Long id) {
        return notificationRepository.findById(id);
    }

    /** Paginated - every order status change writes a notification, so this grows without bound over a customer's lifetime. */
    public org.springframework.data.domain.Page<Notification> getNotificationsByCustomerId(
            Long customerId, org.springframework.data.domain.Pageable pageable) {
        return notificationRepository.findByCustomerIdOrderBySentAtDesc(customerId, pageable);
    }

    /**
     * A dedicated count query instead of paging through every notification -
     * needed now that getNotificationsByCustomerId only returns one page at a
     * time, so the unread badge can no longer be derived client-side from a
     * full in-memory list.
     */
    public long getUnreadCount(Long customerId) {
        return notificationRepository.countByCustomerIdAndIsReadFalse(customerId);
    }

    public List<Notification> getNotificationsByOrderId(Long orderId) {
        return notificationRepository.findByOrderId(orderId);
    }

    /** Ownership-checked - a customer marking one of THEIR OWN notifications as read. */
    public void markAsRead(Long notificationId, Long customerId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));

        if (notification.getCustomer() == null || !notification.getCustomer().getId().equals(customerId)) {
            throw new ResourceNotFoundException("Notification not found");
        }

        notification.setIsRead(true);
        notificationRepository.save(notification);
    }

    /** Only ever touches the caller's own notifications - never a client-supplied customer id. */
    public void markAllAsRead(Long customerId) {
        List<Notification> notifications = notificationRepository.findByCustomerId(customerId);
        for (Notification notification : notifications) {
            notification.setIsRead(true);
        }
        notificationRepository.saveAll(notifications);
    }

    /**
     * Ownership-checked delete - a customer removing one of THEIR OWN
     * notifications from their own list. Distinct from the existing bare
     * deleteNotification(id) above, which has no ownership check at all and
     * is deliberately left as-is rather than reused here (it isn't wired to
     * any endpoint currently, so it poses no risk sitting unused).
     */
    public void deleteOwnNotification(Long notificationId, Long customerId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));

        if (notification.getCustomer() == null || !notification.getCustomer().getId().equals(customerId)) {
            throw new ResourceNotFoundException("Notification not found");
        }

        notificationRepository.delete(notification);
    }

    public void deleteNotification(Long id) {
        notificationRepository.deleteById(id);
    }
}