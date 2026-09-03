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
 * Creating a customer must not be a way to become a super admin.
 *
 * POST /api/customers takes a raw Customer ENTITY from the request body and
 * hands it to customerRepository.save(). Two things follow from that, and
 * neither was checked:
 *
 * ROLE. saveCustomer defaulted the role to CUSTOMER only when it arrived
 * null. A body carrying "role":"SUPER_ADMIN" was saved verbatim, with a
 * password the caller chose - so whoever sent it could then log in as the
 * highest role in the system. The route needs CUSTOMERS_MANAGE, which
 * MANAGER holds; MANAGER is a limited role with eighteen named permissions,
 * not an administrator.
 *
 * ID. save() on an entity that already carries an id is an UPDATE, not an
 * insert. A body with somebody else's customer id and a fresh password
 * overwrites that account - including an administrator's - which is a
 * takeover rather than a creation.
 *
 * A real port and real JWTs: this is about what the deployed application
 * writes to its database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("Creating a customer cannot mint a role or overwrite an account")
class CreateCustomerCannotEscalateTest {

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
    private String registerWithRole(String email, String role) {
        ResponseEntity<java.util.Map> res = rest.postForEntity(
                url("/api/auth/register"),
                json("""
                     {"name":"Escalation Probe","email":"%s","phone":"%s","password":"Passw0rd!23"}
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
    @DisplayName("a MANAGER cannot create a SUPER_ADMIN")
    void managerCannotMintASuperAdmin() {
        String manager = registerWithRole("mgr-" + System.nanoTime() + "@example.com", "MANAGER");
        String victimEmail = "minted-" + System.nanoTime() + "@example.com";

        ResponseEntity<String> response = rest.exchange(
                url("/api/customers"), HttpMethod.POST,
                bearerJson(manager, """
                        {"fullName":"Minted","email":"%s","mobileNumber":"%s",
                         "password":"Passw0rd!23","role":"SUPER_ADMIN"}
                        """.formatted(victimEmail, phone())),
                String.class);

        // Creating the customer is allowed - that is the feature. What must
        // not happen is the role coming from the body.
        if (response.getStatusCode().is2xxSuccessful()) {
            String stored = jdbc.queryForObject(
                    "SELECT role FROM customers WHERE email = ?", String.class, victimEmail);
            assertEquals("CUSTOMER", stored,
                    "A MANAGER minted a " + stored + " account. That account's password was "
                            + "chosen in the same request, so it is a login as that role.");
        } else {
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                    "Refusing outright is fine too, but not with this status: " + response.getBody());
        }
    }

    @Test
    @DisplayName("an id in the body cannot overwrite an existing account")
    void idInTheBodyCannotOverwriteAnAccount() {
        String manager = registerWithRole("mgr2-" + System.nanoTime() + "@example.com", "MANAGER");

        String targetEmail = "target-" + System.nanoTime() + "@example.com";
        registerWithRole(targetEmail, "ADMIN");
        Long targetId = jdbc.queryForObject(
                "SELECT id FROM customers WHERE email = ?", Long.class, targetEmail);

        ResponseEntity<String> response = rest.exchange(
                url("/api/customers"), HttpMethod.POST,
                bearerJson(manager, """
                        {"id":%d,"fullName":"Hijacked","email":"hijack-%d@example.com",
                         "mobileNumber":"%s","password":"Passw0rd!23"}
                        """.formatted(targetId, System.nanoTime(), phone())),
                String.class);

        String roleAfter = jdbc.queryForObject(
                "SELECT role FROM customers WHERE id = ?", String.class, targetId);
        String nameAfter = jdbc.queryForObject(
                "SELECT full_name FROM customers WHERE id = ?", String.class, targetId);

        assertEquals("ADMIN", roleAfter,
                "The administrator's row was rewritten by a create call. Status was "
                        + response.getStatusCode());
        assertNotEquals("Hijacked", nameAfter,
                "The administrator's account was overwritten - the password in that "
                        + "same body is now a login to it.");
    }

    @Test
    @DisplayName("the ordinary case still works: a plain customer is created")
    void creatingAPlainCustomerStillWorks() {
        String manager = registerWithRole("mgr3-" + System.nanoTime() + "@example.com", "MANAGER");
        String email = "plain-" + System.nanoTime() + "@example.com";

        ResponseEntity<String> response = rest.exchange(
                url("/api/customers"), HttpMethod.POST,
                bearerJson(manager, """
                        {"fullName":"Phone Order","email":"%s","mobileNumber":"%s"}
                        """.formatted(email, phone())),
                String.class);

        assertTrue(response.getStatusCode().is2xxSuccessful(),
                "Tightening this must not have broken creating a phone-order customer: "
                        + response.getBody());
        assertEquals("CUSTOMER", jdbc.queryForObject(
                "SELECT role FROM customers WHERE email = ?", String.class, email));
    }
}
