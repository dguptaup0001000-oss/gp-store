package com.gpstore.service;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Order;
import com.gpstore.entity.OrderItem;
import com.gpstore.enums.PaymentStatus;
import com.gpstore.notify.NewOrderAlerts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The NEW_ORDER push is a CONTRACT, not just a banner: the shop app speaks
 * customerName and orderAmount aloud, so their exact shape matters.
 *
 * <p>These assert the data fields rather than the title and body, because those
 * two fields are what gets voiced. A ₹ or a "520.00" leaking into
 * {@code orderAmount} is not a cosmetic bug - it is the shop hearing "rupee
 * symbol five two zero point zero zero".
 *
 * <h2>Why these moved from NotificationService to NewOrderAlerts</h2>
 *
 * <p>Composing the message and choosing who receives it used to be the same
 * method, and the choosing half was {@code findByRole(ADMIN)} - every merchant
 * on the platform. Splitting them is what let the routing be fixed; the money
 * and name rules below are unchanged and are asserted here against the class
 * that now owns them. Who actually receives an alert is asserted separately,
 * against a real database, in {@code EachShopHearsOnlyItsOwnOrdersTest}.
 */
class NewOrderAnnouncementPayloadTest {

    private final NewOrderAlerts alerts = new NewOrderAlerts(
            mock(com.gpstore.platform.ShopStaffRepository.class),
            mock(com.gpstore.notify.PushRegistrationRepository.class),
            mock(PushNotificationService.class),
            mock(com.gpstore.notify.OrderAlertLedger.class));

    @Test
    @DisplayName("The push carries the customer's real name and a speakable amount")
    void payloadCarriesNameAndPlainAmount() {
        Map<String, String> data = payloadFor("Rahul", new BigDecimal("350.00"));

        assertEquals("NEW_ORDER", data.get("type"));
        assertTrue(data.containsKey("orderId"),
                "The app fetches the order by this id to print the receipt");
        assertEquals("Rahul", data.get("customerName"));
        assertEquals("350", data.get("orderAmount"),
                "Trailing zeros must be stripped - the app speaks this, and "
                        + "\"350 point zero zero\" is not how a total is heard");
        assertFalse(data.get("orderAmount").contains("₹"),
                "A currency symbol here would be spoken literally");
    }

    @Test
    @DisplayName("Genuine paise survive rather than being rounded away")
    void paiseArePreserved() {
        assertEquals("780.50", payloadFor("Priya", new BigDecimal("780.50")).get("orderAmount"));
    }

    @Test
    @DisplayName("A round thousand does not become scientific notation")
    void roundThousandStaysReadable() {
        // stripTrailingZeros turns 1000 into 1E+3, which is both wrong on
        // screen and unspeakable. This is the case that catches it.
        assertEquals("1000", payloadFor("Anita", new BigDecimal("1000.00")).get("orderAmount"));
    }

    @Test
    @DisplayName("A nameless account still produces a sayable announcement")
    void missingNameFallsBackToAGenericWord() {
        // OTP-only accounts can legitimately have no name yet. "New order
        // received from ." is worse than a generic word.
        assertEquals("a customer", payloadFor("   ", new BigDecimal("120")).get("customerName"));
        assertEquals("a customer", payloadFor(null, new BigDecimal("120")).get("customerName"));
    }

    @Test
    @DisplayName("The banner says which order, from whom, how much, how many and how paid")
    void theBodyIsOperational() {
        Order order = orderFor("Deepak", new BigDecimal("520"));
        order.setPaymentStatus(PaymentStatus.SUCCESS);
        withItems(order, 6);

        String body = alerts.bodyFor(order);

        assertTrue(body.contains("GP20260820000042"), "which order: " + body);
        assertTrue(body.contains("Deepak"), "from whom: " + body);
        assertTrue(body.contains("₹520"), "how much - and HERE the symbol belongs: " + body);
        assertTrue(body.contains("6 items"), "how many: " + body);
        assertTrue(body.contains("Online paid"), "how paid: " + body);
    }

    @Test
    @DisplayName("Cash on delivery reads as cash on delivery, not as paid")
    void codIsNotPaid() {
        Order cod = orderFor("Rahul", new BigDecimal("520"));
        cod.setPaymentStatus(PaymentStatus.COD_PENDING);
        withItems(cod, 1);

        String body = alerts.bodyFor(cod);
        assertTrue(body.contains("Cash on delivery"),
                "a counter that believes an unpaid order is paid hands over goods for nothing: "
                        + body);
        assertFalse(body.contains("Online paid"), body);
        assertTrue(body.contains("1 item") && !body.contains("1 items"),
                "one item is singular: " + body);
    }

    @Test
    @DisplayName("The payload carries no address, phone, or payment reference")
    void nothingSensitiveRidesAlong() {
        Map<String, String> data = payloadFor("Rahul", new BigDecimal("100"));
        // A notification payload is readable on a lock screen. Everything the
        // merchant needs beyond this comes from the authenticated order
        // endpoint, which is the only thing that decides what they may see.
        for (String key : data.keySet()) {
            assertFalse(key.toLowerCase().contains("address"), key);
            assertFalse(key.toLowerCase().contains("phone"), key);
            assertFalse(key.toLowerCase().contains("token"), key);
            assertFalse(key.toLowerCase().contains("payment_id"), key);
        }
        assertEquals(
                java.util.Set.of("type", "orderId", "shopId", "orderNumber",
                        "customerName", "orderAmount", "itemCount", "payment"),
                data.keySet(),
                "the payload is a closed list on purpose - anything added here "
                        + "is visible on a locked phone");
    }

    @Test
    @DisplayName("The shop is named so a two-shop merchant knows which counter")
    void theShopIsIdentified() {
        assertEquals("42", payloadFor("Rahul", new BigDecimal("100")).get("shopId"));
    }

    // -------------------------------------------------------------- helpers

    private Map<String, String> payloadFor(String customerName, BigDecimal total) {
        return alerts.payloadFor(orderFor(customerName, total), 42L);
    }

    private static void withItems(Order order, int howMany) {
        List<OrderItem> items = new ArrayList<>();
        for (int i = 0; i < howMany; i++) {
            items.add(new OrderItem());
        }
        order.setOrderItems(items);
    }

    private Order orderFor(String customerName, BigDecimal total) {
        Customer buyer = new Customer();
        buyer.setId(7L);
        buyer.setFullName(customerName);

        Order order = new Order();
        order.setOrderNumber("GP20260820000042");
        order.setCustomer(buyer);
        order.setTotalAmount(total);
        return order;
    }
}
