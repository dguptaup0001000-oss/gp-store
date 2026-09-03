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
 * A read permission must not be able to write an order line.
 *
 * WHAT POST /api/order-items DOES. It takes an OrderItem entity straight from
 * the request body and hands it to repository.save() - the parent order, the
 * unit price and the line total all chosen by the caller, nothing validated,
 * and the order's own total never recalculated. A line attached this way
 * makes the order's total disagree with the sum of its lines.
 *
 * WHO COULD REACH IT. The route was matched by "/api/order-items/**" with
 * ORDERS_VIEW - a READ permission. SUPPORT and DELIVERY_MANAGER hold
 * ORDERS_VIEW and not ORDERS_MANAGE, so either could attach a priced line to
 * any order in the shop. That is a read role performing a financial write.
 *
 * A real port and real JWTs: this is about what the deployed filter chain
 * does, and MockMvc does not run the container's ERROR dispatch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("Writing an order line needs more than a read permission")
class OrderLinesAreNotViewOnlyTest {

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

    private HttpEntity<String> bearerJson(String token, String body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }

    private static String phone() {
        return "9" + (100000000 + (int) (Math.random() * 899999999));
    }

    @SuppressWarnings("rawtypes")
    private String tokenForRole(String role) {
        String email = "orderline-" + role.toLowerCase() + "-" + System.nanoTime() + "@example.com";
        ResponseEntity<java.util.Map> res = rest.postForEntity(
                url("/api/auth/register"),
                json("""
                     {"name":"Line Probe","email":"%s","phone":"%s","password":"Passw0rd!23"}
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

    /** A line with a price the caller picked, on an order the caller picked. */
    private static final String FORGED_LINE = """
            {"quantity":1,"price":0.01,"totalPrice":0.01,"active":true}
            """;

    @Test
    @DisplayName("SUPPORT cannot write an order line")
    void supportCannotWriteAnOrderLine() {
        String token = tokenForRole("SUPPORT");

        ResponseEntity<String> response = rest.exchange(
                url("/api/order-items"), HttpMethod.POST, bearerJson(token, FORGED_LINE), String.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "A support agent reached a financial write with a read permission. Body: "
                        + response.getBody());
    }

    @Test
    @DisplayName("DELIVERY_MANAGER cannot write an order line")
    void deliveryManagerCannotWriteAnOrderLine() {
        String token = tokenForRole("DELIVERY_MANAGER");

        ResponseEntity<String> response = rest.exchange(
                url("/api/order-items"), HttpMethod.POST, bearerJson(token, FORGED_LINE), String.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), response.getBody());
    }

    @Test
    @DisplayName("a plain customer certainly cannot")
    void customersCannotWriteAnOrderLine() {
        String token = tokenForRole("CUSTOMER");

        ResponseEntity<String> response = rest.exchange(
                url("/api/order-items"), HttpMethod.POST, bearerJson(token, FORGED_LINE), String.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), response.getBody());
    }

    @Test
    @DisplayName("reading order lines is still open to a read permission")
    void supportCanStillRead() {
        String token = tokenForRole("SUPPORT");

        ResponseEntity<String> response = rest.exchange(
                url("/api/order-items"), HttpMethod.GET,
                new HttpEntity<>(bearerJson(token, "").getHeaders()), String.class);

        // The point of splitting read from write is that the read still works.
        assertNotEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "Tightening the write must not have taken the read away: " + response.getBody());
    }
}
