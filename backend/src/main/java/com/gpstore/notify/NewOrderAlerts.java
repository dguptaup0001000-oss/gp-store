package com.gpstore.notify;

import com.gpstore.entity.Order;
import com.gpstore.enums.OrderStatus;
import com.gpstore.enums.PaymentStatus;
import com.gpstore.platform.ShopStaffRepository;
import com.gpstore.service.PushNotificationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tells ONE shop that ONE order has arrived.
 *
 * <h2>What this replaced, and why it had to be replaced</h2>
 *
 * <p>The previous implementation read:
 *
 * <pre>
 * List&lt;Customer&gt; admins = customerRepository.findByRole(Role.ADMIN);
 * for (Customer admin : admins) sendPush(admin.getFcmToken(), ...);
 * </pre>
 *
 * <p>That is correct in a shop. It is a data leak in a marketplace: EVERY
 * merchant on the platform holds {@code Role.ADMIN}, so an order placed at GUPT
 * SAREE pushed the customer's name and the order total to Deepak Phone Shop, to
 * GP Store Shop #1, and to every merchant onboarded afterwards. It was written
 * when there was one shop and never revisited when there were three.
 *
 * <p>The fix is not a filter bolted onto that list. It is asking a different
 * question: not "who is an admin" but "who works in the shop this order is
 * for". Those coincided exactly once, in the single-shop era.
 *
 * <h2>Where the shop id comes from</h2>
 *
 * <p>{@code order.getShopId()} - written by the server when the order was
 * created, from the cart's own shop. No client sends it here and none could:
 * this runs after commit, on a thread with no request behind it.
 *
 * <h2>Why the recipients are read fresh every time</h2>
 *
 * <p>Membership is read from live {@code shop_staff} rows at dispatch, not
 * frozen into the device registration. A manager removed from a shop this
 * morning stops hearing its orders this afternoon, with nothing to clean up -
 * and a manager added this morning starts hearing them, with nothing to
 * re-register.
 */
@Service
public class NewOrderAlerts {

    private static final Logger log = LoggerFactory.getLogger(NewOrderAlerts.class);

    private final ShopStaffRepository staff;
    private final PushRegistrationRepository registrations;
    private final PushNotificationService push;
    private final OrderAlertLedger ledger;

    public NewOrderAlerts(ShopStaffRepository staff,
                          PushRegistrationRepository registrations,
                          PushNotificationService push,
                          OrderAlertLedger ledger) {
        this.staff = staff;
        this.registrations = registrations;
        this.push = push;
        this.ledger = ledger;
    }

    /**
     * Who would be told, without telling them.
     *
     * <p>Exposed because it is the part worth testing directly: a test can
     * assert that shop A's order selects shop A's people and nobody else
     * without needing a Firebase credential that does not exist in CI.
     */
    @Transactional(readOnly = true)
    public List<PushRegistration> recipientsFor(Long shopId) {
        if (shopId == null) {
            return List.of();
        }
        List<Long> people = staff.staffIdsFor(shopId);
        if (people.isEmpty()) {
            return List.of();
        }
        return registrations.enabledFor(people, PushApp.MERCHANT_ADMIN.name());
    }

    /**
     * Announces an order to its own shop, at most once, ever.
     *
     * <p>CALLED AFTER COMMIT. The order must be a fact in the database before
     * any phone is told about it - a rollback after a "New order received" is
     * worse than a late notification, because the shop starts packing something
     * that does not exist.
     *
     * <p>Never throws. A notification failure has never been a reason to fail an
     * order and is not one here.
     */
    public void announce(Order order) {
        try {
            if (!worthAnnouncing(order)) {
                return;
            }
            Long shopId = order.getShopId();
            if (shopId == null) {
                // Not an assertion failure - an order with no shop cannot be
                // routed, and guessing a shop is the bug this class exists to
                // remove. Logged so it is visible rather than silent.
                log.warn("Order {} has no shop, so no merchant can be told about it.",
                        order.getId());
                return;
            }

            // THE CLAIM COMES BEFORE THE SEND. If this loses, somebody else is
            // already announcing this order and the only correct thing to do is
            // nothing at all.
            if (!ledger.claim(order.getId(), OrderAlertSent.NEW_ORDER, shopId)) {
                log.debug("Order {} was already announced; not announcing it again.",
                        order.getId());
                return;
            }

            List<PushRegistration> devices = recipientsFor(shopId);
            if (devices.isEmpty()) {
                log.info("Shop {} has no merchant device registered, so order {} "
                        + "was not pushed anywhere.", shopId, order.getId());
                return;
            }

            String title = "New order received";
            String body = bodyFor(order);
            Map<String, String> data = payloadFor(order, shopId);

            int reached = 0;
            for (PushRegistration device : devices) {
                PushNotificationService.PushOutcome outcome =
                        push.sendPushTo(device.getToken(), title, body, data);
                switch (outcome) {
                    case SENT -> {
                        reached++;
                        touch(device);
                    }
                    case INVALID_TOKEN -> retire(device);
                    default -> { /* SKIPPED or FAILED: leave the row alone. */ }
                }
            }
            ledger.recordRecipients(order.getId(), OrderAlertSent.NEW_ORDER, reached);
        } catch (Exception failed) {
            log.error("Could not announce order {}: {}",
                    order == null ? null : order.getId(), failed.getMessage());
        }
    }

    // ------------------------------------------------------------- internals

    /**
     * Whether this order is a real one yet.
     *
     * <p>THE FALSE ALARM THIS PREVENTS. Pressing Checkout is not placing an
     * order. An online payment that has not been verified leaves the order in
     * {@code PENDING_CONFIRMATION}, and announcing that would have a shop
     * packing goods for a payment that may never arrive - and, when it does not,
     * no way to un-say it. COD has no such gap: the order is real the moment it
     * is written.
     */
    private boolean worthAnnouncing(Order order) {
        if (order == null || order.getId() == null) {
            return false;
        }
        if (order.getOrderStatus() == OrderStatus.PENDING_CONFIRMATION) {
            return false;
        }
        return order.getOrderStatus() != OrderStatus.CANCELLED;
    }

    /** "Order #GP12345 from Deepak - Rs 520 - 6 items - Cash on delivery" */
    public String bodyFor(Order order) {
        StringBuilder body = new StringBuilder();
        String number = order.getOrderNumber();
        if (number != null && !number.isBlank()) {
            body.append("Order #").append(number.trim());
        } else {
            body.append("Order ").append(order.getId());
        }
        body.append(" from ").append(customerName(order));
        body.append(" • ₹").append(amount(order));
        int items = itemCount(order);
        if (items > 0) {
            body.append(" • ").append(items).append(items == 1 ? " item" : " items");
        }
        body.append(" • ").append(howPaid(order));
        return body.toString();
    }

    /**
     * The payload.
     *
     * <p>OPERATIONAL FACTS ONLY. No address, no phone number, no payment
     * reference, no token - a notification payload is readable on a lock screen
     * and is not a place to put anything that has to be authorised to see. The
     * app opens the order through the authenticated shop-scoped endpoint, which
     * is the only thing that decides what this merchant may read.
     *
     * <p>{@code shopId} is included so a merchant with two shops can see WHICH
     * of their shops an order landed in; it is not authority for anything, and
     * the order endpoint re-checks membership regardless of what arrives here.
     */
    public Map<String, String> payloadFor(Order order, Long shopId) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", "NEW_ORDER");
        data.put("orderId", String.valueOf(order.getId()));
        data.put("shopId", String.valueOf(shopId));
        if (order.getOrderNumber() != null) {
            data.put("orderNumber", order.getOrderNumber());
        }
        // Both spoken fields are stated by the server rather than parsed back
        // out of the body above, because recovering a name from a display
        // string breaks on the first customer whose name contains the
        // separator. orderAmount carries no symbol: the app says "rupees".
        data.put("customerName", customerName(order));
        data.put("orderAmount", amount(order));
        data.put("itemCount", String.valueOf(itemCount(order)));
        data.put("payment", howPaid(order));
        return data;
    }

    private static String customerName(Order order) {
        if (order.getCustomer() == null) {
            return "a customer";
        }
        String name = order.getCustomer().getFullName();
        return (name == null || name.isBlank()) ? "a customer" : name.trim();
    }

    /**
     * Whole rupees lose their decimals, real paise keep exactly two.
     *
     * <p>The same string is shown and spoken, and "520.5" is neither how money
     * is written nor how it is said.
     */
    static String amount(Order order) {
        BigDecimal total = order.getTotalAmount();
        if (total == null) {
            return "0";
        }
        if (total.stripTrailingZeros().scale() <= 0) {
            return total.stripTrailingZeros().toBigInteger().toString();
        }
        return total.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static int itemCount(Order order) {
        return order.getOrderItems() == null ? 0 : order.getOrderItems().size();
    }

    static String howPaid(Order order) {
        PaymentStatus status = order.getPaymentStatus();
        if (status == null) {
            return "Payment pending";
        }
        return switch (status) {
            case COD_PENDING, COD_RECEIVED -> "Cash on delivery";
            case SUCCESS -> "Online paid";
            default -> "Payment pending";
        };
    }

    private void touch(PushRegistration device) {
        try {
            device.setLastSeenAt(LocalDateTime.now());
            registrations.save(device);
        } catch (Exception ignored) {
            // Bookkeeping. Never worth failing a delivered notification over.
        }
    }

    /** A dead token stops being tried, without losing the row's history. */
    private void retire(PushRegistration device) {
        try {
            device.setEnabled(Boolean.FALSE);
            registrations.save(device);
            log.info("Retired a push registration whose token FCM no longer recognises.");
        } catch (Exception ignored) {
            // Same: best effort.
        }
    }
}
