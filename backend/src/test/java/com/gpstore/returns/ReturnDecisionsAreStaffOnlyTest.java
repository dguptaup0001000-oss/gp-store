package com.gpstore.returns;

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
 * A customer must never be able to approve their own return.
 *
 * WHAT THAT WOULD MEAN. Approving is the step that sends money: it opens a
 * refund through the ledger and puts stock back. A shopper who could call it
 * would refund themselves for goods they still have, as many times as there
 * are lines on the order. It is the most directly monetisable route in the
 * application.
 *
 * AND IT WOULD HAVE BEEN OPEN. SecurityConfig ends with
 * anyRequest().authenticated(), so every new route under /api/returns is
 * reachable by ANY signed-in account unless a matcher says otherwise. The
 * customer routes are safe by construction - they take the account from the
 * token and never from the URL - but the staff routes are not, and nothing
 * about the code would tell you which is which.
 *
 * A REAL PORT AND REAL TOKENS, for the reason AccessDeniedStatusTest
 * documents: MockMvc does not run the container's ERROR dispatch, so it can
 * report a comfortable 403 for a request the deployed application answers
 * differently.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("Deciding a return is staff-only")
class ReturnDecisionsAreStaffOnlyTest {

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

    private HttpEntity<String> bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return new HttpEntity<>(h);
    }

    private static String phone() {
        return "9" + (100000000 + (int) (Math.random() * 899999999));
    }

    @SuppressWarnings("rawtypes")
    private String register(String email) {
        ResponseEntity<java.util.Map> res = rest.postForEntity(
                url("/api/auth/register"),
                json("""
                     {"name":"Return Probe","email":"%s","phone":"%s","password":"Passw0rd!23"}
                     """.formatted(email, phone())),
                java.util.Map.class);
        assertEquals(HttpStatus.OK, res.getStatusCode(), "registration failed: " + res.getBody());
        return (String) res.getBody().get("token");
    }

    @SuppressWarnings("rawtypes")
    private String staffToken(String email) {
        register(email);
        // Nothing in the application grants ADMIN - registration hardcodes
        // CUSTOMER - so the role is set directly, exactly as production does.
        jdbc.update("UPDATE customers SET role = 'ADMIN' WHERE email = ?", email);
        ResponseEntity<java.util.Map> login = rest.postForEntity(
                url("/api/auth/login"),
                json("""
                     {"email":"%s","password":"Passw0rd!23"}
                     """.formatted(email)),
                java.util.Map.class);
        return (String) login.getBody().get("token");
    }

    @Test
    @DisplayName("a customer cannot approve a return")
    void customersCannotApprove() {
        String token = register("ret-approve-" + System.nanoTime() + "@example.com");

        ResponseEntity<String> response = rest.exchange(
                url("/api/returns/1/approve"), HttpMethod.POST, bearerJson(token, "{}"), String.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "A signed-in shopper reached the route that sends refunds. Body: " + response.getBody());
    }

    @Test
    @DisplayName("a customer cannot reject a return")
    void customersCannotReject() {
        String token = register("ret-reject-" + System.nanoTime() + "@example.com");

        ResponseEntity<String> response = rest.exchange(
                url("/api/returns/1/reject"), HttpMethod.POST,
                bearerJson(token, "{\"note\":\"no\"}"), String.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), response.getBody());
    }

    @Test
    @DisplayName("a customer cannot read the shop's returns queue")
    void customersCannotSeeTheQueue() {
        String token = register("ret-queue-" + System.nanoTime() + "@example.com");

        // The queue carries other customers' order numbers and reasons.
        for (String path : new String[]{"/api/returns/pending", "/api/returns/pending/count"}) {
            ResponseEntity<String> response = rest.exchange(
                    url(path), HttpMethod.GET, bearer(token), String.class);
            assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                    path + " leaked to a shopper: " + response.getBody());
        }
    }

    @Test
    @DisplayName("an anonymous caller gets nothing")
    void anonymousIsRefused() {
        ResponseEntity<String> response = rest.exchange(
                url("/api/returns/pending"), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);

        assertTrue(response.getStatusCode() == HttpStatus.UNAUTHORIZED
                        || response.getStatusCode() == HttpStatus.FORBIDDEN,
                "Anonymous got " + response.getStatusCode());
    }

    @Test
    @DisplayName("staff can read the queue, which is the whole point")
    void staffCanReadTheQueue() {
        String admin = staffToken("ret-admin-" + System.nanoTime() + "@example.com");

        ResponseEntity<String> response = rest.exchange(
                url("/api/returns/pending"), HttpMethod.GET, bearer(admin), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "Staff were refused their own queue: " + response.getBody());
    }

    @Test
    @DisplayName("a customer CAN still ask for a return on their own order")
    void customersKeepTheirOwnRoutes() {
        String token = register("ret-own-" + System.nanoTime() + "@example.com");

        // Their own routes must not have been locked down by the staff rules.
        // 404 because this customer has no order 999999 - the point is that
        // it is not 403.
        ResponseEntity<String> response = rest.exchange(
                url("/api/returns/orders/999999/returnable"), HttpMethod.GET,
                bearer(token), String.class);

        assertNotEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "The staff matchers must not have swallowed the customer's own routes.");

        ResponseEntity<String> mine = rest.exchange(
                url("/api/returns/me"), HttpMethod.GET, bearer(token), String.class);
        assertEquals(HttpStatus.OK, mine.getStatusCode(), mine.getBody());
    }
}
