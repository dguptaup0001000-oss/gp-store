package com.gpstore.service;

import com.gpstore.entity.Notification;
import com.gpstore.entity.Customer;
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
import java.util.Optional;

import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;

    public NotificationService(
            NotificationRepository notificationRepository,
            CustomerRepository customerRepository,
            OrderRepository orderRepository) {

        this.notificationRepository = notificationRepository;
        this.customerRepository = customerRepository;
        this.orderRepository = orderRepository;
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
     * The actual "real-time order tracking" trigger - called automatically by
     * OrderService/DeliveryService whenever a status changes, instead of
     * requiring someone to manually POST a notification every time. This only
     * creates the in-app record (status PENDING); wiring an actual SMS/email/
     * push provider (Twilio, FCM, etc.) is a separate integration step with
     * real API keys, not something to fake here.
     */
    public void notifyOrderStatusChange(Order order, OrderStatus status) {
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
    }

    public List<Notification> getAllNotifications() {
        return notificationRepository.findAll();
    }

    public Optional<Notification> getNotificationById(Long id) {
        return notificationRepository.findById(id);
    }

    public List<Notification> getNotificationsByCustomerId(Long customerId) {
        return notificationRepository.findByCustomerIdOrderBySentAtDesc(customerId);
    }

    public List<Notification> getNotificationsByOrderId(Long orderId) {
        return notificationRepository.findByOrderId(orderId);
    }

    public void deleteNotification(Long id) {
        notificationRepository.deleteById(id);
    }
}