package com.gpstore.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpstore.dto.response.NotificationResponse;
import com.gpstore.entity.Address;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Notification;
import com.gpstore.entity.Order;
import com.gpstore.enums.OrderStatus;
import com.gpstore.repository.AddressRepository;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.NotificationRepository;
import com.gpstore.repository.OrderRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GET /api/notifications/mine returned a raw Page&lt;Notification&gt; entity
 * until now, which made Jackson walk live Hibernate proxies while writing
 * the response - Notification.order is a LAZY @ManyToOne, and Order.address
 * behind it is another one with no @JsonIgnore. That serialization happens
 * after the service call returns, so any proxy that fails to initialize at
 * that point produces an opaque 500 ("An unexpected error occurred") for the
 * entire page rather than a usable error, and it does so only against real
 * data - which is exactly how it reached production unnoticed.
 *
 * These tests pin both halves of the fix:
 *
 * 1. The service hands back fully-materialized DTOs, so nothing lazy is left
 *    for the serializer. Serializing the result with a plain ObjectMapper -
 *    no Hibernate session involved at all - is the assertion: it only
 *    succeeds if every value was already resolved inside the transaction.
 * 2. The emitted JSON keys still match what the Flutter app's
 *    AppNotification/NotificationOrderRef models parse. Renaming a field
 *    here (isRead -> read is the easy accident, since Jackson derives that
 *    from the getter name) would break the app silently, with the endpoint
 *    still returning a perfectly valid 200.
 */
@SpringBootTest
class NotificationSerializationTest {

    @Autowired private NotificationService notificationService;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private AddressRepository addressRepository;
    @Autowired private OrderRepository orderRepository;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules();

    @Test
    void notificationsPageSerializesWithoutAnOpenSession() throws Exception {
        Customer customer = newCustomer("serialize-test");
        Order order = newOrder(customer);

        Notification withOrder = new Notification();
        withOrder.setCustomer(customer);
        withOrder.setOrder(order);
        withOrder.setTitle("Order confirmed");
        withOrder.setMessage("Your order has been placed.");
        withOrder.setSentAt(LocalDateTime.now());
        withOrder.setIsRead(false);
        withOrder.setActive(true);
        notificationRepository.save(withOrder);

        Page<NotificationResponse> page = notificationService
                .getNotificationsByCustomerId(customer.getId(), PageRequest.of(0, 20));

        assertEquals(1, page.getTotalElements());

        // The real assertion: this runs outside the service transaction, so
        // it throws if anything in the DTO is still an uninitialized proxy.
        String json = MAPPER.writeValueAsString(page.getContent());
        JsonNode first = MAPPER.readTree(json).get(0);

        // Exact key names the Flutter AppNotification model parses.
        assertTrue(first.has("id"), "app model requires 'id'");
        assertEquals("Order confirmed", first.get("title").asText());
        assertEquals("Your order has been placed.", first.get("message").asText());
        assertTrue(first.has("sentAt"), "app model requires 'sentAt'");
        assertTrue(first.has("isRead"),
                "app model reads 'isRead' - a boolean isRead() getter would emit 'read' instead");
        assertFalse(first.get("isRead").asBoolean());

        // Nested order stays trimmed to a reference, not the whole order.
        JsonNode orderRef = first.get("order");
        assertNotNull(orderRef, "app model expects a nested 'order' object");
        assertEquals(order.getId(), orderRef.get("id").asLong());
        assertEquals(order.getOrderNumber(), orderRef.get("orderNumber").asText());
        assertFalse(orderRef.has("orderItems"), "order reference must not carry the full order contents");
        assertFalse(orderRef.has("address"), "order reference must not drag in the lazy address");
        assertFalse(orderRef.has("customer"), "order reference must not renest the customer");
    }

    /**
     * A notification with no order at all (a broadcast/announcement) is a
     * real row shape - it must serialize to a null 'order' rather than
     * blowing up, since the app models that field as nullable.
     */
    @Test
    void notificationWithoutAnOrderSerializesWithNullOrderRef() throws Exception {
        Customer customer = newCustomer("broadcast-test");

        Notification broadcast = new Notification();
        broadcast.setCustomer(customer);
        broadcast.setTitle("Store announcement");
        broadcast.setMessage("We are open late today.");
        broadcast.setSentAt(LocalDateTime.now());
        broadcast.setActive(true);
        // isRead deliberately left null - older rows predate the column, and
        // the app's model is non-nullable, so the DTO has to normalize it.
        notificationRepository.save(broadcast);

        Page<NotificationResponse> page = notificationService
                .getNotificationsByCustomerId(customer.getId(), PageRequest.of(0, 20));

        String json = MAPPER.writeValueAsString(page.getContent());
        JsonNode first = MAPPER.readTree(json).get(0);

        assertTrue(first.get("order").isNull(), "a notification with no order must emit order:null");
        assertFalse(first.get("isRead").asBoolean(), "a null isRead must normalize to false, not null");
    }

    private Customer newCustomer(String tag) {
        Customer customer = new Customer();
        customer.setFullName("Notification Test " + tag);
        customer.setEmail("notif-" + tag + "-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber("9" + String.valueOf(System.nanoTime()).substring(0, 9));
        customer.setPassword("irrelevant-for-this-test");
        customer.setEnabled(true);
        customer.setActive(true);
        return customerRepository.save(customer);
    }

    private Order newOrder(Customer customer) {
        Address address = new Address();
        address.setCustomer(customer);
        address.setFullName(customer.getFullName());
        address.setMobileNumber(customer.getMobileNumber());
        address.setHouseNo("1");
        address.setArea("Test Area");
        address.setCity("Test City");
        address.setState("Test State");
        address.setPincode("110001");
        address.setCountry("India");
        address.setDefaultAddress(true);
        address = addressRepository.save(address);

        Order order = new Order();
        order.setCustomer(customer);
        order.setAddress(address);
        // order_number carries a unique constraint, and this suite runs
        // against a database that persists between runs (CI reuses the same
        // service container across a job, a local run reuses the same DB) -
        // a fixed literal here passes exactly once and then collides.
        order.setOrderNumber("TEST-ORDER-" + System.nanoTime());
        order.setOrderStatus(OrderStatus.CONFIRMED);
        order.setTotalAmount(new BigDecimal("135.00"));
        order.setOrderDate(LocalDateTime.now());
        order.setActive(true);
        return orderRepository.save(order);
    }
}
