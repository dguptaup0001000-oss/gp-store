package com.gpstore.engagement;

import com.gpstore.entity.Customer;
import com.gpstore.repository.CustomerAppSessionRepository;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.service.CustomerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rules that make a client-reported number safe to show a shopkeeper,
 * and the deletion promise made in docs/PLAY_STORE_DECLARATIONS.md sec. 8.
 *
 * WHY DELETION GETS A TEST OF ITS OWN. Account deletion in this application
 * anonymises the customer row rather than dropping it, so a foreign key does
 * NOT carry child rows away - every table has to be deleted by name in
 * CustomerService.deleteOwnAccount. When app sessions were first written,
 * they were not on that list, and "deleted with the account" would have been
 * a false Play Store declaration. A declaration nothing enforces goes stale
 * the first time somebody adds a table; this is what enforces it.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@TestPropertySource(properties = {
        // Named explicitly so the assertions below read against a known cap
        // rather than whatever the default happens to become later.
        "engagement.max-session-seconds=14400",
        "engagement.min-session-seconds=3",
        "engagement.max-sessions-per-hour=60"
})
@DisplayName("App session recording")
class AppSessionTest {

    @Autowired private AppSessionService sessions;
    @Autowired private CustomerAppSessionRepository repository;
    @Autowired private CustomerRepository customers;
    @Autowired private CustomerService customerService;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String PASSWORD = "Passw0rd!23";

    private Customer newCustomer() {
        Customer customer = new Customer();
        customer.setFullName("Session Probe");
        customer.setEmail("session-" + UUID.randomUUID() + "@example.com");
        customer.setMobileNumber("9" + (100000000 + (int) (Math.random() * 899999999)));
        customer.setPassword(passwordEncoder.encode(PASSWORD));
        customer.setRole(com.gpstore.entity.Role.CUSTOMER);
        customer.setActive(true);
        customer.setEnabled(true);
        return customers.save(customer);
    }

    @Test
    @DisplayName("an ordinary session is recorded as reported")
    void ordinarySessionIsRecorded() {
        Customer customer = newCustomer();

        int recorded = sessions.record(customer, 300);

        assertEquals(300, recorded);
        assertEquals(300, repository.totalSecondsFor(customer.getId()));
        assertEquals(1, repository.sessionCountFor(customer.getId()));
        assertNotNull(repository.lastSeenFor(customer.getId()));
    }

    @Test
    @DisplayName("a session longer than the cap is truncated, not thrown away")
    void tooLongIsTruncated() {
        Customer customer = newCustomer();

        // A phone left unlocked in a pocket overnight.
        int recorded = sessions.record(customer, 40_000);

        assertEquals(14_400, recorded, "the four-hour cap should apply");
        assertEquals(14_400, repository.totalSecondsFor(customer.getId()));
        assertEquals(1, repository.sessionCountFor(customer.getId()),
                "a capped session is still a session - it happened");
    }

    @Test
    @DisplayName("an accidental tap is not a visit")
    void tooShortIsIgnored() {
        Customer customer = newCustomer();

        assertEquals(0, sessions.record(customer, 1));
        assertEquals(0, repository.sessionCountFor(customer.getId()),
                "a one-second session would inflate the visit count while adding no time");
    }

    @Test
    @DisplayName("a client in a loop is stopped after the hourly limit")
    void aLoopingClientIsStopped() {
        Customer customer = newCustomer();

        for (int i = 0; i < 60; i++) {
            assertEquals(10, sessions.record(customer, 10), "session " + i + " should be accepted");
        }
        assertEquals(0, sessions.record(customer, 10),
                "the 61st session in an hour is a loop, not a person");
        assertEquals(60, repository.sessionCountFor(customer.getId()));
    }

    @Test
    @DisplayName("a negative claim never reaches the table")
    void negativeIsRefused() {
        Customer customer = newCustomer();

        assertEquals(0, sessions.record(customer, -500));
        assertEquals(0, repository.sessionCountFor(customer.getId()));
        assertEquals(0, repository.totalSecondsFor(customer.getId()),
                "a negative duration must never be able to reduce a total");
    }

    @Test
    @DisplayName("deleting the account deletes the usage history with it")
    void deletionTakesTheHistory() {
        Customer customer = newCustomer();
        Long id = customer.getId();

        sessions.record(customer, 600);
        sessions.record(customer, 900);
        assertEquals(2, repository.sessionCountFor(id), "precondition: there is history to delete");
        assertEquals(1500, repository.totalSecondsFor(id));

        customerService.deleteOwnAccount(id, PASSWORD);

        // THE POINT OF THIS TEST. The customer row still exists, anonymised -
        // so nothing is carried away by a cascade, and if these rows were not
        // deleted by name they would simply still be here, still joined to a
        // real person's id.
        assertTrue(customers.findById(id).isPresent(),
                "deletion anonymises in place; if this ever changes, the reasoning above needs rechecking");
        assertEquals(0, repository.sessionCountFor(id),
                "app sessions outlived an account deletion - "
                        + "docs/PLAY_STORE_DECLARATIONS.md sec. 8 says they must not");
        assertEquals(0, repository.totalSecondsFor(id));
    }
}
