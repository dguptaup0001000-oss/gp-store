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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * A shop's announcement reaches its own customers, and stops there.
 *
 * <p>THE WORST OF THE REMAINING LEAKS, AND IT IS A WRITE. {@code
 * POST /api/notifications/broadcast} called {@code broadcastToAll}, which
 * pushed to {@code PushNotificationService.ALL_CUSTOMERS_TOPIC} - one global
 * FCM topic - and then wrote a notification row for EVERY active customer on
 * the platform. Its only gate is BROADCAST_SEND, and {@code Role.ADMIN} holds
 * it through EVERY_SHOP_PERMISSION, so every shop owner had it.
 *
 * <p>So a phone shop that opened this morning could put a push notification on
 * the lock screen of every GP-STORE customer, including people who have only
 * ever bought atta from the kirana and have never heard of them. A leak you can
 * read is bad; a write that reaches every user of the platform and cannot be
 * recalled is worse, and the admin app has a screen wired straight to it.
 *
 * <p>TWO READ LEAKS RIDE ALONG. {@code GET /api/notifications} was
 * {@code findAll()} - every notification ever sent to anyone, each carrying the
 * order number and status it was about - and {@code GET /api/wishlists} was
 * {@code findAll()} too, which is every customer's saved list across the
 * marketplace. Neither is called by any app today, which is exactly why they
 * were never noticed.
 */
@SpringBootTest(properties = {
        "platform.mode=MULTI_SHOP_PRODUCTION",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@DisplayName("A merchant reaches only their own customers")
class AMerchantReachesOnlyTheirCustomersTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;
    @Autowired private MerchantLifecycleService merchantLifecycle;
    @Autowired private ShopLifecycleService shopLifecycle;

    private final String tag = "bcast" + System.nanoTime();

    private long phoneShop;
    private long sareeShop;
    private Long phoneMerchant;
    private Long sareeMerchant;
    private Long phoneOwner;
    private Long sareeOwner;
    private Long phoneCategory;
    private Long sareeCategory;
    private Long phoneProduct;
    private Long sareeProduct;
    /** Bought from the phone shop. */
    private Long phoneCustomer;
    /** Bought from the saree shop, and must never hear from the phone shop. */
    private Long sareeCustomer;
    /** Has bought from neither - a customer of the platform, nobody's to page. */
    private Long strangerCustomer;

    @BeforeEach
    void twoShopsWithOneCustomerEach() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        phoneMerchant = newMerchant("phone");
        sareeMerchant = newMerchant("saree");
        phoneShop = newShop(phoneMerchant, "BPHN-" + tag);
        sareeShop = newShop(sareeMerchant, "BSAR-" + tag);
        phoneOwner = newStaffFor(phoneShop, "phone");
        sareeOwner = newStaffFor(sareeShop, "saree");

        phoneCategory = newCategory("Broadcast Phones " + tag);
        sareeCategory = newCategory("Broadcast Sarees " + tag);
        phoneProduct = newListedProduct(phoneShop, phoneCategory, "Broadcast Phone " + tag);
        sareeProduct = newListedProduct(sareeShop, sareeCategory, "Broadcast Saree " + tag);

        phoneCustomer = newCustomer("Phone Buyer " + tag, tag + "-phonebuyer@example.test");
        sareeCustomer = newCustomer("Saree Buyer " + tag, tag + "-sareebuyer@example.test");
        strangerCustomer = newCustomer("Stranger " + tag, tag + "-stranger@example.test");

        // An order is what makes somebody a shop's customer. Order is
        // shop-owned, so "who has bought from me" is already a tenant-filtered
        // question - no new column needed to answer it.
        newOrder(phoneShop, phoneCustomer, "BCP-" + tag);
        newOrder(sareeShop, sareeCustomer, "BCS-" + tag);
    }

    @AfterEach
    void tidyUp() {
        // BY TAG, NOT BY CUSTOMER. The platform-broadcast test deliberately
        // writes one row per active customer in the database, so clearing only
        // the three this test made would leave the rest behind - and then the
        // customer deletes below fail on their foreign keys.
        jdbc.update("DELETE FROM notifications WHERE title LIKE ?", "%" + tag + "%");
        jdbc.update("DELETE FROM notifications WHERE customer_id IN (?, ?, ?)",
                phoneCustomer, sareeCustomer, strangerCustomer);
        jdbc.update("DELETE FROM wishlist WHERE product_id IN (?, ?)",
                phoneProduct, sareeProduct);
        for (long shop : new long[]{phoneShop, sareeShop}) {
            jdbc.update("DELETE FROM order_items WHERE order_id IN "
                    + "(SELECT id FROM orders WHERE shop_id = ?)", shop);
            jdbc.update("DELETE FROM orders WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM inventory WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_product_variants WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_business_hours WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_staff WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shops WHERE id = ?", shop);
        }
        jdbc.update("DELETE FROM product_variants WHERE product_id IN (?, ?)",
                phoneProduct, sareeProduct);
        jdbc.update("DELETE FROM products WHERE id IN (?, ?)", phoneProduct, sareeProduct);
        jdbc.update("DELETE FROM categories WHERE id IN (?, ?)", phoneCategory, sareeCategory);
        jdbc.update("DELETE FROM merchants WHERE id in (?, ?)", phoneMerchant, sareeMerchant);
        jdbc.update("DELETE FROM customers WHERE id in (?, ?, ?, ?, ?)",
                phoneOwner, sareeOwner, phoneCustomer, sareeCustomer, strangerCustomer);
        // RESTORES THE CONFIGURED MODE, NOT SINGLE_SHOP.
        //
        // TenantDefaults is a static global, so whatever a teardown installs is
        // what the NEXT test class inherits until its own setup runs. Putting
        // SINGLE_SHOP back - copied from tests written when that was the
        // default - left the JVM in a mode the deployment no longer runs, and
        // the next class to touch a shop-scoped route got a tenant refusal it
        // had done nothing to deserve. Restoring what is actually configured
        // leaves the global where the application put it.
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the announcement")
    class TheAnnouncement {

        @Test
        @DisplayName("reaches the shop's own customers")
        void reachesMyOwnCustomers() throws Exception {
            broadcastAs(phoneOwner, "Phone sale " + tag, "half price today");

            assertEquals(1, notificationsFor(phoneCustomer),
                    "somebody who has bought from this shop is exactly who an "
                            + "announcement is for");
        }

        @Test
        @DisplayName("does not reach another shop's customer")
        void doesNotReachAnotherShopsCustomer() throws Exception {
            broadcastAs(phoneOwner, "Phone sale " + tag, "half price today");

            assertEquals(0, notificationsFor(sareeCustomer),
                    "a phone shop putting a notification on the lock screen of somebody "
                            + "who has only ever bought a saree is the platform's users being "
                            + "spent as one merchant's mailing list");
        }

        @Test
        @DisplayName("does not reach a customer who has bought from nobody")
        void doesNotReachAStranger() throws Exception {
            broadcastAs(phoneOwner, "Phone sale " + tag, "half price today");

            assertEquals(0, notificationsFor(strangerCustomer),
                    "an account that has never ordered belongs to the platform, not to "
                            + "whichever merchant broadcasts first");
        }

        @Test
        @DisplayName("and the count reported back is the shop's own, not the platform's")
        void theCountIsHonest() throws Exception {
            String body = broadcastAs(phoneOwner, "Phone sale " + tag, "half price");

            assertTrue(body.contains("1 customer"),
                    "the reply told the merchant how many people it had reached, and it "
                            + "was counting the whole platform. A number a merchant cannot "
                            + "act on is worse than none: " + body);
        }

        @Test
        @DisplayName("a shop with no customers yet reaches nobody, and is told so")
        void aShopWithNoCustomersReachesNobody() throws Exception {
            long freshMerchant = newMerchant("fresh");
            long freshShop = newShop(freshMerchant, "BFSH-" + tag);
            Long freshOwner = newStaffFor(freshShop, "fresh");
            try {
                broadcastAs(freshOwner, "Hello " + tag, "we are open");

                assertEquals(0, notificationsFor(phoneCustomer)
                                + notificationsFor(sareeCustomer)
                                + notificationsFor(strangerCustomer),
                        "a merchant who has served nobody must be able to reach nobody - "
                                + "which is the exact state a shop is in on its first day, "
                                + "and the state in which this handed them everyone");
            } finally {
                jdbc.update("DELETE FROM shop_staff WHERE shop_id = ?", freshShop);
                jdbc.update("DELETE FROM shop_business_hours WHERE shop_id = ?", freshShop);
                jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", freshShop);
                jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", freshShop);
                jdbc.update("DELETE FROM shops WHERE id = ?", freshShop);
                jdbc.update("DELETE FROM merchants WHERE id = ?", freshMerchant);
                jdbc.update("DELETE FROM customers WHERE id = ?", freshOwner);
            }
        }
    }

    @Nested
    @DisplayName("the two read surfaces nobody was watching")
    class TheQuietReads {

        @Test
        @DisplayName("the notification log is narrowed to this shop's orders")
        void notificationLogIsNarrowed() throws Exception {
            // Each shop announces, so there is a row of each kind to confuse.
            broadcastAs(phoneOwner, "Phone news " + tag, "phone message " + tag);
            broadcastAs(sareeOwner, "Saree news " + tag, "saree message " + tag);

            String seen = body(get("/api/notifications?size=200"), phoneOwner, null);

            assertFalse(seen.contains("saree message " + tag),
                    "findAll() handed every merchant every notification ever sent to "
                            + "anyone, order numbers and statuses included: " + seen);
        }

        @Test
        @DisplayName("the wishlist log is narrowed to this shop's shelf")
        void wishlistsAreNarrowed() throws Exception {
            jdbc.update("INSERT INTO wishlist (customer_id, product_id, active) "
                    + "VALUES (?, ?, true)", sareeCustomer, sareeProduct);

            String seen = body(get("/api/wishlists?size=200"), phoneOwner, null);

            assertFalse(seen.contains("Broadcast Saree " + tag),
                    "what the marketplace's customers are saving up for is not one "
                            + "merchant's to page through: " + seen);
        }
    }

    @Nested
    @DisplayName("the platform's own reach")
    class ThePlatformReach {

        @Test
        @DisplayName("Super Admin still announces to the whole platform")
        void platformKeepsTheGlobalBroadcast() throws Exception {
            // PRESERVED DELIBERATELY. GP-STORE itself has things to tell
            // everybody; taking that away would be a different bug.
            String body = bodyAs(post("/api/notifications/broadcast"), phoneOwner,
                    Role.SUPER_ADMIN,
                    "{\"title\":\"Platform notice " + tag + "\",\"message\":\"everybody\"}");
            assertTrue(body.contains("customer"), "the platform still broadcasts: " + body);

            assertTrue(notificationsFor(strangerCustomer) >= 1,
                    "including to a customer no merchant has served");
        }
    }

    // ------------------------------------------------------------------

    private String broadcastAs(Long accountId, String title, String message) throws Exception {
        return body(post("/api/notifications/broadcast"), accountId,
                "{\"title\":\"" + title + "\",\"message\":\"" + message + "\"}");
    }

    /**
     * Broadcast persistence runs on an executor, so the row may land just after
     * the response. Polled rather than slept on, so the test is neither flaky
     * nor slower than it has to be.
     */
    private int notificationsFor(Long customerId) throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM notifications WHERE customer_id = ? "
                            + "AND title LIKE ?",
                    Integer.class, customerId, "%" + tag + "%");
            if (count != null && count > 0) {
                return count;
            }
            Thread.sleep(50);
        }
        return 0;
    }

    private Long newMerchant(String kind) {
        Long id = merchantLifecycle.register(
                "Broadcast Merchant " + kind + " " + tag, "Broadcast Shop",
                null, null, null, true).getId();
        merchantLifecycle.transition(id, MerchantStatus.PENDING_REVIEW, "submitted");
        merchantLifecycle.transition(id, MerchantStatus.APPROVED, "checked");
        merchantLifecycle.transition(id, MerchantStatus.ACTIVE, "trading");
        return id;
    }

    private long newShop(Long merchant, String code) {
        long id = shopLifecycle.open(merchant, code, "Broadcast Shop",
                12.9716, 77.5946, new java.math.BigDecimal("5"), "Asia/Kolkata").getId();
        shopLifecycle.transitionAsPlatform(id, ShopStatus.ACTIVE, "ready to trade");
        return id;
    }

    private Long newStaffFor(long shop, String kind) {
        Long id = newCustomer("Owner " + kind + " " + tag, tag + "-" + kind + "@example.test");
        jdbc.update("""
                INSERT INTO shop_staff (shop_id, customer_id, is_default, active)
                VALUES (?, ?, true, true)
                """, shop, id);
        return id;
    }

    private Long newCustomer(String name, String email) {
        jdbc.update("""
                INSERT INTO customers (full_name, email, mobile_number, password, role, active)
                VALUES (?, ?, ?, 'not-a-real-hash', 'ADMIN', true)
                """, name, email,
                "9" + (100000000 + (int) (Math.random() * 899999999)));
        return jdbc.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
    }

    private Long newCategory(String name) {
        jdbc.update("INSERT INTO categories (name, description, active) VALUES (?, ?, true)",
                name, name + " department");
        return jdbc.queryForObject("SELECT id FROM categories WHERE name = ?", Long.class, name);
    }

    private Long newListedProduct(long shop, Long category, String name) {
        jdbc.update("INSERT INTO products (name, brand, category_id, active) "
                + "VALUES (?, 'Test', ?, true)", name, category);
        Long productId = jdbc.queryForObject(
                "SELECT id FROM products WHERE name = ?", Long.class, name);
        jdbc.update("INSERT INTO product_variants "
                + "(product_id, unit, selling_price, mrp, available, active) "
                + "VALUES (?, 'one', 100, 120, true, true)", productId);
        Long variantId = jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE product_id = ?", Long.class, productId);
        jdbc.update("INSERT INTO shop_product_variants "
                + "(shop_id, product_variant_id, selling_price, mrp, available, active) "
                + "VALUES (?, ?, 100, 120, true, true)", shop, variantId);
        return productId;
    }

    /** An order is what makes somebody this shop's customer. */
    private void newOrder(long shop, Long customerId, String orderNumber) {
        jdbc.update("INSERT INTO orders (shop_id, customer_id, order_number, order_status, "
                + "total_amount, order_date) "
                + "VALUES (?, ?, ?, 'DELIVERED', 100, now())",
                shop, customerId, orderNumber);
    }

    private String body(MockHttpServletRequestBuilder request, Long accountId, String json)
            throws Exception {
        MvcResult result = send(request, accountId, json);
        int status = result.getResponse().getStatus();
        String content = result.getResponse().getContentAsString();
        assertTrue(status >= 200 && status < 300,
                request + " returned " + status + ": " + content);
        return content;
    }

    private String bodyAs(MockHttpServletRequestBuilder request, Long accountId, Role role,
                          String json) throws Exception {
        request.with(authentication(tokenFor(accountId, role)));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        assertTrue(result.getResponse().getStatus() < 300,
                request + " returned " + result.getResponse().getStatus() + ": "
                        + result.getResponse().getContentAsString());
        return result.getResponse().getContentAsString();
    }

    private MvcResult send(MockHttpServletRequestBuilder request, Long accountId, String json)
            throws Exception {
        request.with(authentication(tokenFor(accountId, Role.ADMIN)));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request).andReturn();
    }

    private UsernamePasswordAuthenticationToken tokenFor(Long accountId, Role role) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String authority : RolePermissions.authorityNames(role)) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(accountId, tag + "@example.test", role.name()),
                null, authorities);
    }
}
