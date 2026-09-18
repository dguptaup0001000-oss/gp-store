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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * A phone shop adds a phone, and can then see the phone.
 *
 * <p>WHAT A REAL DEVICE FOUND, and no automated test did. A newly onboarded
 * merchant - Deepak Phone Shop - opened Add Product, typed a name, a brand and
 * a category, and tapped Create Product. The API answered 200. The screen
 * popped back to Products. Products said "No products yet", and went on saying
 * it after a refresh, after a restart, forever.
 *
 * <p>THE DEEPER CAUSE IS A ROUTE THAT DID NOT EXIST. A marketplace merchant had
 * no way to introduce something new at all: {@code PUT /api/shop/listings/{id}}
 * prices a variant that already exists centrally, and {@code POST /api/products}
 * is the platform's catalogue (CATALOG_DEFINE). Production runs with
 * {@code platform.mode} unset - SINGLE_SHOP - where that guard falls back to
 * CATALOG_MANAGE, so Add Product reached the platform route after all and wrote
 * one row: the central catalogue entry. The merchant's list asks a different
 * question:
 * {@code findAllListedForCurrentShop} returns products having a variant that
 * THIS shop has a {@code shop_product_variants} row for. A catalogue row with
 * no variant cannot satisfy that EXISTS, so the product was invisible from the
 * instant it was written - and being invisible, it could not be opened to add
 * the variant that would have made it visible. The merchant was stuck in a loop
 * with no error message anywhere in it.
 *
 * <p>THE FIRST TEST IN THIS FILE IS THE ONE THAT MATTERS. Everything else here
 * guards a property; {@code createdProductIsOnTheShelfImmediately} reproduces
 * the customer-visible failure exactly - create, then list, then assert the
 * thing is there. It fails against the old code for the real reason rather than
 * an approximation of it.
 */
@SpringBootTest(properties = {
        // Without this the resolver short-circuits to Shop #1 and both shops
        // below are the same shop under two names - see AShopStocksItsOwnShelfTest.
        "platform.mode=MULTI_SHOP_PRODUCTION",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@DisplayName("A merchant adds what they actually sell")
class AMerchantAddsWhatTheySellTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;
    @Autowired private MerchantLifecycleService merchantLifecycle;
    @Autowired private ShopLifecycleService shopLifecycle;

    private final String tag = "addp" + System.nanoTime();

    private long phoneShop;
    private long sareeShop;
    private Long phoneMerchant;
    private Long sareeMerchant;
    private Long phoneOwner;
    private Long sareeOwner;
    private Long phoneCategory;
    private Long sareeCategory;

    /**
     * TWO SHOPS THIS TEST MADE, AND SHOP #1 IS NOT ONE OF THEM - the same rule
     * AShopStocksItsOwnShelfTest records, and for the same reason: a fixture
     * that tears down by tenant would empty the live shop in a shared database.
     * Nothing here reads or writes shop #1.
     */
    @BeforeEach
    void twoMerchantsInDifferentTrades() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        phoneMerchant = newMerchant("phone");
        sareeMerchant = newMerchant("saree");
        phoneShop = newShop(phoneMerchant, "PHN-" + tag);
        sareeShop = newShop(sareeMerchant, "SAR-" + tag);
        phoneOwner = newStaffFor(phoneShop, "phone");
        sareeOwner = newStaffFor(sareeShop, "saree");

        // Categories are PLATFORM taxonomy - one tree both shops choose from.
        // That is the architecture under test, not an accident of the fixture:
        // a shop trades in a category by listing something in it, never by
        // owning the category row.
        phoneCategory = newCategory("Mobile Phones " + tag);
        sareeCategory = newCategory("Sarees " + tag);
    }

    @AfterEach
    void tidyUp() {
        for (long shop : new long[]{phoneShop, sareeShop}) {
            jdbc.update("DELETE FROM inventory WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_product_variants WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_business_hours WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_staff WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shops WHERE id = ?", shop);
        }
        // Catalogue rows this test created, deepest first.
        jdbc.update("DELETE FROM inventory WHERE product_variant_id IN "
                + "(SELECT v.id FROM product_variants v JOIN products p ON p.id = v.product_id "
                + "WHERE p.category_id IN (?, ?))", phoneCategory, sareeCategory);
        jdbc.update("DELETE FROM shop_product_variants WHERE product_variant_id IN "
                + "(SELECT v.id FROM product_variants v JOIN products p ON p.id = v.product_id "
                + "WHERE p.category_id IN (?, ?))", phoneCategory, sareeCategory);
        jdbc.update("DELETE FROM product_variants WHERE product_id IN "
                + "(SELECT id FROM products WHERE category_id IN (?, ?))",
                phoneCategory, sareeCategory);
        jdbc.update("DELETE FROM products WHERE category_id IN (?, ?)",
                phoneCategory, sareeCategory);
        jdbc.update("DELETE FROM categories WHERE id IN (?, ?)", phoneCategory, sareeCategory);
        jdbc.update("DELETE FROM merchants WHERE id in (?, ?)", phoneMerchant, sareeMerchant);
        jdbc.update("DELETE FROM customers WHERE id in (?, ?)", phoneOwner, sareeOwner);
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
    @DisplayName("the loop a real merchant got stuck in")
    class TheStuckLoop {

        @Test
        @DisplayName("a product a merchant creates is on their shelf immediately")
        void createdProductIsOnTheShelfImmediately() throws Exception {
            assertEquals("[]", myProducts(phoneOwner).trim(),
                    "a shop that has listed nothing starts empty");

            String created = body(post("/api/shop/products"), phoneOwner, """
                    {"name":"Motorola Edge 50 Pro","brand":"Motorola",
                     "categoryId":%d,
                     "firstVariant":{"label":"12 GB + 256 GB","sellingPrice":29999,
                                     "mrp":35999,"stock":4}}
                    """.formatted(phoneCategory));
            assertTrue(created.contains("Motorola Edge 50 Pro"),
                    "the create must answer with what it made: " + created);

            // THE ASSERTION THE OLD CODE FAILED. Everything above it passed
            // before this fix too - the 200 was never the problem.
            String listed = myProducts(phoneOwner);
            assertTrue(listed.contains("Motorola Edge 50 Pro"),
                    "the merchant created this and the list must show it. This is the "
                            + "exact failure a real device hit: 200 from create, "
                            + "\"No products yet\" from the list. Body: " + listed);
        }

        @Test
        @DisplayName("the four rows are written, not just the catalogue one")
        void catalogueVariantListingAndStockAllExist() throws Exception {
            body(post("/api/shop/products"), phoneOwner, """
                    {"name":"Edge 50 Fusion","brand":"Motorola","categoryId":%d,
                     "firstVariant":{"label":"8 GB + 128 GB","sellingPrice":22999,"stock":7}}
                    """.formatted(phoneCategory));

            Long productId = jdbc.queryForObject(
                    "SELECT id FROM products WHERE name = 'Edge 50 Fusion'", Long.class);
            assertNotNull(productId, "no catalogue row");

            Long variantId = jdbc.queryForObject(
                    "SELECT id FROM product_variants WHERE product_id = ?", Long.class, productId);
            assertNotNull(variantId,
                    "no variant - which is precisely what made the product invisible");

            Integer listings = jdbc.queryForObject(
                    "SELECT count(*) FROM shop_product_variants WHERE shop_id = ? "
                            + "AND product_variant_id = ?",
                    Integer.class, phoneShop, variantId);
            assertEquals(1, listings,
                    "the shop listing is the row the merchant's list and the customer's "
                            + "storefront both read; without it the product exists and is sold "
                            + "by nobody");

            Integer stock = jdbc.queryForObject(
                    "SELECT stock FROM inventory WHERE shop_id = ? AND product_variant_id = ?",
                    Integer.class, phoneShop, variantId);
            assertEquals(7, stock, "opening stock must be what the merchant typed");
        }

        @Test
        @DisplayName("it is still there on the next launch")
        void itPersistsAcrossAFreshFetch() throws Exception {
            body(post("/api/shop/products"), phoneOwner, """
                    {"name":"Edge 50 Neo","brand":"Motorola","categoryId":%d,
                     "firstVariant":{"label":"8 GB + 256 GB","sellingPrice":24999,"stock":2}}
                    """.formatted(phoneCategory));

            // A second, independent request is what "close and reopen the app"
            // means to the server - no session, no cache carried across.
            assertTrue(myProducts(phoneOwner).contains("Edge 50 Neo"),
                    "a product that vanishes on the next fetch was never really created");
        }

        @Test
        @DisplayName("a create with no variant is refused, and says what to type")
        void refusesToMakeAnInvisibleProduct() throws Exception {
            MvcResult result = send(post("/api/shop/products"), phoneOwner, """
                    {"name":"Ghost Phone","brand":"Nobody","categoryId":%d}
                    """.formatted(phoneCategory));

            assertEquals(400, result.getResponse().getStatus(),
                    "creating a product that cannot be seen is not a success");
            String message = result.getResponse().getContentAsString();
            assertTrue(message.contains("variant"),
                    "the merchant has to be told what is missing, not just refused: " + message);

            assertEquals("[]", myProducts(phoneOwner).trim(),
                    "a refused create must leave nothing behind");
            Integer orphans = jdbc.queryForObject(
                    "SELECT count(*) FROM products WHERE name = 'Ghost Phone'", Integer.class);
            assertEquals(0, orphans,
                    "a half-created product is worse than none: it is invisible and "
                            + "un-deletable from the merchant's side");
        }

        @Test
        @DisplayName("a double tap does not create two listings")
        void duplicateCreateIsRefused() throws Exception {
            String payload = """
                    {"name":"Edge 50 Ultra","brand":"Motorola","categoryId":%d,
                     "firstVariant":{"label":"12 GB + 512 GB","sellingPrice":39999,"stock":1}}
                    """.formatted(phoneCategory);

            body(post("/api/shop/products"), phoneOwner, payload);
            MvcResult second = send(post("/api/shop/products"), phoneOwner, payload);

            assertEquals(409, second.getResponse().getStatus(),
                    "the second tap must be refused rather than shelving the phone twice: "
                            + second.getResponse().getContentAsString());
            Integer copies = jdbc.queryForObject(
                    "SELECT count(*) FROM products WHERE name = 'Edge 50 Ultra'", Integer.class);
            assertEquals(1, copies, "two rows for one phone is how a catalogue rots");
        }
    }

    @Nested
    @DisplayName("one merchant's shelf is not another's")
    class ShelvesDoNotMix {

        @Test
        @DisplayName("the saree shop never sees the phone shop's phone")
        void noCrossShopLeak() throws Exception {
            body(post("/api/shop/products"), phoneOwner, """
                    {"name":"Edge 50 Pro Max","brand":"Motorola","categoryId":%d,
                     "firstVariant":{"label":"12 GB + 256 GB","sellingPrice":31999,"stock":3}}
                    """.formatted(phoneCategory));
            body(post("/api/shop/products"), sareeOwner, """
                    {"name":"Banarasi Silk Saree","brand":"Kanchipuram","categoryId":%d,
                     "firstVariant":{"label":"Red, pure silk","sellingPrice":4999,"stock":6}}
                    """.formatted(sareeCategory));

            String phones = myProducts(phoneOwner);
            String sarees = myProducts(sareeOwner);

            assertTrue(phones.contains("Edge 50 Pro Max"), "the phone shop keeps its own: " + phones);
            assertFalse(phones.contains("Banarasi Silk Saree"),
                    "a phone shop must not inherit a saree shop's stock: " + phones);
            assertTrue(sarees.contains("Banarasi Silk Saree"), "the saree shop keeps its own: " + sarees);
            assertFalse(sarees.contains("Edge 50 Pro Max"),
                    "and the leak must not run the other way either: " + sarees);
        }

        @Test
        @DisplayName("a new shop starts with nothing at all")
        void aNewShopStartsEmpty() throws Exception {
            // Deliberately asserted on a shop that has done nothing, while
            // ANOTHER shop in the same database is trading - which is the
            // condition that made the original bug report possible.
            body(post("/api/shop/products"), phoneOwner, """
                    {"name":"Edge 50","brand":"Motorola","categoryId":%d,
                     "firstVariant":{"label":"8 GB + 128 GB","sellingPrice":19999,"stock":5}}
                    """.formatted(phoneCategory));

            assertEquals("[]", myProducts(sareeOwner).trim(),
                    "a shop that has listed nothing sells nothing, however busy its "
                            + "neighbours are");
        }
    }

    @Nested
    @DisplayName("categories are the platform's, the shelf is the shop's")
    class Categories {

        @Test
        @DisplayName("a phone shop's own departments do not include a kirana's")
        void myCategoriesAreMine() throws Exception {
            body(post("/api/shop/products"), phoneOwner, """
                    {"name":"Edge 50 Neo 5G","brand":"Motorola","categoryId":%d,
                     "firstVariant":{"label":"8 GB + 256 GB","sellingPrice":23999,"stock":2}}
                    """.formatted(phoneCategory));
            body(post("/api/shop/products"), sareeOwner, """
                    {"name":"Silk Saree Gold","brand":"Kanchipuram","categoryId":%d,
                     "firstVariant":{"label":"Gold, silk","sellingPrice":5999,"stock":3}}
                    """.formatted(sareeCategory));

            String mine = body(get("/api/categories/mine"), phoneOwner, null);
            assertTrue(mine.contains("Mobile Phones " + tag),
                    "the phone shop trades in phones: " + mine);
            assertFalse(mine.contains("Sarees " + tag),
                    "and must not be handed the saree shop's department to manage: " + mine);
        }

        @Test
        @DisplayName("a shop that has listed nothing manages no departments")
        void emptyShopHasNoCategories() throws Exception {
            assertEquals("[]", body(get("/api/categories/mine"), sareeOwner, null).trim(),
                    "an empty shelf is an empty list - not the platform's whole taxonomy, "
                            + "which is what showed a phone shop \"Atta, Rice & Dal\"");
        }

        @Test
        @DisplayName("the full taxonomy is still there to choose from")
        void taxonomyRemainsAvailableForPicking() throws Exception {
            // The empty state above must not make the shop unable to start: the
            // Add Product screen picks from the platform tree, which is how the
            // first category ever gets onto a shelf.
            String all = body(get("/api/categories"), phoneOwner, null);
            assertTrue(all.contains("Mobile Phones " + tag) && all.contains("Sarees " + tag),
                    "both departments exist in the platform taxonomy: " + all);
        }

        @Test
        @DisplayName("a merchant cannot edit the platform's taxonomy while others trade")
        void merchantCannotRewriteTheSharedTree() throws Exception {
            MvcResult result = send(post("/api/categories"), phoneOwner, """
                    {"name":"Phone Accessories %s","description":"mine"}
                    """.formatted(tag));

            assertEquals(403, result.getResponse().getStatus(),
                    "a shopkeeper holding CATALOG_MANAGE could rewrite every merchant's "
                            + "departments while platform.mode stayed unset. Defining the "
                            + "shared tree is a platform act once a second shop exists.");
        }
    }

    // ------------------------------------------------------------------

    private String myProducts(Long accountId) throws Exception {
        return body(get("/api/products/admin/all"), accountId, null);
    }

    private Long newMerchant(String kind) {
        Long id = merchantLifecycle.register(
                "Add Product Merchant " + kind + " " + tag, "Add Product Shop",
                null, null, null, true).getId();
        merchantLifecycle.transition(id, MerchantStatus.PENDING_REVIEW, "submitted");
        merchantLifecycle.transition(id, MerchantStatus.APPROVED, "checked");
        merchantLifecycle.transition(id, MerchantStatus.ACTIVE, "trading");
        return id;
    }

    private long newShop(Long merchant, String code) {
        long id = shopLifecycle.open(merchant, code, "Add Product Shop",
                12.9716, 77.5946, new java.math.BigDecimal("5"), "Asia/Kolkata").getId();
        shopLifecycle.transitionAsPlatform(id, ShopStatus.ACTIVE, "ready to trade");
        return id;
    }

    private Long newStaffFor(long shop, String kind) {
        String email = tag + "-" + kind + "@example.test";
        jdbc.update("""
                INSERT INTO customers (full_name, email, mobile_number, password, role, active)
                VALUES (?, ?, ?, 'not-a-real-hash', 'ADMIN', true)
                """, "Owner " + kind + " " + tag, email,
                "9" + (100000000 + (int) (Math.random() * 899999999)));
        Long id = jdbc.queryForObject(
                "SELECT id FROM customers WHERE email = ?", Long.class, email);
        jdbc.update("""
                INSERT INTO shop_staff (shop_id, customer_id, is_default, active)
                VALUES (?, ?, true, true)
                """, shop, id);
        return id;
    }

    private Long newCategory(String name) {
        jdbc.update("INSERT INTO categories (name, description, active) VALUES (?, ?, true)",
                name, name + " department");
        return jdbc.queryForObject(
                "SELECT id FROM categories WHERE name = ?", Long.class, name);
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

    private MvcResult send(MockHttpServletRequestBuilder request, Long accountId, String json)
            throws Exception {
        request.with(authentication(tokenFor(accountId)));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
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
