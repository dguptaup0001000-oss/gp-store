package com.gpstore.security;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Order;
import com.gpstore.entity.Role;
import com.gpstore.enums.OrderStatus;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.OrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "Is the caller an admin?" answered with a string comparison against ONE role
 * name.
 *
 * Six places ask the question this way:
 *
 *     boolean isAdmin = "ADMIN".equals(currentUser.get().getRole());
 *
 * getRole() returns the raw role name from the JWT - "SUPER_ADMIN",
 * "MANAGER", "ORDER_MANAGER", "SUPPORT", "DELIVERY_MANAGER". Only the role
 * literally spelled ADMIN matches. Every other staff role, INCLUDING THE
 * OWNER'S OWN SUPER_ADMIN, is treated as an ordinary customer and falls into
 * the ownership check - which compares the order against the staff member's
 * own customer id and, of course, does not match.
 *
 * WHAT THAT COSTS. The admin app's order detail screen calls
 * GET /api/orders/{id} directly - admin_order_detail_screen.dart says so in
 * as many words. So a SUPPORT agent, whose entire job is "explain what
 * happened to an order", opens an order and is told it does not exist. So is
 * the shop owner on SUPER_ADMIN. The permission system was built, documented
 * and wired into SecurityConfig, and then six call sites ignored it in favour
 * of one hardcoded string.
 *
 * The right question is never "which role is this" but "does this caller hold
 * the permission for what they are about to do" - which is what
 * SecurityConfig itself already asks everywhere else.
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
@DisplayName("Every staff role is an admin, not only the one spelled ADMIN")
class AdminIsNotJustTheRoleNamedAdminTest {

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CustomerRepository customers;
    @Autowired private OrderRepository orders;
    @Autowired private PasswordEncoder encoder;

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
        String email = "staff-" + role.toLowerCase() + "-" + System.nanoTime() + "@example.com";
        ResponseEntity<java.util.Map> res = rest.postForEntity(
                url("/api/auth/register"),
                json("""
                     {"name":"Staff","email":"%s","phone":"%s","password":"Passw0rd!23"}
                     """.formatted(email, phone())),
                java.util.Map.class);
        assertEquals(HttpStatus.OK, res.getStatusCode(), "registration failed: " + res.getBody());

        // Role set BEFORE login: JwtFilter re-checks the token's role against
        // the live row on every request, so minting first would be rejected.
        jdbc.update("UPDATE customers SET role = ? WHERE email = ?", role, email);

        ResponseEntity<java.util.Map> login = rest.postForEntity(
                url("/api/auth/login"),
                json("""
                     {"email":"%s","password":"Passw0rd!23"}
                     """.formatted(email)),
                java.util.Map.class);
        return (String) login.getBody().get("token");
    }

    /** An order belonging to somebody else entirely. */
    private Long someoneElsesOrder() {
        Customer shopper = new Customer();
        shopper.setFullName("A Customer");
        shopper.setEmail("shopper-" + System.nanoTime() + "@example.com");
        shopper.setMobileNumber("9" + String.format("%09d", System.nanoTime() % 1_000_000_000L));
        shopper.setPassword(encoder.encode("a-real-passphrase"));
        shopper.setRole(Role.CUSTOMER);
        shopper.setActive(true);
        shopper.setEnabled(true);
        shopper = customers.save(shopper);

        Order order = new Order();
        order.setOrderNumber("ADM-" + System.nanoTime());
        order.setCustomer(shopper);
        order.setTotalAmount(new BigDecimal("250.00"));
        order.setOrderStatus(OrderStatus.DELIVERED);
        order.setOrderDate(LocalDateTime.now().minusDays(1));
        order.setActive(true);
        return orders.save(order).getId();
    }

    @Test
    @DisplayName("SUPPORT can open a customer's order, which is the whole job")
    void supportCanOpenACustomersOrder() {
        Long orderId = someoneElsesOrder();
        String token = tokenForRole("SUPPORT");

        ResponseEntity<String> response = rest.exchange(
                url("/api/orders/" + orderId), HttpMethod.GET, bearer(token), String.class);

        // THE FAILING CASE. isAdmin was false for SUPPORT, so the ownership
        // check ran against the agent's own customer id and the order came
        // back as "not found" - to the one role that exists to look orders up.
        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "a support agent could not open a customer's order: " + response.getBody());
    }

    @Test
    @DisplayName("SUPER_ADMIN - the owner - can open any order")
    void superAdminCanOpenACustomersOrder() {
        Long orderId = someoneElsesOrder();
        String token = tokenForRole("SUPER_ADMIN");

        ResponseEntity<String> response = rest.exchange(
                url("/api/orders/" + orderId), HttpMethod.GET, bearer(token), String.class);

        // The owner of the shop, locked out of an order by a string compare
        // against a role name that is not theirs.
        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "the shop owner could not open an order: " + response.getBody());
    }

    @Test
    @DisplayName("MANAGER can open any order")
    void managerCanOpenACustomersOrder() {
        Long orderId = someoneElsesOrder();
        String token = tokenForRole("MANAGER");

        ResponseEntity<String> response = rest.exchange(
                url("/api/orders/" + orderId), HttpMethod.GET, bearer(token), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
    }

    @Test
    @DisplayName("a plain customer still cannot open somebody else's order")
    void customersStillCannotReadOtherPeoplesOrders() {
        Long orderId = someoneElsesOrder();
        String token = tokenForRole("CUSTOMER");

        // THE PROTECTION MUST SURVIVE THE FIX. Widening "who counts as staff"
        // must not widen it to everyone - this is the assertion that stops
        // the fix from being "always treat the caller as an admin".
        ResponseEntity<String> response = rest.exchange(
                url("/api/orders/" + orderId), HttpMethod.GET, bearer(token), String.class);

        assertNotEquals(HttpStatus.OK, response.getStatusCode(),
                "a customer read another customer's order: " + response.getBody());
    }
}
