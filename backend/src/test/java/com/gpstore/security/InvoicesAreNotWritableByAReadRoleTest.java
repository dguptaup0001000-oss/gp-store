package com.gpstore.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A read permission must not be able to cancel an invoice.
 *
 * THE THIRD SIBLING. Part 1 found /api/order-items/** matched by one rule on
 * ORDERS_VIEW covering every verb, and fixed it. Part 3 found /api/cart-items/**
 * with the identical shape, and fixed that. /api/invoices/** is the same shape
 * again - a single rule on ORDERS_VIEW, no HttpMethod - and underneath it:
 *
 *   POST /api/invoices?orderId=N     generate an invoice
 *   PUT  /api/invoices/{id}/cancel   cancel one
 *
 * WHO HOLDS ORDERS_VIEW WITHOUT ORDERS_MANAGE. SUPPORT, whose comment in
 * RolePermissions says it "Changes nothing else", and DELIVERY_MANAGER, who
 * runs dispatch. Either could cancel the shop's own record of a sale. An
 * invoice is a tax document; cancelling one is not a UI inconvenience, it is a
 * hole in the books, and nothing in InvoiceService re-checks the caller.
 *
 * Fixed the same way as the two before it: writes need ORDERS_MANAGE, reads
 * stay on ORDERS_VIEW so support can still answer "what was I charged".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "rate-limit.auth-per-minute=250",
        "rate-limit.mutation-per-minute=250",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("Cancelling an invoice needs more than a read permission")
class InvoicesAreNotWritableByAReadRoleTest {

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    private String url(String path) {
        return "http://localhost:" + port + "/v1" + path;
    }

    private static HttpEntity<String> json(String body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }

    private HttpEntity<String> bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>("", h);
    }

    private static String phone() {
        return "9" + (100000000 + (int) (Math.random() * 899999999));
    }

    @SuppressWarnings("rawtypes")
    private String tokenForRole(String role) {
        String email = "inv-" + role.toLowerCase() + "-" + System.nanoTime() + "@example.com";
        ResponseEntity<java.util.Map> res = rest.postForEntity(
                url("/api/auth/register"),
                json("""
                     {"name":"Invoice Probe","email":"%s","phone":"%s","password":"Passw0rd!23"}
                     """.formatted(email, phone())),
                java.util.Map.class);
        assertEquals(HttpStatus.OK, res.getStatusCode(), "registration failed: " + res.getBody());
        jdbc.update("UPDATE customers SET role = ? WHERE email = ?", role, email);
        ResponseEntity<java.util.Map> login = rest.postForEntity(
                url("/api/auth/login"),
                json("""
                     {"email":"%s","password":"Passw0rd!23"}
                     """.formatted(email)),
                java.util.Map.class);
        return (String) login.getBody().get("token");
    }

    @Test
    @DisplayName("SUPPORT cannot cancel an invoice")
    void supportCannotCancelAnInvoice() {
        String token = tokenForRole("SUPPORT");

        ResponseEntity<String> response = rest.exchange(
                url("/api/invoices/1/cancel"), HttpMethod.PUT, bearer(token), String.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "a role documented as changing nothing cancelled a tax document. Body: "
                        + response.getBody());
    }

    @Test
    @DisplayName("SUPPORT cannot generate an invoice")
    void supportCannotGenerateAnInvoice() {
        String token = tokenForRole("SUPPORT");

        ResponseEntity<String> response = rest.exchange(
                url("/api/invoices?orderId=1"), HttpMethod.POST, bearer(token), String.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), response.getBody());
    }

    @Test
    @DisplayName("DELIVERY_MANAGER has no business in the books either")
    void deliveryManagerCannotCancelAnInvoice() {
        String token = tokenForRole("DELIVERY_MANAGER");

        ResponseEntity<String> response = rest.exchange(
                url("/api/invoices/1/cancel"), HttpMethod.PUT, bearer(token), String.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), response.getBody());
    }

    @Test
    @DisplayName("reading an invoice is still open to a read permission")
    void supportCanStillReadAnInvoice() {
        String token = tokenForRole("SUPPORT");

        // THE FEATURE MUST SURVIVE THE FIX. "What was I charged?" is the most
        // ordinary support question there is, and closing the writes must not
        // close the read that answers it.
        ResponseEntity<String> response = rest.exchange(
                url("/api/invoices/1"), HttpMethod.GET, bearer(token), String.class);

        assertNotEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "the read a support agent actually needs was closed: " + response.getBody());
    }

    @Test
    @DisplayName("ORDER_MANAGER, who does own the books, is not blocked")
    void orderManagerIsNotBlocked() {
        String token = tokenForRole("ORDER_MANAGER");

        // Not asserting success - order 1 may not exist - only that the
        // authorization layer let them through. A fix that locks everybody
        // out would pass the three tests above and be useless.
        ResponseEntity<String> response = rest.exchange(
                url("/api/invoices/1/cancel"), HttpMethod.PUT, bearer(token), String.class);

        assertNotEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "the role that manages orders was locked out of invoices: " + response.getBody());
    }
}
