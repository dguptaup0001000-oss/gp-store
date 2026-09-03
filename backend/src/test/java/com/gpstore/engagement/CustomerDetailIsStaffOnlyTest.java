package com.gpstore.engagement;

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
 * One customer must never be able to read another customer's file.
 *
 * WHAT THIS ENDPOINT RETURNS, which is why it gets its own test: a named
 * person's phone number, every address they have saved including the
 * directions to their front door, what is sitting in their basket right now,
 * and what they have spent. It is the most sensitive single response the
 * application produces.
 *
 * AND IT WOULD HAVE BEEN OPEN. SecurityConfig ends with
 * anyRequest().authenticated(), so a new route under /api/customers is
 * reachable by ANY signed-in account unless a rule says otherwise. Without
 * the explicit matcher, one customer could have read another customer's home
 * address by changing a number in a URL - the plainest IDOR there is, on the
 * plainest data.
 *
 * A REAL PORT AND REAL TOKENS, for the reason AccessDeniedStatusTest
 * documents at length: MockMvc does not run the container's ERROR dispatch,
 * so it can report a comfortable 403 for a request the deployed application
 * answers differently. Nothing here can be satisfied by a mock.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("A customer's file is staff-only")
class CustomerDetailIsStaffOnlyTest {

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
                     {"name":"Detail Probe","email":"%s","phone":"%s","password":"Passw0rd!23"}
                     """.formatted(email, phone())),
                java.util.Map.class);
        assertEquals(HttpStatus.OK, res.getStatusCode(), "registration failed: " + res.getBody());
        return (String) res.getBody().get("token");
    }

    private Long idOf(String email) {
        return jdbc.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
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
    @DisplayName("a customer cannot read another customer's file")
    void oneCustomerCannotReadAnother() {
        long stamp = System.nanoTime();
        String victimEmail = "detail-victim-" + stamp + "@example.com";
        register(victimEmail);
        Long victimId = idOf(victimEmail);

        String snooperToken = register("detail-snoop-" + stamp + "@example.com");

        ResponseEntity<String> response = rest.exchange(
                url("/api/customers/" + victimId + "/detail"),
                HttpMethod.GET, bearer(snooperToken), String.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "A signed-in customer read another customer's file. Body: " + response.getBody());

        // AND THE DATA MUST NOT BE IN THE BODY EITHER. A refusal that still
        // ships the payload is not a refusal.
        String body = response.getBody() == null ? "" : response.getBody();
        assertFalse(body.contains("wishlist"),
                "The refusal carried the customer's file: " + body);
    }

    @Test
    @DisplayName("a customer cannot even read their own file through this route")
    void notEvenTheirOwnFile() {
        long stamp = System.nanoTime();
        String email = "detail-self-" + stamp + "@example.com";
        String token = register(email);
        Long id = idOf(email);

        // This is a STAFF screen, not a profile screen. A customer has
        // /api/customers/me for their own details; widening this route to
        // "or your own id" would be a second code path to keep correct
        // forever, for no capability anybody asked for.
        ResponseEntity<String> response = rest.exchange(
                url("/api/customers/" + id + "/detail"),
                HttpMethod.GET, bearer(token), String.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    @DisplayName("an anonymous caller gets nothing")
    void anonymousIsRefused() {
        long stamp = System.nanoTime();
        String email = "detail-anon-" + stamp + "@example.com";
        register(email);
        Long id = idOf(email);

        ResponseEntity<String> response = rest.exchange(
                url("/api/customers/" + id + "/detail"),
                HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);

        assertTrue(response.getStatusCode() == HttpStatus.UNAUTHORIZED
                        || response.getStatusCode() == HttpStatus.FORBIDDEN,
                "Anonymous got " + response.getStatusCode());
    }

    @Test
    @DisplayName("staff can read it, which is the whole point")
    void staffCanRead() {
        long stamp = System.nanoTime();
        String subjectEmail = "detail-subject-" + stamp + "@example.com";
        register(subjectEmail);
        Long subjectId = idOf(subjectEmail);

        String admin = staffToken("detail-admin-" + stamp + "@example.com");

        ResponseEntity<String> response = rest.exchange(
                url("/api/customers/" + subjectId + "/detail"),
                HttpMethod.GET, bearer(admin), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "Staff were refused their own screen: " + response.getBody());

        String body = response.getBody() == null ? "" : response.getBody();
        // The shape a shopkeeper needs, all in one answer.
        for (String field : new String[]{"addresses", "cart", "wishlist", "orders", "engagement"}) {
            assertTrue(body.contains(field), "Missing " + field + " in: " + body);
        }
    }

    @Test
    @DisplayName("the file never carries a password hash, device token or precise pin")
    void noSecretsInTheFile() {
        long stamp = System.nanoTime();
        String subjectEmail = "detail-secrets-" + stamp + "@example.com";
        register(subjectEmail);
        Long subjectId = idOf(subjectEmail);

        String admin = staffToken("detail-secadmin-" + stamp + "@example.com");

        ResponseEntity<String> response = rest.exchange(
                url("/api/customers/" + subjectId + "/detail"),
                HttpMethod.GET, bearer(admin), String.class);

        String body = response.getBody() == null ? "" : response.getBody();
        // A staff screen is still a screen: it gets screenshotted, pasted into
        // chats and photographed. None of these help serve a customer.
        assertFalse(body.contains("password"), "Password field leaked: " + body);
        assertFalse(body.contains("$2a$"), "A bcrypt hash leaked: " + body);
        assertFalse(body.contains("fcmToken"), "The device push token leaked: " + body);
        assertFalse(body.contains("latitude"), "Precise home coordinates leaked: " + body);
    }
}
