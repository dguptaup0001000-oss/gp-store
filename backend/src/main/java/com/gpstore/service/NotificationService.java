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
import java.util.concurrent.ExecutorService;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final AuditLogService auditLogService;
    private final PushNotificationService pushNotificationService;
    private final ExecutorService orderSideEffectsExecutor;

    public NotificationService(
            NotificationRepository notificationRepository,
            CustomerRepository customerRepository,
            OrderRepository orderRepository,
            AuditLogService auditLogService,
            PushNotificationService pushNotificationService,
            ExecutorService orderSideEffectsExecutor) {

        this.notificationRepository = notificationRepository;
        this.customerRepository = customerRepository;
        this.orderRepository = orderRepository;
        this.auditLogService = auditLogService;
        this.pushNotificationService = pushNotificationService;
        this.orderSideEffectsExecutor = orderSideEffectsExecutor;
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
    // Originally loaded every customer row into memory at once and sent one
    // synchronous FCM network call per customer, inside a single request/
    // transaction - fine at a few dozen customers, but a real store with
    // thousands would time out the HTTP request and hold one DB transaction
    // open for as long as it took every push to complete. Fixed in two
    // layers: the actual push is now ONE FCM call to a topic every device
    // subscribes to on registration (see CustomerService.updateMyFcmToken /
    // PushNotificationService.ALL_CUSTOMERS_TOPIC) - O(1), not O(customer
    // count), regardless of whether there are 50 customers or 50,000. The
    // per-customer in-app Notification rows (so each customer's own
    // notification history/read-state still works exactly as before) are
    // still created by paging through active customers in bounded batches
    // rather than one giant findAll(), and that batch-save work runs on
    // orderSideEffectsExecutor so the admin's request returns immediately
    // rather than waiting on it.
    private static final int BROADCAST_PAGE_SIZE = 200;

    public int broadcastToAll(String title, String message) {
        if (title == null || title.isBlank() || message == null || message.isBlank()) {
            throw new BadRequestException("Title and message are required");
        }

        pushNotificationService.sendToTopic(
                PushNotificationService.ALL_CUSTOMERS_TOPIC, title, message, Map.of("type", "ANNOUNCEMENT"));

        // One executor task pages the whole customer list. Submitting one
        // task per 200-row page used to enqueue a burst of work onto the
        // same 4-thread/200-slot pool that also writes invoices after
        // checkout. A 20,000-customer announcement would have been 100
        // queued tasks, each holding a page of Customer entities, before
        // any of them ran.
        int totalCustomers = (int) Math.min(Integer.MAX_VALUE, customerRepository.countByActiveTrue());
        orderSideEffectsExecutor.submit(() -> {
            try {
                persistBroadcastPages(title, message);
            } catch (Exception ex) {
                auditLogService.log("BROADCAST_PERSIST_FAILED", "Notification", null, ex.getMessage());
            }
        });
        return totalCustomers;
    }

    void persistBroadcastPages(String title, String message) {
        int pageNumber = 0;
        Page<Customer> page;
        do {
            page = customerRepository.findByActiveTrue(PageRequest.of(pageNumber, BROADCAST_PAGE_SIZE));
            List<Customer> customers = page.getContent();
            if (!customers.isEmpty()) {
                saveNotificationBatch(customers, title, message);
            }
            pageNumber++;
        } while (page.hasNext());
    }

    // No @Transactional here - it would be a no-op anyway (self-invocation
    // from broadcastToAll bypasses Spring's proxy). saveAll() below is
    // already transactional on its own (Spring Data's SimpleJpaRepository
    // annotates it internally), which is the only atomicity guarantee this
    // batch actually needs.
    void saveNotificationBatch(List<Customer> customers, String title, String message) {
        LocalDateTime now = LocalDateTime.now();

        List<Notification> notifications = customers.stream().map(customer -> {
            Notification notification = new Notification();
            notification.setCustomer(customer);
            notification.setTitle(title);
            notification.setMessage(message);
            notification.setNotificationType(NotificationType.PUSH);
            notification.setNotificationStatus(NotificationStatus.PENDING);
            notification.setSentAt(now);
            notification.setActive(true);
            return notification;
        }).toList();

        // saveAll(), not one save() per customer in a loop - lets Hibernate's
        // batch_size/order_inserts config (see application.properties) group
        // these into real batched INSERT statements instead of one
        // individual round trip per row.
        notificationRepository.saveAll(notifications);
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
            case PACKED -> {
                // EXACTLY THIS, and nothing more. A worker scanning a packed
                // order is recording accountability inside the shop - the
                // order has not left the counter. "Ready for delivery",
                // "picked up", "on the way" and "your delivery partner has
                // your order" are all promises about a journey that has not
                // started, and a customer who reads one of them starts waiting
                // at the door. The order number is deliberately left out: the
                // brief specifies this sentence and only this sentence.
                title = "Order Packed";
                message = "\uD83D\uDCE6 Your order is packed.";
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
     * The store-owner-facing counterpart to notifyOrderStatusChange above -
     * every ADMIN account gets pushed the instant a new order is placed,
     * not just the customer who placed it. type=NEW_ORDER (distinct from
     * ORDER_STATUS) is what the admin app's PushNotificationService uses to
     * trigger an auto-print of the order receipt on a connected thermal
     * printer - see printer_service.dart. Called once, from placeOrder's
     * afterCommitWork, never on later status changes (an admin doesn't need
     * a fresh print for every status update, only when the order first
     * arrives). Same defensive isolation as every other method here: never
     * lets a notification failure affect the order itself.
     */
    public void notifyAdminsOfNewOrder(Order order) {
        try {
            if (order == null) return;
            if (order.getOrderStatus() == com.gpstore.enums.OrderStatus.PENDING_CONFIRMATION) {
                return;
            }

            List<Customer> admins = customerRepository.findByRole(com.gpstore.entity.Role.ADMIN);
            if (admins.isEmpty()) return;

            // The shop counter cares about two things when an order lands:
            // who it is for, and how much. Order number and status were what
            // this used to lead with, and neither is what someone glancing at
            // a phone across a counter needs.
            String customerName = displayNameOf(order);
            String amount = plainAmountOf(order);

            String title = "New order received from " + customerName;
            String message = "Order amount ₹" + amount;

            // customerName and orderAmount are sent as their own data fields,
            // NOT parsed back out of the title and body above. The shop app
            // speaks this order aloud (see VoiceAnnouncementService), and
            // recovering a name from a display string is exactly the kind of
            // thing that breaks the day someone's name contains the word the
            // parser splits on. The backend is the source of truth for both,
            // so it states both.
            //
            // orderAmount carries no currency symbol and no grouping - it is
            // a number for a machine to read, and the app is what turns it
            // into "520 rupees". A ₹ in this field would be spoken literally.
            Map<String, String> data = Map.of(
                    "type", "NEW_ORDER",
                    "orderId", String.valueOf(order.getId()),
                    "customerName", customerName,
                    "orderAmount", amount);

            for (Customer admin : admins) {
                if (admin.getFcmToken() == null || admin.getFcmToken().isBlank()) continue;
                pushNotificationService.sendPush(admin.getFcmToken(), title, message, data);
            }
        } catch (Exception ex) {
            auditLogService.log("ADMIN_NEW_ORDER_PUSH_FAILED", "Order", order != null ? order.getId() : null,
                    "Failed to notify admins of new order: " + ex.getMessage());
        }
    }

    /**
     * The customer's name as the shop should see and hear it.
     *
     * Falls back to "a customer" rather than an empty string or a null: the
     * announcement is spoken aloud, and "New order received from ." is worse
     * than a generic word. An OTP-only account can legitimately have no name
     * yet, so this is a real case rather than defensive padding.
     *
     * order.getCustomer() is safe to touch here without a session: this runs
     * from placeOrder's after-commit callback, where the customer was
     * assigned from a fully-loaded entity rather than a lazy proxy - see the
     * comment on afterCommitWork in OrderService.
     */
    private String displayNameOf(Order order) {
        Customer customer = order.getCustomer();
        if (customer == null) {
            return "a customer";
        }
        String name = customer.getFullName();
        return (name == null || name.isBlank()) ? "a customer" : name.trim();
    }

    /**
     * The final payable amount, as a plain number with no symbol.
     *
     * Trailing zeros are stripped so a whole-rupee order reads "520" rather
     * than "520.00" - the app speaks this, and "five hundred and twenty point
     * zero zero" is not how a shopkeeper hears a total. Genuine paise
     * survive: 520.50 stays "520.50" for the app to voice as rupees and
     * paise.
     *
     * This is the ORDER's own total as the backend computed it - discounts,
     * delivery fee and tax already applied - never a client-supplied figure.
     */
    private String plainAmountOf(Order order) {
        java.math.BigDecimal total = order.getTotalAmount();
        if (total == null) {
            return "0";
        }
        // Whole rupees lose the decimals entirely; anything with paise keeps
        // exactly two.
        //
        // Not a plain stripTrailingZeros: that turns 780.50 into "780.5",
        // which the notification body then shows as "₹780.5" - money is not
        // written that way, and the same field feeds both the banner and the
        // spoken line. Two decimals or none is the only pair of forms that
        // reads correctly in both places.
        //
        // remainder rather than scale(): a BigDecimal of "520.00" has scale 2
        // but no actual paise, and it should read "520".
        if (total.stripTrailingZeros().scale() <= 0) {
            // toBigInteger avoids the scientific notation stripTrailingZeros
            // produces for round thousands (1000 becomes 1E+3), which would be
            // both wrong on screen and unspeakable.
            return total.toBigInteger().toString();
        }
        return total.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
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

    /**
     * Admin feed. Returns DTOs for the same reason the customer feed does
     * (see NotificationResponse): a raw entity page leaves Jackson resolving
     * lazy proxies during response serialization, which fails as an opaque
     * 500 rather than anything actionable. Pagination stays database-side -
     * findAll(Pageable) issues its own LIMIT/OFFSET, and the mapping happens
     * per page, never over the full table.
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Page<com.gpstore.dto.response.NotificationResponse> getAllNotifications(
            org.springframework.data.domain.Pageable pageable) {
        return notificationRepository.findAll(pageable)
                .map(com.gpstore.dto.response.NotificationResponse::from);
    }

    public Optional<Notification> getNotificationById(Long id) {
        return notificationRepository.findById(id);
    }

    /**
     * Paginated - every order status change writes a notification, so this
     * grows without bound over a customer's lifetime.
     *
     * Returns DTOs, not entities: each row's lazy Order proxy is resolved
     * here, inside the transaction, instead of being left for Jackson to
     * touch while writing the response (see NotificationResponse's class
     * comment for why that was failing). readOnly because this only reads -
     * it also keeps the whole page mapping inside one open session.
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Page<com.gpstore.dto.response.NotificationResponse> getNotificationsByCustomerId(
            Long customerId, org.springframework.data.domain.Pageable pageable) {
        return notificationRepository.findByCustomerIdOrderBySentAtDesc(customerId, pageable)
                .map(com.gpstore.dto.response.NotificationResponse::from);
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

    /**
     * Paginated rather than returning every notification an order has ever
     * generated - one is written per status change, so this grows with the
     * order's history. Currently unused by any endpoint; bounded now so it
     * cannot become an unbounded load the first time something wires it up.
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Page<com.gpstore.dto.response.NotificationResponse> getNotificationsByOrderId(
            Long orderId, org.springframework.data.domain.Pageable pageable) {
        return notificationRepository.findByOrderId(orderId, pageable)
                .map(com.gpstore.dto.response.NotificationResponse::from);
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
    /**
     * One UPDATE, executed in the database.
     *
     * Previously loaded every notification the customer had ever received
     * into JVM memory, set a flag on each, and saved them back - unbounded
     * memory and one UPDATE per row, both growing for the life of the
     * account. A customer with 20k notifications made this a 20k-entity
     * load and 20k statements to set a single boolean.
     *
     * @return how many rows actually changed - 0 when everything was
     *         already read, which the caller can surface instead of
     *         implying work happened.
     */
    @org.springframework.transaction.annotation.Transactional
    public int markAllAsRead(Long customerId) {
        return notificationRepository.markAllAsReadForCustomer(customerId);
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