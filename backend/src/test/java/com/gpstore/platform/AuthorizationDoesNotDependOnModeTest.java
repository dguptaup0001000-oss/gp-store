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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * GP-STORE as production actually runs it: {@code platform.mode} unset.
 *
 * <p>WHY THIS FILE SETS NO MODE, AND THAT IS THE POINT. Every other multi-shop
 * test in this repository pins {@code platform.mode=MULTI_SHOP_PRODUCTION} in
 * its {@code @SpringBootTest} properties - which is honest about what it is
 * testing and means NONE of them ever exercised the configuration production
 * boots with. The mode is set nowhere in this repository, so for a year the
 * marketplace's isolation was proved under a setting the live system was not
 * running.
 *
 * <p>This file deliberately declares no mode, so it runs whatever an unset
 * {@code PLATFORM_MODE} produces. If somebody reintroduces a permissive
 * default, these tests are what fails.
 *
 * <p>WHAT IT ASSERTS. Not "the mode is X" - that is
 * {@link PlatformModeConfigurationTest}'s job - but that the answers a merchant
 * gets do not depend on the mode at all. Adding the second shop, or the
 * thousandth, must not change who may read what. The scenarios are the ones
 * that actually differ: one shop, two shops, two merchants, one merchant with
 * two shops, a direct-id attack across the boundary, and the platform owner who
 * legitimately spans all of it.
 */
@SpringBootTest(properties = {
        // NO platform.mode HERE ON PURPOSE - see the class comment.
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@DisplayName("Authorization does not depend on how many shops there are")
class AuthorizationDoesNotDependOnModeTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;
    @Autowired private MerchantLifecycleService merchantLifecycle;
    @Autowired private ShopLifecycleService shopLifecycle;

    private final String tag = "mode" + System.nanoTime();

    private Long merchantA;
    private Long merchantB;
    private long shopA1;
    private long shopA2;
    private long shopB1;
    private Long ownerA;
    private Long ownerB;
    private Long superAdmin;
    private Long categoryA;
    private Long categoryB;
    private Long productA;
    private Long productB;

    @BeforeEach
    void twoMerchantsAndThreeShops() {
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        // MERCHANT A OWNS TWO SHOPS, which is the case §"one merchant may own
        // multiple authorized shops" names and the one most likely to be got
        // wrong: their own second shop must be reachable, and nobody else's.
        merchantA = newMerchant("a");
        merchantB = newMerchant("b");
        shopA1 = newShop(merchantA, "MA1-" + tag);
        shopA2 = newShop(merchantA, "MA2-" + tag);
        shopB1 = newShop(merchantB, "MB1-" + tag);

        ownerA = newStaffFor(shopA1, "a");
        // The same person on their second shop's roster, not a second account.
        jdbc.update("""
                INSERT INTO shop_staff (shop_id, customer_id, is_default, active)
                VALUES (?, ?, false, true)
                """, shopA2, ownerA);
        ownerB = newStaffFor(shopB1, "b");
        superAdmin = newCustomer("Platform Owner " + tag, tag + "-super@example.test");

        categoryA = newCategory("Mode Cat A " + tag);
        categoryB = newCategory("Mode Cat B " + tag);
        productA = newListedProduct(shopA1, categoryA, "Mode Product A " + tag);
        productB = newListedProduct(shopB1, categoryB, "Mode Product B " + tag);
    }

    @AfterEach
    void tidyUp() {
        for (long shop : new long[]{shopA1, shopA2, shopB1}) {
            jdbc.update("DELETE FROM inventory WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_product_variants WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_business_hours WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_staff WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shops WHERE id = ?", shop);
        }
        jdbc.update("DELETE FROM product_variants WHERE product_id IN (?, ?)", productA, productB);
        jdbc.update("DELETE FROM products WHERE id IN (?, ?)", productA, productB);
        jdbc.update("DELETE FROM categories WHERE id IN (?, ?)", categoryA, categoryB);
        jdbc.update("DELETE FROM merchants WHERE id in (?, ?)", merchantA, merchantB);
        jdbc.update("DELETE FROM customers WHERE id in (?, ?, ?)", ownerA, ownerB, superAdmin);
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the configuration production actually boots with")
    class TheLiveConfiguration {

        @Test
        @DisplayName("an unset mode runs the marketplace")
        void unsetIsTheMarketplace() {
            assertTrue(platform.getMode().isMultiShop(),
                    "production sets platform.mode nowhere. If that resolves to SINGLE_SHOP "
                            + "then every isolation test in this repository has been proving "
                            + "a configuration the live system does not run.");
        }
    }

    @Nested
    @DisplayName("one merchant, many shops")
    class OneMerchantManyShops {

        @Test
        @DisplayName("a merchant reaches their own second shop")
        void ownSecondShopIsReachable() throws Exception {
            String profile = body(get("/api/shop/profile").header("X-Shop-Id", shopA2),
                    ownerA, null);
            assertTrue(profile.contains("MA2-" + tag),
                    "owning two shops must mean being able to act in both: " + profile);
        }

        @Test
        @DisplayName("and not another merchant's, however they ask")
        void anotherMerchantsShopIsRefused() throws Exception {
            MvcResult result = send(get("/api/shop/profile").header("X-Shop-Id", shopB1),
                    ownerA, null);
            assertTrue(result.getResponse().getStatus() >= 400,
                    "a shop id in a header may NARROW to something the credential already "
                            + "permits and may never grant. Got "
                            + result.getResponse().getStatus() + ": "
                            + result.getResponse().getContentAsString());
        }
    }

    @Nested
    @DisplayName("direct-id attacks across the boundary")
    class DirectIdAttacks {

        @Test
        @DisplayName("merchant A cannot price merchant B's listing by naming its variant")
        void cannotPriceAcrossTheBoundary() throws Exception {
            Long variantB = jdbc.queryForObject(
                    "SELECT id FROM product_variants WHERE product_id = ?", Long.class, productB);

            send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .put("/api/shop/listings/" + variantB),
                    ownerA, "{\"sellingPrice\":1}");

            // The assertion is on the DATA, not the status. A 200 that wrote
            // into merchant A's own shop is a pass; a 200 that moved merchant
            // B's price is the bug, and only the row can tell them apart.
            java.math.BigDecimal priceB = jdbc.queryForObject(
                    "SELECT selling_price FROM shop_product_variants "
                            + "WHERE shop_id = ? AND product_variant_id = ?",
                    java.math.BigDecimal.class, shopB1, variantB);
            assertEquals(0, priceB.compareTo(new java.math.BigDecimal("100")),
                    "merchant B's price must be untouched by merchant A naming their "
                            + "variant id directly. Found " + priceB);
        }

        @Test
        @DisplayName("merchant A's product list never contains merchant B's product")
        void productListsDoNotCross() throws Exception {
            String mine = body(get("/api/products/admin/all"), ownerA, null);
            assertTrue(mine.contains("Mode Product A " + tag), "its own: " + mine);
            assertFalse(mine.contains("Mode Product B " + tag),
                    "with the mode unset, the shelf filter must still apply: " + mine);
        }

        @Test
        @DisplayName("a merchant removed from every roster does not land in Shop #1")
        void aRevokedStaffAccountDoesNotFallBackIntoTheFirstShop() throws Exception {
            // THE ESCALATION A BROWSE FALLBACK REOPENS IF IT IS WRITTEN LAZILY,
            // and this file caught it within the hour. An earlier version of
            // TenantResolver's shopless-browse fallback covered EVERY credential
            // that reached it, so an account whose shop_staff row had been
            // revoked stopped being refused and started resolving to Shop #1.
            // Revoking a membership does not demote the customers row, so their
            // role still said ADMIN - they would have arrived in the first
            // shop's inventory and orders holding a shopkeeper's permissions.
            //
            // The fallback now tests the ROLE, not the absence of a membership.
            jdbc.update("DELETE FROM shop_staff WHERE customer_id = ?", ownerA);
            try {
                MvcResult result = send(get("/api/inventory"), ownerA, null);
                assertTrue(result.getResponse().getStatus() >= 400,
                        "a merchant taken off every roster must be refused, not quietly "
                                + "reseated in Shop #1. Got "
                                + result.getResponse().getStatus() + ": "
                                + result.getResponse().getContentAsString());
            } finally {
                jdbc.update("INSERT INTO shop_staff (shop_id, customer_id, is_default, active) "
                        + "VALUES (?, ?, true, true)", shopA1, ownerA);
            }
        }

        @Test
        @DisplayName("a customer account gains nothing merely by being active")
        void anActiveCustomerIsNotAMerchant() throws Exception {
            Long shopper = newCustomer("Plain Shopper " + tag, tag + "-plain@example.test");
            try {
                MvcResult result = send(get("/api/shop/profile"), shopper, Role.CUSTOMER, null);
                assertTrue(result.getResponse().getStatus() >= 400,
                        "an ACTIVE customer with no shop_staff row is not the merchant of "
                                + "Shop #1, whatever the mode. Got "
                                + result.getResponse().getStatus());
            } finally {
                jdbc.update("DELETE FROM customers WHERE id = ?", shopper);
            }
        }
    }

    @Nested
    @DisplayName("the platform owner")
    class ThePlatformOwner {

        @Test
        @DisplayName("Super Admin resolves to platform scope, not to Shop #1")
        void superAdminSpansTheMarketplace() throws Exception {
            // THE BUG THE MODE WAS HIDING. Under SINGLE_SHOP, TenantResolver
            // returns before the PLATFORM_ADMIN branch is reached, so the
            // platform owner was resolved to Shop #1 like any other credential
            // with no shop of its own - and every narrowing added for merchants
            // narrowed them too.
            String all = bodyAs(get("/api/products/admin/all"), superAdmin,
                    Role.SUPER_ADMIN, null);

            assertTrue(all.contains("Mode Product A " + tag)
                            && all.contains("Mode Product B " + tag),
                    "the platform owner sees across merchants, which is the whole of their "
                            + "job: " + all);
        }
    }

    @Nested
    @DisplayName("the shared catalogue")
    class TheSharedCatalogue {

        @Test
        @DisplayName("a merchant cannot define the platform's taxonomy")
        void merchantCannotDefineTaxonomy() throws Exception {
            MvcResult result = send(post("/api/categories"), ownerA,
                    "{\"name\":\"Merchant Made " + tag + "\"}");
            assertEquals(403, result.getResponse().getStatus(),
                    "renaming or adding a department changes it for every merchant "
                            + "selling in it: " + result.getResponse().getContentAsString());
        }

        @Test
        @DisplayName("but a merchant can still add something they sell")
        void merchantCanStillStockTheirOwnShelf() throws Exception {
            // The other half, and the one a narrowing most easily breaks: a
            // rule that stopped merchants trading would be worse than the leak.
            String created = body(post("/api/shop/products"), ownerA, """
                    {"name":"Mode Added %s","brand":"Test","categoryId":%d,
                     "firstVariant":{"label":"one","sellingPrice":50,"stock":1,
                                     "commerceMode":"ONLINE_PURCHASE"}}
                    """.formatted(tag, categoryA));
            assertTrue(created.contains("Mode Added " + tag), created);

            assertTrue(body(get("/api/products/admin/all"), ownerA, null)
                            .contains("Mode Added " + tag),
                    "and it must land on their shelf");

            jdbc.update("DELETE FROM inventory WHERE product_variant_id IN "
                    + "(SELECT id FROM product_variants WHERE product_id IN "
                    + "(SELECT id FROM products WHERE name = ?))", "Mode Added " + tag);
            jdbc.update("DELETE FROM shop_product_variants WHERE product_variant_id IN "
                    + "(SELECT id FROM product_variants WHERE product_id IN "
                    + "(SELECT id FROM products WHERE name = ?))", "Mode Added " + tag);
            jdbc.update("DELETE FROM product_variants WHERE product_id IN "
                    + "(SELECT id FROM products WHERE name = ?)", "Mode Added " + tag);
            jdbc.update("DELETE FROM products WHERE name = ?", "Mode Added " + tag);
        }
    }

    @Nested
    @DisplayName("the customer's way in")
    class TheCustomersWayIn {

        @Test
        @DisplayName("an anonymous shopper can still open the catalogue")
        void anonymousBrowsingStillWorks() throws Exception {
            // THE COMPATIBILITY QUESTION THE MODE FLIP TURNS ON. Under
            // SINGLE_SHOP every request resolves implicitly to Shop #1, so an
            // anonymous caller with no account, no address and no shop header
            // gets a scope. Under multi-shop the resolver refuses to invent
            // one - correctly - and TenantContextFilter turns that into a 403.
            //
            // If this fails, defaulting to the marketplace has taken the public
            // catalogue off the internet, and no amount of correct isolation
            // makes that an acceptable trade.
            MvcResult result = mockMvc.perform(get("/api/products/feed?page=0&size=5")).andReturn();

            assertNotEquals(403, result.getResponse().getStatus(),
                    "a shopper who has not signed in must still be able to look at the "
                            + "catalogue. Body: " + result.getResponse().getContentAsString());
            assertTrue(result.getResponse().getStatus() < 400,
                    "anonymous browse returned " + result.getResponse().getStatus() + ": "
                            + result.getResponse().getContentAsString());
        }

        @Test
        @DisplayName("a signed-in customer with no address can still open the catalogue")
        void aNewCustomerWithNoAddressCanBrowse() throws Exception {
            Long fresh = newCustomer("Addressless " + tag, tag + "-noaddr@example.test");
            try {
                MvcResult result = send(get("/api/products/feed?page=0&size=5"),
                        fresh, Role.CUSTOMER, null);
                assertTrue(result.getResponse().getStatus() < 400,
                        "a customer's shop is the nearest one that delivers to their "
                                + "address; somebody who has just signed up has neither, and "
                                + "must not be locked out of the catalogue while they decide. "
                                + "Got " + result.getResponse().getStatus() + ": "
                                + result.getResponse().getContentAsString());
            } finally {
                jdbc.update("DELETE FROM customers WHERE id = ?", fresh);
            }
        }
    }

    // ------------------------------------------------------------------

    private Long newMerchant(String kind) {
        Long id = merchantLifecycle.register(
                "Mode Merchant " + kind + " " + tag, "Mode Shop",
                null, null, null, true).getId();
        merchantLifecycle.transition(id, MerchantStatus.PENDING_REVIEW, "submitted");
        merchantLifecycle.transition(id, MerchantStatus.APPROVED, "checked");
        merchantLifecycle.transition(id, MerchantStatus.ACTIVE, "trading");
        return id;
    }

    private long newShop(Long merchant, String code) {
        long id = shopLifecycle.open(merchant, code, "Mode Shop",
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
        MvcResult result = send(request, accountId, role, json);
        assertTrue(result.getResponse().getStatus() < 300,
                request + " returned " + result.getResponse().getStatus() + ": "
                        + result.getResponse().getContentAsString());
        return result.getResponse().getContentAsString();
    }

    private MvcResult send(MockHttpServletRequestBuilder request, Long accountId, String json)
            throws Exception {
        return send(request, accountId, Role.ADMIN, json);
    }

    private MvcResult send(MockHttpServletRequestBuilder request, Long accountId, Role role,
                           String json) throws Exception {
        request.with(authentication(tokenFor(accountId, role)));
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
