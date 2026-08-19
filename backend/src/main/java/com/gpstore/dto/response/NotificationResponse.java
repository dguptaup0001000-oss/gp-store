package com.gpstore.dto.response;

import com.gpstore.entity.Notification;
import com.gpstore.entity.Order;

import java.time.LocalDateTime;

/**
 * The customer-facing shape of a notification. Exists because
 * GET /api/notifications/mine used to return the raw {@link Notification}
 * entity straight out of the repository - the same DTO-leak pattern already
 * fixed for OrderController and DeliveryController, which this endpoint was
 * missed by.
 *
 * Returning the entity meant Jackson walked live Hibernate proxies during
 * response serialization: Notification.order is a LAZY @ManyToOne, and
 * Order.address is another LAZY @ManyToOne with no @JsonIgnore on it. Any
 * failure to initialize one of those proxies mid-serialization (a deleted
 * address row still referenced by an old order, a session already closed,
 * an entity that no longer resolves) surfaces as a 500 from
 * GlobalExceptionHandler - "An unexpected error occurred" - rather than as
 * anything actionable, and it fails for the whole page even if a single row
 * is affected.
 *
 * Mapping to a flat DTO inside the service's transaction removes that
 * entire failure class: every field is read while the session is
 * unambiguously open, and nothing lazy is left for the serializer to touch.
 * It also stops the endpoint's payload from silently growing whenever a new
 * field is added to the entity.
 *
 * The nested order stays deliberately minimal (id/orderNumber/status) -
 * exactly what the app's NotificationOrderRef model consumes to link back to
 * an order, not the order's full contents.
 */
public class NotificationResponse {

    private final Long id;
    private final String title;
    private final String message;
    private final LocalDateTime sentAt;
    private final Boolean isRead;
    private final OrderRef order;

    public NotificationResponse(Long id, String title, String message,
                                LocalDateTime sentAt, Boolean isRead, OrderRef order) {
        this.id = id;
        this.title = title;
        this.message = message;
        this.sentAt = sentAt;
        this.isRead = isRead;
        this.order = order;
    }

    /**
     * Must be called with the persistence context still open (the service
     * method that calls this is @Transactional(readOnly = true)) - touching
     * notification.getOrder() is what initializes the lazy proxy, and doing
     * it here rather than in the serializer is the entire point.
     */
    public static NotificationResponse from(Notification notification) {
        Order order = notification.getOrder();

        return new NotificationResponse(
                notification.getId(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getSentAt(),
                // Older rows predate the is_read column and can be null;
                // the app models this as a non-nullable bool, so normalize
                // here instead of letting a null reach it.
                Boolean.TRUE.equals(notification.getIsRead()),
                order != null ? OrderRef.from(order) : null);
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    /**
     * Named getIsRead (not isRead) on purpose - Jackson would serialize a
     * boolean isRead() accessor as "read", and the app's AppNotification
     * model reads the key "isRead", matching what the raw entity used to
     * emit. Renaming it here would silently break the read/unread state.
     */
    public Boolean getIsRead() {
        return isRead;
    }

    public OrderRef getOrder() {
        return order;
    }

    /** Just enough of an order to navigate to it from a notification. */
    public static class OrderRef {

        private final Long id;
        private final String orderNumber;
        private final String orderStatus;

        public OrderRef(Long id, String orderNumber, String orderStatus) {
            this.id = id;
            this.orderNumber = orderNumber;
            this.orderStatus = orderStatus;
        }

        public static OrderRef from(Order order) {
            return new OrderRef(
                    order.getId(),
                    order.getOrderNumber(),
                    order.getOrderStatus() != null ? order.getOrderStatus().name() : null);
        }

        public Long getId() {
            return id;
        }

        public String getOrderNumber() {
            return orderNumber;
        }

        public String getOrderStatus() {
            return orderStatus;
        }
    }
}
