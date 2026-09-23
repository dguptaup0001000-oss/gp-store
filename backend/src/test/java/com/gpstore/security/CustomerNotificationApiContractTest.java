package com.gpstore.security;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Notification;
import com.gpstore.entity.Role;
import com.gpstore.enums.NotificationStatus;
import com.gpstore.enums.NotificationType;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.NotificationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@DisplayName("The Customer APK notification routes match the backend contract")
class CustomerNotificationApiContractTest {

    @Autowired MockMvc mockMvc;
    @Autowired CustomerRepository customers;
    @Autowired NotificationRepository notifications;

    private Customer alice;
    private Customer bob;

    @BeforeEach
    void accounts() {
        alice = customer("alice");
        bob = customer("bob");
    }

    @AfterEach
    void cleanUp() {
        notifications.deleteAll(notifications.findByCustomerId(alice.getId()));
        notifications.deleteAll(notifications.findByCustomerId(bob.getId()));
        customers.deleteAllById(List.of(alice.getId(), bob.getId()));
    }

    @Test
    @DisplayName("zero notifications is HTTP 200 with an empty page")
    void emptyStateIsNotARouteLevel404() throws Exception {
        mockMvc.perform(get("/api/notifications/mine")
                        .param("page", "0").param("size", "20")
                        .with(authentication(as(alice))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/notifications/unread-count")
                        .with(authentication(as(alice))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(0));
    }

    @Test
    @DisplayName("list, unread count, mark one and mark all remain customer-scoped")
    void completeNotificationWireFlow() throws Exception {
        Notification first = notification(alice, "First");
        Notification second = notification(alice, "Second");
        Notification stranger = notification(bob, "Bob only");

        mockMvc.perform(get("/api/notifications/mine")
                        .with(authentication(as(alice))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[?(@.title == 'Bob only')]").isEmpty());
        mockMvc.perform(get("/api/notifications/unread-count")
                        .with(authentication(as(alice))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(2));

        mockMvc.perform(put("/api/notifications/{id}/read", first.getId())
                        .with(authentication(as(bob))))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/notifications/{id}/read", first.getId())
                        .with(authentication(as(alice))))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/notifications/read-all")
                        .with(authentication(as(alice))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications/unread-count")
                        .with(authentication(as(alice))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(0));
        mockMvc.perform(get("/api/notifications/unread-count")
                        .with(authentication(as(bob))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));

        // Keep the compiler and test honest about which rows were created.
        org.junit.jupiter.api.Assertions.assertNotNull(second.getId());
        org.junit.jupiter.api.Assertions.assertNotNull(stranger.getId());
    }

    @Test
    @DisplayName("a customer who also delivers keeps the customer notification identity")
    void customerRiderReadsCustomerNotifications() throws Exception {
        alice.setRole(Role.DELIVERY_BOY);
        customers.saveAndFlush(alice);
        notification(alice, "Customer order update");
        var auth = new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(alice.getId(), alice.getEmail(), Role.DELIVERY_BOY.name()),
                null, List.of(
                        new SimpleGrantedAuthority("ROLE_CUSTOMER"),
                        new SimpleGrantedAuthority("ROLE_DELIVERY_BOY")));

        mockMvc.perform(get("/api/notifications/mine").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Customer order update"));
    }

    @Test
    @DisplayName("a standalone worker has no customer notification identity")
    void standaloneWorkerCannotReadCustomerNotifications() throws Exception {
        var auth = new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(null, "worker@notify.invalid",
                        Role.DELIVERY_BOY.name(), 91L),
                null, List.of(new SimpleGrantedAuthority("ROLE_DELIVERY_BOY")));

        mockMvc.perform(get("/api/notifications/mine").with(authentication(auth)))
                .andExpect(status().isForbidden());
    }

    private Customer customer(String who) {
        Customer customer = new Customer();
        customer.setFullName(who);
        customer.setEmail(who + "-" + System.nanoTime() + "@notify.invalid");
        customer.setMobileNumber("8" + Math.abs(customer.getEmail().hashCode() % 1_000_000_000));
        customer.setRole(Role.CUSTOMER);
        customer.setEnabled(true);
        customer.setActive(true);
        customer.setVerified(true);
        return customers.save(customer);
    }

    private Notification notification(Customer customer, String title) {
        Notification notification = new Notification();
        notification.setCustomer(customer);
        notification.setTitle(title);
        notification.setMessage(title + " message");
        notification.setNotificationType(NotificationType.PUSH);
        notification.setNotificationStatus(NotificationStatus.SENT);
        notification.setSentAt(LocalDateTime.now());
        notification.setIsRead(false);
        notification.setActive(true);
        return notifications.save(notification);
    }

    private UsernamePasswordAuthenticationToken as(Customer customer) {
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(customer.getId(), customer.getEmail(), Role.CUSTOMER.name()),
                null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }
}
