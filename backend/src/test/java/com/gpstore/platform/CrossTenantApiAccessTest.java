package com.gpstore.platform;

import com.gpstore.security.WithStaff;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The same isolation, proved the way it will actually be attacked: over HTTP,
 * by changing an id.
 *
 * WHY THIS EXISTS SEPARATELY FROM THE REPOSITORY TEST. A repository test
 * proves the mechanism; it does not prove the mechanism is switched on for a
 * real request. Between the two sit the servlet filter chain, the security
 * configuration, the controller and the service - and any of them could
 * resolve a scope that never reaches the query. This test starts where an
 * attacker starts: an authenticated staff session, and an id belonging to
 * somebody else.
 *
 * THE ATTACK IS NOT HYPOTHETICAL AND NOT SUBTLE. Order ids are sequential.
 * A shopkeeper with a legitimate login who subtracts one from their own order
 * id is doing the entire attack, and if the answer is 200 they are reading a
 * competitor's customer names, addresses and phone numbers.
 *
 * EVERY REFUSAL IS 404, NEVER 403. A 403 on an id you guessed confirms the id
 * exists; alternating 403 and 404 down a range of ids maps out how much
 * business the other shop is doing. "Not found" is the only answer that
 * leaks nothing.
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
@DisplayName("Changing an id in a request does not cross a shop boundary")
class CrossTenantApiAccessTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;

    private final String tag = "api" + System.nanoTime();

    private long shopA;
    private long shopB;
    private Long merchantB;
    private Long customerId;
    private long ourOrderId;
    private long otherShopsOrderId;
    private String otherShopsOrderNumber;
    private long otherShopsRiderId;
    private long otherShopsCouponId;
    private long ourStockRowId;
    private long otherShopsStockRowId;

    @BeforeEach
    void aSecondShopWithBusinessOfItsOwn() {
        shopA = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();

        Merchant second = new Merchant();
        second.setLegalName("API isolation fixture " + tag);
        second.setDisplayName("Fixture B");
        second.setStatus(MerchantStatus.ACTIVE);
        second.setIsDemo(Boolean.TRUE);
        second.setActive(Boolean.TRUE);
        merchantB = merchants.save(second).getId();

        Shop b = new Shop();
        b.setMerchantId(merchantB);
        b.setCode("API-" + tag);
        b.setDisplayName("Fixture shop B");
        b.setStatus(ShopStatus.ACTIVE);
        b.setIsDemo(Boolean.TRUE);
        b.setActive(Boolean.TRUE);
        shopB = shops.save(b).getId();

        jdbc.update("""
                INSERT INTO customers (full_name, email, mobile_number, password, role, active)
                VALUES (?, ?, ?, 'not-a-real-hash', 'CUSTOMER', true)
                """, "API fixture " + tag, tag + "@example.test",
                "9" + (100000000 + (int) (Math.random() * 899999999)));
        customerId = jdbc.queryForObject(
                "SELECT id FROM customers WHERE email = ?", Long.class, tag + "@example.test");

        // COD_PENDING, because a shop may confirm a cash order before any
        // money has moved - which is what makes this a usable positive
        // control for "the shop can still work its own order".
        ourOrderId = insertOrder("APIA-" + tag, shopA, "COD_PENDING");
        otherShopsOrderNumber = "APIB-" + tag;
        otherShopsOrderId = insertOrder(otherShopsOrderNumber, shopB, "COD_PENDING");

        jdbc.update("""
                INSERT INTO delivery_partners (name, mobile, available, active, shop_id)
                VALUES (?, ?, false, true, ?)
                """, "Other shop rider " + tag,
                "8" + (100000000 + (int) (Math.random() * 899999999)), shopB);
        otherShopsRiderId = jdbc.queryForObject(
                "SELECT id FROM delivery_partners WHERE name = ?", Long.class, "Other shop rider " + tag);

        jdbc.update("""
                INSERT INTO coupons (coupon_code, discount_value, active, shop_id)
                VALUES (?, 10.00, true, ?)
                """, "APIC" + tag, shopB);
        otherShopsCouponId = jdbc.queryForObject(
                "SELECT id FROM coupons WHERE coupon_code = ?", Long.class, "APIC" + tag);

        // A payment row per order: confirming a PENDING_CONFIRMATION order
        // asks the payment whether the shop may confirm it, and a cash order
        // with no payment record at all is refused for reasons that have
        // nothing to do with tenancy.
        insertCodPayment(ourOrderId, shopA);
        insertCodPayment(otherShopsOrderId, shopB);

        ourStockRowId = insertStock(shopA, 31);
        otherShopsStockRowId = insertStock(shopB, 32);
    }

    /**
     * Deletes children before parents, because working an order leaves a trail.
     *
     * Confirming an order writes a notification; assigning a rider writes a
     * batch. A teardown that deletes only what it inserted leaves the next run
     * looking at a foreign key violation rather than at the thing it tested.
     */
    @AfterEach
    void removeTheFixture() {
        // RETRIED, because some of what this deletes is written AFTER the
        // request that caused it returns: confirming an order queues a
        // notification through AfterCommitExecutor, which lands on another
        // thread once the transaction commits. In an isolated run the delete
        // always wins that race; in a full suite it does not, and the failure
        // is a foreign key violation in teardown rather than anything about
        // the test.
        for (int attempt = 1; ; attempt++) {
            try {
                deleteFixtureRows();
                return;
            } catch (org.springframework.dao.DataIntegrityViolationException stillReferenced) {
                if (attempt == 5) {
                    throw stillReferenced;
                }
                try {
                    Thread.sleep(200L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw stillReferenced;
                }
            }
        }
    }

    private void deleteFixtureRows() {
        jdbc.update("DELETE FROM notifications WHERE order_id in (?, ?)", ourOrderId, otherShopsOrderId);
        jdbc.update("DELETE FROM order_scan_events WHERE order_id in (?, ?)", ourOrderId, otherShopsOrderId);
        jdbc.update("DELETE FROM invoices WHERE order_id in (?, ?)", ourOrderId, otherShopsOrderId);
        jdbc.update("DELETE FROM deliveries WHERE order_id in (?, ?)", ourOrderId, otherShopsOrderId);
        jdbc.update("DELETE FROM payments WHERE order_id in (?, ?)", ourOrderId, otherShopsOrderId);
        jdbc.update("DELETE FROM inventory WHERE id in (?, ?)", ourStockRowId, otherShopsStockRowId);
        jdbc.update("DELETE FROM coupons WHERE id = ?", otherShopsCouponId);
        jdbc.update("DELETE FROM deliveries WHERE batch_id in "
                + "(SELECT id FROM delivery_batches WHERE delivery_partner_id = ?)", otherShopsRiderId);
        jdbc.update("DELETE FROM delivery_batches WHERE delivery_partner_id = ?", otherShopsRiderId);
        jdbc.update("UPDATE orders SET assigned_worker_partner_id = null, packed_by_partner_id = null "
                + "WHERE assigned_worker_partner_id = ? or packed_by_partner_id = ?",
                otherShopsRiderId, otherShopsRiderId);
        jdbc.update("DELETE FROM delivery_partners WHERE id = ?", otherShopsRiderId);
        jdbc.update("DELETE FROM orders WHERE id in (?, ?)", ourOrderId, otherShopsOrderId);
        if (customerId != null) {
            jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        }
        jdbc.update("DELETE FROM shops WHERE id = ?", shopB);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantB);
    }

    @Test
    @WithStaff
    @DisplayName("the shop can still advance its own order - the control that says isolation is not an outage")
    void ourOwnOrderStillMoves() throws Exception {
        MvcResult ours = mockMvc.perform(
                put("/api/orders/" + ourOrderId + "/status").param("status", "CONFIRMED")).andReturn();

        assertEquals(200, ours.getResponse().getStatus(),
                "the shop lost the ability to work its own order; body: "
                        + ours.getResponse().getContentAsString());
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "SELECT order_status FROM orders WHERE id = ?", String.class, ourOrderId));
    }

    @Test
    @WithStaff
    @DisplayName("PUT /api/coupons/{id} cannot rewrite another shop's offer")
    void writingToAnotherShopsCouponOverHttp() throws Exception {
        MvcResult attempt = mockMvc.perform(
                put("/api/coupons/" + otherShopsCouponId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"discountValue\":99.00,\"active\":true}")).andReturn();

        assertNotEquals(200, attempt.getResponse().getStatus(),
                "another shop's coupon was rewritten through the admin endpoint");
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM coupons WHERE id = ? AND discount_value = 99.00",
                Integer.class, otherShopsCouponId),
                "the other shop's discount was actually changed");
    }

    @Test
    @WithStaff
    @DisplayName("PUT /api/orders/{id}/status cannot advance another shop's order")
    void writingToAnotherShopsOrderOverHttp() throws Exception {
        MvcResult attempt = mockMvc.perform(
                put("/api/orders/" + otherShopsOrderId + "/status").param("status", "CONFIRMED")).andReturn();

        assertNotEquals(200, attempt.getResponse().getStatus(),
                "another shop's order was advanced through the admin endpoint");
        assertEquals("PENDING_CONFIRMATION", jdbc.queryForObject(
                "SELECT order_status FROM orders WHERE id = ?", String.class, otherShopsOrderId),
                "the other shop's order actually changed state");
    }

    @Test
    @WithStaff
    @DisplayName("GET /api/inventory/{id} answers 404 for another shop's stock row")
    void readingAnotherShopsRowByIdOverHttp() throws Exception {
        MvcResult ours = mockMvc.perform(get("/api/inventory/" + ourStockRowId)).andReturn();
        assertEquals(200, ours.getResponse().getStatus(),
                "the shop lost sight of its own stock; body: " + ours.getResponse().getContentAsString());

        MvcResult theirs = mockMvc.perform(get("/api/inventory/" + otherShopsStockRowId)).andReturn();
        assertEquals(404, theirs.getResponse().getStatus(),
                "an authenticated shopkeeper read another shop's stock row by changing the id");
        assertFalse(theirs.getResponse().getContentAsString().contains("\"stock\":32"),
                "the response leaked the other shop's stock level");
    }

    @Test
    @WithStaff
    @DisplayName("the admin order list contains no order from another shop")
    void theAdminOrderListIsScoped() throws Exception {
        MvcResult list = mockMvc.perform(get("/api/orders/admin/all?page=0&size=100")).andReturn();

        assertEquals(200, list.getResponse().getStatus());
        assertFalse(list.getResponse().getContentAsString().contains(otherShopsOrderNumber),
                "the admin order list included an order belonging to another shop");
    }

    @Test
    @WithStaff
    @DisplayName("the rider roster contains no worker from another shop")
    void theRiderRosterIsScoped() throws Exception {
        MvcResult list = mockMvc.perform(get("/api/delivery-partners?page=0&size=100")).andReturn();

        assertEquals(200, list.getResponse().getStatus());
        assertFalse(list.getResponse().getContentAsString().contains("Other shop rider " + tag),
                "the admin worker list included another shop's rider");
    }

    @Test
    @WithStaff
    @DisplayName("the coupon list contains no offer from another shop")
    void theCouponListIsScoped() throws Exception {
        MvcResult list = mockMvc.perform(get("/api/coupons?page=0&size=100")).andReturn();

        assertEquals(200, list.getResponse().getStatus());
        assertFalse(list.getResponse().getContentAsString().contains("APIC" + tag),
                "the admin coupon list included another shop's offer");
    }

    @Test
    @WithStaff
    @DisplayName("a customer's order history is scoped to the shop serving it")
    void perCustomerHistoryIsScoped() throws Exception {
        MvcResult list = mockMvc.perform(
                get("/api/orders/customer/" + customerId + "?page=0&size=50")).andReturn();

        assertEquals(200, list.getResponse().getStatus());
        assertFalse(list.getResponse().getContentAsString().contains(otherShopsOrderNumber),
                "a shop read what one of its customers bought from a different shop");
    }

    private void insertCodPayment(long orderId, long shopId) {
        jdbc.update("""
                INSERT INTO payments (order_id, amount, currency, payment_method, payment_status,
                                      payment_date, active, shop_id)
                VALUES (?, 100.00, 'INR', 'COD', 'COD_PENDING', now(), true, ?)
                """, orderId, shopId);
    }

    private long insertStock(long shopId, int stock) {
        jdbc.update("INSERT INTO inventory (stock, reserved_stock, shop_id) VALUES (?, 0, ?)", stock, shopId);
        return jdbc.queryForObject(
                "SELECT max(id) FROM inventory WHERE shop_id = ? AND stock = ?", Long.class, shopId, stock);
    }

    private long insertOrder(String number, long shopId, String paymentStatus) {
        jdbc.update("""
                INSERT INTO orders (customer_id, order_number, total_amount, order_status,
                                    payment_status, order_date, shop_id)
                VALUES (?, ?, ?, 'PENDING_CONFIRMATION', ?, now(), ?)
                """, customerId, number, new BigDecimal("100.00"), paymentStatus, shopId);
        return jdbc.queryForObject("SELECT id FROM orders WHERE order_number = ?", Long.class, number);
    }
}
