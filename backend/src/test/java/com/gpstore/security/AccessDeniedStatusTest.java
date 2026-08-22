package com.gpstore.security;

import org.junit.jupiter.api.BeforeAll;
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
 * A logged-in customer touching an admin-only route must get 403, not 401.
 *
 * WHY THIS TEST RUNS A REAL SERVER. Every other authorization test here uses
 * MockMvc, and all of them passed while the deployed application was answering
 * 401 to exactly the requests they assert are 403. MockMvc does not run the
 * servlet container's ERROR dispatch: it observes the AccessDeniedException
 * Spring Security raises and reports 403. A real container forwards to /error,
 * that forward re-enters the security filter chain, /error matches no permitAll
 * rule, and the authentication entry point answers 401 instead - with
 * "path":"/error", not the path the caller asked for.
 *
 * Found by probing a running instance with a real customer JWT:
 *
 *     GET /v1/api/customers  ->  401 {"path":"/v1/error"}
 *
 * The cost of getting this wrong is not cosmetic. ApiClient._handleError
 * treats 401 as "the access token expired": it refreshes (which succeeds -
 * the token was never the problem), retries, receives 401 again, and refreshes
 * again. That branch carries no attempt counter, unlike _retryIfSafe, so it
 * does not terminate, and every pass rotates the refresh token.
 *
 * So: a real port, a real JWT, a real HTTP client. Nothing here can be
 * satisfied by a mock.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class AccessDeniedStatusTest {

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    private static String customerToken;
    private static String adminToken;

    private String url(String path) {
        return "http://localhost:" + port + "/v1" + path;
    }

    private String register(String email, String phone) {
        ResponseEntity<java.util.Map> res = rest.postForEntity(
                url("/api/auth/register"),
                json("""
                     {"name":"Authz Probe","email":"%s","phone":"%s","password":"Passw0rd!23"}
                     """.formatted(email, phone)),
                java.util.Map.class);
        assertEquals(HttpStatus.OK, res.getStatusCode(), "registration failed: " + res.getBody());
        return (String) res.getBody().get("token");
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

    @BeforeAll
    static void reset() {
        customerToken = null;
        adminToken = null;
    }

    private void ensureIdentities() {
        if (customerToken != null) return;
        long stamp = System.nanoTime();
        customerToken = register("authz-cust-" + stamp + "@example.com", phone());

        String adminEmail = "authz-admin-" + stamp + "@example.com";
        adminToken = register(adminEmail, phone());
        // Nothing in the application grants ADMIN - registration hardcodes
        // CUSTOMER - so the role is set directly, exactly as production does it.
        jdbc.update("UPDATE customers SET role = 'ADMIN' WHERE email = ?", adminEmail);
        ResponseEntity<java.util.Map> login = rest.postForEntity(
                url("/api/auth/login"),
                json("""
                     {"email":"%s","password":"Passw0rd!23"}
                     """.formatted(adminEmail)),
                java.util.Map.class);
        adminToken = (String) login.getBody().get("token");
    }

    private static String phone() {
        return "9" + (100000000 + (int) (Math.random() * 899999999));
    }

    @Test
    @DisplayName("a logged-in CUSTOMER on an admin route gets 403, never 401")
    void customerOnAdminRouteIsForbiddenNotUnauthorized() {
        ensureIdentities();

        for (String adminPath : new String[]{
                "/api/customers", "/api/audit-logs", "/api/inventory",
                "/api/coupons", "/api/payments", "/api/products/admin/all"}) {

            ResponseEntity<String> res = rest.exchange(
                    url(adminPath), HttpMethod.GET, bearer(customerToken), String.class);

            assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode(),
                    adminPath + " must answer 403 to a logged-in customer, not "
                            + res.getStatusCode() + " - a 401 here sends ApiClient into "
                            + "refresh-and-retry on a token that was never expired. Body: " + res.getBody());

            assertNotNull(res.getBody(), adminPath + " must return a body");
            assertTrue(res.getBody().contains("\"status\":403"),
                    adminPath + " body must say 403; was: " + res.getBody());
            // The 401-via-/error bug rewrote the path. A correct 403 keeps it.
            assertFalse(res.getBody().contains("/error"),
                    adminPath + " must report the requested path, not the error dispatch path; was: "
                            + res.getBody());
        }
    }

    @Test
    @DisplayName("no credentials still gets 401, so token refresh keeps working")
    void anonymousStillGets401() {
        ResponseEntity<String> res = rest.exchange(
                url("/api/customers"), HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode(),
                "an unauthenticated request must stay 401 - this is what lets the app "
                        + "refresh an expired access token instead of dead-ending. Body: " + res.getBody());
    }

    @Test
    @DisplayName("an ADMIN is still allowed through")
    void adminIsAllowed() {
        ensureIdentities();
        ResponseEntity<String> res = rest.exchange(
                url("/api/customers"), HttpMethod.GET, bearer(adminToken), String.class);
        assertEquals(HttpStatus.OK, res.getStatusCode(),
                "the fix must not have broken admin access. Body: " + res.getBody());
    }

    @Test
    @DisplayName("a garbage token is 401, not 403 - it is a credential problem, not a permission one")
    void malformedTokenIs401() {
        ResponseEntity<String> res = rest.exchange(
                url("/api/customers"), HttpMethod.GET, bearer("not.a.real.token"), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode(),
                "an unparseable token leaves the SecurityContext empty, which is 401. Body: " + res.getBody());
    }
}
