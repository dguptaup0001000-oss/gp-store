package com.gpstore.order;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * An order with a NULL payment_status must not take out the order lists.
 *
 * orders.payment_status is nullable with no default. placeOrder always sets it
 * now (COD_PENDING or PENDING), but it did not always do so, and no migration
 * ever backfilled the rows created before that - in a real database 476 of 728
 * orders held NULL.
 *
 * toOrderResponse called order.getPaymentStatus().name() unguarded, and it
 * backs three endpoints: the admin order list, the admin per-customer order
 * list, and the customer's OWN order history. One legacy row made all three
 * answer 500 - the admin could not open the orders screen at all.
 *
 * NO EXISTING TEST COULD HAVE CAUGHT THIS, and that is the point of writing it
 * this way. Every order the suite creates goes through placeOrder, so every
 * order the suite has ever seen has a payment status. This test inserts the
 * shape the database actually contains instead: a row with NULL in that
 * column. Found by probing a running instance against real data.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
class LegacyOrderNullStatusTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    private Long legacyOrderId;
    private Long customerId;

    /**
     * An order exactly as the pre-payment-tracking code left it.
     *
     * CREATES ITS OWN CUSTOMER rather than reusing whatever is in the table.
     * The first version read min(id) from customers and passed only because
     * other test classes happened to run first and leave rows behind - on a
     * fresh CI database, with a different class order, it failed with "test
     * database has no customers". A test that depends on another test's
     * leftovers is not a test, it is a coincidence.
     */
    private void insertLegacyOrder() {
        jdbc.update("""
                INSERT INTO customers (full_name, email, mobile_number, password, role, active)
                VALUES ('Legacy Order Fixture', ?, ?, 'not-a-real-hash', 'CUSTOMER', true)
                """,
                "legacy-fixture-" + System.nanoTime() + "@example.com",
                "9" + (100000000 + (int) (Math.random() * 899999999)));
        customerId = jdbc.queryForObject(
                "SELECT id FROM customers WHERE full_name = 'Legacy Order Fixture' ORDER BY id DESC LIMIT 1",
                Long.class);
        assertNotNull(customerId, "failed to create the fixture customer");

        jdbc.update("""
                INSERT INTO orders (customer_id, order_number, total_amount, order_status,
                                    payment_status, order_date)
                VALUES (?, ?, ?, 'PENDING_CONFIRMATION', NULL, now())
                """, customerId, "LEGACY-" + System.nanoTime(), new java.math.BigDecimal("249.00"));

        legacyOrderId = jdbc.queryForObject(
                "SELECT max(id) FROM orders WHERE payment_status IS NULL", Long.class);
        assertNotNull(legacyOrderId);
    }

    @AfterEach
    void removeLegacyOrder() {
        if (legacyOrderId != null) {
            jdbc.update("DELETE FROM orders WHERE id = ?", legacyOrderId);
        }
        if (customerId != null) {
            jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("the admin order list survives an order with no payment status")
    void adminOrderListDoesNotBlowUp() throws Exception {
        insertLegacyOrder();

        MvcResult result = mockMvc.perform(get("/api/orders/admin/all?page=0&size=20")).andReturn();

        assertEquals(200, result.getResponse().getStatus(),
                "a legacy order with NULL payment_status must not 500 the admin order list; body: "
                        + result.getResponse().getContentAsString());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("the per-customer order list survives it too")
    void customerOrderListDoesNotBlowUp() throws Exception {
        insertLegacyOrder();

        MvcResult result = mockMvc.perform(
                get("/api/orders/customer/" + customerId + "?page=0&size=20")).andReturn();

        assertEquals(200, result.getResponse().getStatus(),
                "the same mapper backs this endpoint; body: " + result.getResponse().getContentAsString());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("the missing status is reported as null, not invented")
    void nullStatusIsReportedHonestly() throws Exception {
        insertLegacyOrder();

        MvcResult result = mockMvc.perform(get("/api/orders/admin/all?page=0&size=100")).andReturn();
        String body = result.getResponse().getContentAsString();

        assertEquals(200, result.getResponse().getStatus());
        // Which status a pre-payment-tracking order "really" had is not
        // knowable, and defaulting it to PENDING would show the admin a fact
        // that was never true. Null is the honest answer.
        assertTrue(body.contains("\"paymentStatus\":null"),
                "the legacy order's payment status must serialise as null rather than a guessed value; body: "
                        + body.substring(0, Math.min(600, body.length())));
    }
}
