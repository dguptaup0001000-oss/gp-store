package com.gpstore.service;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Order;
import com.gpstore.entity.Role;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The NEW_ORDER push is now a CONTRACT, not just a banner: the shop app
 * speaks customerName and orderAmount aloud, so their exact shape matters.
 *
 * These assert the data fields rather than the title and body, because those
 * two fields are what gets voiced. A ₹ or a "520.00" leaking into
 * orderAmount is not a cosmetic bug - it is the shop hearing "rupee symbol
 * five two zero point zero zero".
 */
class NewOrderAnnouncementPayloadTest {

    private final PushNotificationService push = mock(PushNotificationService.class);
    private final CustomerRepository customerRepository = mock(CustomerRepository.class);
    private final AuditLogService auditLog = mock(AuditLogService.class);

    private final NotificationService service = new NotificationService(
            mock(NotificationRepository.class),
            customerRepository,
            mock(com.gpstore.repository.OrderRepository.class),
            auditLog,
            push,
            // Direct executor: side-effect work runs inline so the assertions
            // below see it, rather than racing a background thread.
            java.util.concurrent.Executors.newSingleThreadExecutor());

    @Test
    @DisplayName("The push carries the customer's real name and a speakable amount")
    void payloadCarriesNameAndPlainAmount() {
        Map<String, String> data = capturePayloadFor("Rahul", new BigDecimal("350.00"));

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
        Map<String, String> data = capturePayloadFor("Priya", new BigDecimal("780.50"));
        assertEquals("780.50", data.get("orderAmount"));
    }

    @Test
    @DisplayName("A round thousand does not become scientific notation")
    void roundThousandStaysReadable() {
        // stripTrailingZeros turns 1000 into 1E+3, which is both wrong on
        // screen and unspeakable. This is the case that catches it.
        Map<String, String> data = capturePayloadFor("Anita", new BigDecimal("1000.00"));
        assertEquals("1000", data.get("orderAmount"));
    }

    @Test
    @DisplayName("A nameless account still produces a sayable announcement")
    void missingNameFallsBackToAGenericWord() {
        // OTP-only accounts can legitimately have no name yet. "New order
        // received from ." is worse than a generic word.
        Map<String, String> blank = capturePayloadFor("   ", new BigDecimal("120"));
        assertEquals("a customer", blank.get("customerName"));

        Map<String, String> nul = capturePayloadFor(null, new BigDecimal("120"));
        assertEquals("a customer", nul.get("customerName"));
    }

    @Test
    @DisplayName("The visible notification names the customer and the amount")
    void titleAndBodyMatchTheSpokenOrder() {
        givenOneAdminWithADevice();
        service.notifyAdminsOfNewOrder(orderFor("Deepak", new BigDecimal("520")));

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(push).sendPush(anyString(), title.capture(), body.capture(), anyMap());

        assertEquals("New order received from Deepak", title.getValue());
        assertEquals("Order amount ₹520", body.getValue());
    }

    @Test
    @DisplayName("Admins without a device token are skipped, not sent to")
    void adminsWithoutTokensAreSkipped() {
        Customer noToken = new Customer();
        noToken.setFullName("Admin");
        noToken.setRole(Role.ADMIN);
        when(customerRepository.findByRole(Role.ADMIN)).thenReturn(List.of(noToken));

        service.notifyAdminsOfNewOrder(orderFor("Rahul", new BigDecimal("100")));

        verify(push, never()).sendPush(any(), any(), any(), any());
    }

    private Map<String, String> capturePayloadFor(String customerName, BigDecimal total) {
        clearInvocations(push);
        givenOneAdminWithADevice();
        service.notifyAdminsOfNewOrder(orderFor(customerName, total));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(push).sendPush(anyString(), anyString(), anyString(), captor.capture());
        return captor.getValue();
    }

    /**
     * Stubs one admin holding a device token. Kept OUT of orderFor: an
     * earlier version stubbed the admin list there, so the "admin has no
     * token" test had its own setup silently overwritten by the helper and
     * passed for the wrong reason.
     */
    private void givenOneAdminWithADevice() {
        Customer admin = new Customer();
        admin.setFullName("Shop Owner");
        admin.setRole(Role.ADMIN);
        admin.setFcmToken("device-token");
        when(customerRepository.findByRole(Role.ADMIN)).thenReturn(List.of(admin));
    }

    private Order orderFor(String customerName, BigDecimal total) {
        Customer buyer = new Customer();
        buyer.setId(7L);
        buyer.setFullName(customerName);

        // Order has no id setter - JPA assigns it. Not needed here: these
        // assertions are about the spoken fields, and orderId is only
        // checked for presence.
        Order order = new Order();
        order.setOrderNumber("GP20260820000042");
        order.setCustomer(buyer);
        order.setTotalAmount(total);
        return order;
    }
}
