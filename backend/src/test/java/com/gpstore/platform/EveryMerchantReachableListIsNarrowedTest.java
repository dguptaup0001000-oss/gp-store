package com.gpstore.platform;

import com.gpstore.entity.Role;
import com.gpstore.security.AuthenticatedUser;
import com.gpstore.security.RolePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * The listing endpoints a merchant can reach, probed for other people's rows.
 *
 * <p>HOW THESE WERE CHOSEN, because 344 endpoints cannot each get a test. The
 * leak signature across every one found so far is the same three things at
 * once: a repository call that is {@code findAll()} or equivalent, on an entity
 * that is NOT {@code ShopOwned} (so no Hibernate filter narrows it), reached by
 * a credential that is not the platform. Entities that ARE ShopOwned - orders,
 * inventory, coupons, invoices, payments, deliveries, staff - are narrowed
 * structurally and are not the interesting cases.
 *
 * <p>Every non-ShopOwned {@code findAll} in {@code src/main/java} was listed and
 * matched to the route that reaches it. What is probed below is that list.
 *
 * <p>THE ASSERTION IS ON THE ROWS, NOT THE STATUS. A 403 is a fine answer and
 * so is a 200 with only the caller's own data; what must never happen is a 200
 * carrying another merchant's. Probing for the status alone would pass on an
 * endpoint that answers 200 with the whole platform in it.
 */
@SpringBootTest(properties = {
        // No mode set: this is the configuration production boots with.
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@DisplayName("Every list a merchant can reach is narrowed to them")
class EveryMerchantReachableListIsNarrowedTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;
    @Autowired private MerchantLifecycleService merchantLifecycle;
    @Autowired private ShopLifecycleService shopLifecycle;

    private final String tag = "probe" + System.nanoTime();

    private Long merchantA;
    private Long merchantB;
    private long shopA;
    private long shopB;
    private Long ownerA;
    private Long categoryB;
    private Long productB;
    private Long variantB;
    /** A customer of shop B only. Merchant A must not meet them anywhere. */
    private Long customerB;
    /** A customer of shop A. Narrowing must not cost merchant A their own list. */
    private Long customerA;

    @BeforeEach
    void twoShopsOneOfThemBusy() {
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        merchantA = newMerchant("a");
        merchantB = newMerchant("b");
        shopA = newShop(merchantA, "PRA-" + tag);
        shopB = newShop(merchantB, "PRB-" + tag);
        ownerA = newStaffFor(shopA, "a");

        categoryB = newCategory("Probe Cat B " + tag);
        productB = newListedProduct(shopB, categoryB, "Probe Product B " + tag);
        variantB = jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE product_id = ?", Long.class, productB);
        // A cost price is the merchant's margin. It is the single most
        // commercially sensitive number on a catalogue row.
        jdbc.update("UPDATE product_variants SET cost_price = 41.41 WHERE id = ?", variantB);

        customerB = newCustomer("Probe Buyer B " + tag, tag + "-buyerb@example.test");
        jdbc.update("INSERT INTO addresses (customer_id, house_no, area, city, "
                + "latitude, longitude, default_address) "
                + "VALUES (?, ?, 'Probeville', 'Bengaluru', 12.97, 77.59, true)",
                customerB, "Secret Lane " + tag);
        newOrder(shopB, customerB, "PRB-" + tag);

        customerA = newCustomer("Probe Buyer A " + tag, tag + "-buyera@example.test");
        newOrder(shopA, customerA, "PRA-" + tag);
    }

    @AfterEach
    void tidyUp() {
        jdbc.update("DELETE FROM order_items WHERE order_id IN "
                + "(SELECT id FROM orders WHERE shop_id IN (?, ?))", shopA, shopB);
        jdbc.update("DELETE FROM orders WHERE shop_id IN (?, ?)", shopA, shopB);
        jdbc.update("DELETE FROM addresses WHERE customer_id = ?", customerB);
        for (long shop : new long[]{shopA, shopB}) {
            jdbc.update("DELETE FROM inventory WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_product_variants WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_business_hours WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_staff WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shops WHERE id = ?", shop);
        }
        jdbc.update("DELETE FROM product_variants WHERE product_id = ?", productB);
        jdbc.update("DELETE FROM products WHERE id = ?", productB);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryB);
        jdbc.update("DELETE FROM merchants WHERE id in (?, ?)", merchantA, merchantB);
        jdbc.update("DELETE FROM customers WHERE id in (?, ?, ?)",
                ownerA, customerB, customerA);
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("what an anonymous caller can read")
    class Anonymous {

        @Test
        @DisplayName("a merchant's cost price is not on the public internet")
        void costPriceIsNotPublic() throws Exception {
            // GET /api/product-variants sits inside SecurityConfig's permitAll
            // block for the catalogue and returns the ProductVariant ENTITY,
            // cost_price column included. A cost price is the merchant's margin
            // on that line; publishing it to anyone who asks hands every
            // competitor - and every customer - the whole shop's buying terms.
            MvcResult result = mockMvc.perform(get("/api/product-variants")).andReturn();
            String body = result.getResponse().getContentAsString();

            assertFalse(body.contains("41.41") || body.contains("costPrice"),
                    "cost price reached an unauthenticated caller from "
                            + "GET /api/product-variants (status "
                            + result.getResponse().getStatus() + "). Body: " + body);
        }
    }

    @Nested
    @DisplayName("what a merchant can read about other merchants")
    class AcrossTheBoundary {

        @Test
        @DisplayName("another shop's customers are not in the customer list")
        void customerListIsNarrowed() throws Exception {
            // COUNTED, NOT SEARCHED FOR, and the first version of this test got
            // it wrong in exactly the way that matters. Looking for one known
            // customer in the body passed - because the endpoint PAGES, and the
            // marker row was not on page 0. The probe reported "narrowed" while
            // 26KB of other people's accounts came back. A probe that can pass
            // by not looking far enough is worse than no probe.
            //
            // totalElements cannot be paged past: if it equals the platform's
            // customer count, the list is the platform's.
            MvcResult result = send(get("/api/customers?page=0&size=1"), ownerA);
            String body = result.getResponse().getContentAsString();
            System.out.println("[PROBE] GET /api/customers -> "
                    + result.getResponse().getStatus() + " " + body.length() + " bytes");

            if (result.getResponse().getStatus() >= 400) {
                return; // refusing is a fine answer
            }

            long everyoneOnThePlatform = jdbc.queryForObject(
                    "SELECT count(*) FROM customers", Long.class);
            assertFalse(body.contains("\"totalElements\":" + everyoneOnThePlatform),
                    "GET /api/customers reported totalElements=" + everyoneOnThePlatform
                            + ", which is every account on GP-STORE. "
                            + "CustomerService.getAllCustomers is customerRepository"
                            + ".findAll(pageable) and Customer is not ShopOwned, so a merchant "
                            + "reading this gets every customer's name, email and phone "
                            + "number - including people who have never bought from them. "
                            + "Body: " + body);

            // NARROWED IS NOT THE SAME AS EMPTIED, and a fix that answers
            // nobody would pass the assertion above while taking a merchant's
            // own customer list away from them. Both halves are the fix.
            String mine = send(get("/api/customers?page=0&size=200"), ownerA)
                    .getResponse().getContentAsString();
            assertTrue(mine.contains("Probe Buyer A " + tag),
                    "the merchant's OWN customer - somebody who placed an order with "
                            + "them - is missing from GET /api/customers. Narrowing must "
                            + "not empty the list. Body: " + mine);
            assertFalse(mine.contains("Probe Buyer B " + tag),
                    "a customer of another shop is in this merchant's customer list. "
                            + "Body: " + mine);
        }

        @Test
        @DisplayName("another shop's customers' home addresses are not in the address list")
        void addressListIsNarrowed() throws Exception {
            assertNoCrossTenantRows("/api/addresses?page=0&size=100",
                    "Secret Lane " + tag,
                    "AddressService.getAll is an unfiltered page and Address is not "
                            + "ShopOwned. A home address is the most sensitive row a "
                            + "grocery platform holds");
        }

        @Test
        @DisplayName("another shop's baskets are not in the cart list")
        void cartListIsNarrowed() throws Exception {
            assertNoCrossTenantRows("/api/carts?page=0&size=100",
                    "Probe Product B " + tag,
                    "CartService reads cartRepository.findAll(pageable) and Cart is not "
                            + "ShopOwned");
        }

        @Test
        @DisplayName("another shop's order lines are not in the order-item list")
        void orderItemListIsNarrowed() throws Exception {
            assertNoCrossTenantRows("/api/order-items",
                    "Probe Product B " + tag,
                    "OrderItemService.getAll is findAll(capped) and OrderItem is not "
                            + "ShopOwned even though its Order is");
        }

        @Test
        @DisplayName("the variant list never carries a cost price, whoever asks")
        void variantListDoesNotCarryCost() throws Exception {
            // Same correction as customerListIsNarrowed: searched for one known
            // number, and that number was not on the page that came back. The
            // question is not "is MY cost price here" but "does this shape
            // carry cost prices at all", which no amount of paging hides.
            MvcResult result = send(get("/api/product-variants"), ownerA);
            String body = result.getResponse().getContentAsString();
            System.out.println("[PROBE] GET /api/product-variants -> "
                    + result.getResponse().getStatus() + " " + body.length() + " bytes");

            assertFalse(body.contains("costPrice"),
                    "GET /api/product-variants returns ProductVariant entities and this "
                            + "body carries costPrice - a merchant's margin on every line. "
                            + "Body: " + body.substring(0, Math.min(body.length(), 800)));
        }

        @Test
        @DisplayName("another shop's audit trail is not in the audit log")
        void auditLogIsNarrowed() throws Exception {
            // Audit rows name what a merchant did. AuditLogTenantIsolationTest
            // already covers this; probed here so the sweep is complete rather
            // than assumed.
            MvcResult result = send(get("/api/audit-logs?page=0&size=50"), ownerA);
            String body = result.getResponse().getContentAsString();
            assertFalse(body.contains("PRB-" + tag),
                    "another shop's audit entries reached this merchant: " + body);
        }
    }

    /**
     * Reads the endpoint as merchant A and insists the marker row is absent.
     *
     * <p>A refusal counts as absent: 403 is a perfectly good way to keep
     * somebody out of a list. What is not acceptable is 200 with the row in it.
     */
    private void assertNoCrossTenantRows(String path, String marker, String why)
            throws Exception {
        MvcResult result = send(get(path), ownerA);
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();

        // RECORDED, NOT JUST ASSERTED. A probe that only checks the marker is
        // absent cannot tell a refusal from a narrowed answer from an endpoint
        // that happened to return nothing - and "passed for an unknown reason"
        // is not an audit result. The status goes in the log either way.
        System.out.println("[PROBE] GET " + path + " -> " + status
                + " (" + (body.length() > 0 ? body.length() + " bytes" : "empty") + ")");

        assertFalse(body.contains(marker),
                "GET " + path + " answered " + status + " carrying another merchant's row ("
                        + marker + "). " + why + ". Body: "
                        + body.substring(0, Math.min(body.length(), 1200)));
        assertTrue(status < 500,
                "GET " + path + " answered " + status + ", which is a crash rather than a "
                        + "decision: " + body);
    }

    // ------------------------------------------------------------------

    private Long newMerchant(String kind) {
        Long id = merchantLifecycle.register(
                "Probe Merchant " + kind + " " + tag, "Probe Shop",
                null, null, null, true).getId();
        merchantLifecycle.transition(id, MerchantStatus.PENDING_REVIEW, "submitted");
        merchantLifecycle.transition(id, MerchantStatus.APPROVED, "checked");
        merchantLifecycle.transition(id, MerchantStatus.ACTIVE, "trading");
        return id;
    }

    private long newShop(Long merchant, String code) {
        long id = shopLifecycle.open(merchant, code, "Probe Shop",
                12.9716, 77.5946, new java.math.BigDecimal("5"), "Asia/Kolkata").getId();
        shopLifecycle.transitionAsPlatform(id, ShopStatus.ACTIVE, "ready to trade");
        return id;
    }

    private Long newStaffFor(long shop, String kind) {
        Long id = newCustomer("Owner " + kind + " " + tag, tag + "-" + kind + "@example.test");
        jdbc.update("INSERT INTO shop_staff (shop_id, customer_id, is_default, active) "
                + "VALUES (?, ?, true, true)", shop, id);
        return id;
    }

    private Long newCustomer(String name, String email) {
        jdbc.update("INSERT INTO customers (full_name, email, mobile_number, password, role, active) "
                        + "VALUES (?, ?, ?, 'not-a-real-hash', 'ADMIN', true)",
                name, email, "9" + (100000000 + (int) (Math.random() * 899999999)));
        return jdbc.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
    }

    private Long newCategory(String name) {
        jdbc.update("INSERT INTO categories (name, description, active) VALUES (?, ?, true)",
                name, name + " department");
        return jdbc.queryForObject("SELECT id FROM categories WHERE name = ?", Long.class, name);
    }

    private Long newListedProduct(long shop, Long category, String name) {
        jdbc.update("INSERT INTO products (name, brand, category_id, active) "
                + "VALUES (?, 'Probe', ?, true)", name, category);
        Long productId = jdbc.queryForObject(
                "SELECT id FROM products WHERE name = ?", Long.class, name);
        jdbc.update("INSERT INTO product_variants "
                + "(product_id, unit, selling_price, mrp, available, active) "
                + "VALUES (?, 'one', 100, 120, true, true)", productId);
        Long v = jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE product_id = ?", Long.class, productId);
        jdbc.update("INSERT INTO shop_product_variants "
                + "(shop_id, product_variant_id, selling_price, mrp, available, active, commerce_mode) "
                + "VALUES (?, ?, 100, 120, true, true, 'ONLINE_PURCHASE')", shop, v);
        return productId;
    }

    private void newOrder(long shop, Long customerId, String orderNumber) {
        jdbc.update("INSERT INTO orders (shop_id, customer_id, order_number, order_status, "
                        + "total_amount, order_date) VALUES (?, ?, ?, 'DELIVERED', 100, now())",
                shop, customerId, orderNumber);
    }

    private MvcResult send(MockHttpServletRequestBuilder request, Long accountId) throws Exception {
        request.with(authentication(tokenFor(accountId)));
        return mockMvc.perform(request).andReturn();
    }

    private UsernamePasswordAuthenticationToken tokenFor(Long accountId) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String authority : RolePermissions.authorityNames(Role.ADMIN)) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(accountId, tag + "@example.test", Role.ADMIN.name()),
                null, authorities);
    }
}
