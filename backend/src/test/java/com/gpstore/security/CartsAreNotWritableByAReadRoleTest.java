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
 * A read permission must not be able to empty somebody's shopping basket.
 *
 * THE SIBLING I MISSED. Part 1 found that /api/order-items/** was matched by a
 * single rule on ORDERS_VIEW, so a read role could write a priced order line,
 * and fixed it. /api/cart-items/** has exactly the same shape one resource
 * over - a single rule on CUSTOMERS_VIEW covering every method - and it was
 * not checked at the time. This is that check.
 *
 * WHAT SITS UNDER THAT RULE:
 *
 *   POST   /api/cart-items            a CartItem bound straight from the body
 *   POST   /api/cart-items/add        the same again
 *   DELETE /api/cart-items/{id}       deleteById, no ownership check
 *   DELETE /api/cart-items/cart/{id}  clears an entire cart
 *   GET    /api/cart-items/cart/{id}  reads any customer's basket
 *
 * WHO HOLDS CUSTOMERS_VIEW. SUPPORT, DELIVERY_MANAGER and ORDER_MANAGER. The
 * comment on SUPPORT in RolePermissions says, in as many words, "Changes
 * nothing else" - while the role could delete every line from any shopper's
 * cart mid-shop. DELIVERY_MANAGER runs dispatch and has no business in a
 * basket at all.
 *
 * A real port and real JWTs: this is about what the deployed filter chain
 * does, not what a mock thinks it does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Same reasoning as OrderLinesAreNotViewOnlyTest: these fixtures
        // register real accounts, CI shares one IP, and the auth limiter is
        // not what this class is testing. Production default untouched.
        "rate-limit.auth-per-minute=250",
        "rate-limit.mutation-per-minute=250",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("Writing a cart line needs more than a read permission")
class CartsAreNotWritableByAReadRoleTest {

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

    private HttpEntity<String> bearer(String token, String body) {
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
        String email = "cartline-" + role.toLowerCase() + "-" + System.nanoTime() + "@example.com";
        ResponseEntity<java.util.Map> res = rest.postForEntity(
                url("/api/auth/register"),
                json("""
                     {"name":"Cart Probe","email":"%s","phone":"%s","password":"Passw0rd!23"}
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

    /** A cart line with a price and a quantity the caller picked. */
    private static final String FORGED_LINE = """
            {"quantity":99,"price":0.01,"totalPrice":0.01,"active":true}
            """;

    @Test
    @DisplayName("SUPPORT cannot write a cart line")
    void supportCannotWriteACartLine() {
        String token = tokenForRole("SUPPORT");

        ResponseEntity<String> response = rest.exchange(
                url("/api/cart-items"), HttpMethod.POST, bearer(token, FORGED_LINE), String.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "a role documented as changing nothing wrote a cart line. Body: " + response.getBody());
    }

    @Test
    @DisplayName("SUPPORT cannot empty a customer's cart")
    void supportCannotClearACart() {
        String token = tokenForRole("SUPPORT");

        // The one that would actually be noticed: a shopper's basket emptying
        // underneath them while they shop.
        ResponseEntity<String> response = rest.exchange(
                url("/api/cart-items/cart/1"), HttpMethod.DELETE, bearer(token, ""), String.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), response.getBody());
    }

    @Test
    @DisplayName("DELIVERY_MANAGER has no business in a basket either")
    void deliveryManagerCannotClearACart() {
        String token = tokenForRole("DELIVERY_MANAGER");

        ResponseEntity<String> response = rest.exchange(
                url("/api/cart-items/cart/1"), HttpMethod.DELETE, bearer(token, ""), String.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), response.getBody());
    }

    @Test
    @DisplayName("SUPPORT cannot delete a single cart line")
    void supportCannotDeleteACartLine() {
        String token = tokenForRole("SUPPORT");

        ResponseEntity<String> response = rest.exchange(
                url("/api/cart-items/1"), HttpMethod.DELETE, bearer(token, ""), String.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), response.getBody());
    }

    @Test
    @DisplayName("a plain customer certainly cannot")
    void customersCannotWriteACartLine() {
        String token = tokenForRole("CUSTOMER");

        ResponseEntity<String> response = rest.exchange(
                url("/api/cart-items"), HttpMethod.POST, bearer(token, FORGED_LINE), String.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), response.getBody());
    }

    @Test
    @DisplayName("reading a cart is still open to a read permission")
    void supportCanStillRead() {
        String token = tokenForRole("SUPPORT");

        // THE FEATURE MUST SURVIVE THE FIX. Support answering "what is in
        // their basket" is the whole reason this route is reachable by a
        // read role, and closing the writes must not close that.
        ResponseEntity<String> response = rest.exchange(
                url("/api/cart-items/cart/1"), HttpMethod.GET, bearer(token, ""), String.class);

        assertNotEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "the read a support agent actually needs was closed: " + response.getBody());
    }
}
