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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * The whole of what a real phone shop does, end to end, in one test.
 *
 * <p>WHY THIS EXISTS AS ONE LONG SEQUENCE rather than as separate cases. Every
 * bug found on the real device so far has been in the SEAM between two steps
 * that each worked: create answered 200 and the list was empty; the list showed
 * the phone and saving it answered 403; the screen rendered price and stock and
 * the save said the shop no longer lists the item. Nothing that tested one step
 * could see any of them. So this walks the merchant's actual day - create the
 * product, price the variant, describe it, edit it, edit the product, reload as
 * if the app had restarted - and asserts the database after each one.
 *
 * <p>THE TWO ROOT CAUSES IT PINS.
 *
 * <ol>
 *   <li>{@code PUT /api/products/{id}} was the merchant's Save Changes, and it
 *       is the PLATFORM catalogue route: 403 for a shopkeeper the moment a
 *       second shop trades. Captured from the deployed build as
 *       {@code 403 "You don't have permission to do that."} on a product the
 *       same account had just been shown.</li>
 *   <li>The variant save failed only because the app was NEWER than the
 *       server: {@code PUT /api/shop/variants/{id}} answered
 *       {@code 404 "No endpoint exists at ..."} on a backend deployed before
 *       that route existed, and the app re-worded a routing 404 into "This
 *       shop no longer lists that item". The data was never wrong - the
 *       listing row was there, which {@link #theListingRowIsReallyThere()}
 *       asserts.</li>
 * </ol>
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
@DisplayName("The phone shop sells its phone, start to finish")
class ThePhoneShopSellsItsPhoneTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;
    @Autowired private MerchantLifecycleService merchantLifecycle;
    @Autowired private ShopLifecycleService shopLifecycle;

    private final String tag = "moto" + System.nanoTime();

    private long phoneShop;
    private long sareeShop;
    private long secondShopOfPhoneMerchant;
    private Long phoneMerchant;
    private Long sareeMerchant;
    private Long phoneOwner;
    private Long sareeOwner;
    private Long phoneCategory;
    private Long sareeCategory;

    @BeforeEach
    void twoMerchants() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        phoneMerchant = newMerchant("phone");
        sareeMerchant = newMerchant("saree");
        phoneShop = newShop(phoneMerchant, "MPHN-" + tag);
        secondShopOfPhoneMerchant = newShop(phoneMerchant, "MPH2-" + tag);
        sareeShop = newShop(sareeMerchant, "MSAR-" + tag);
        phoneOwner = newStaffFor(phoneShop, "phone");
        sareeOwner = newStaffFor(sareeShop, "saree");
        // The phone merchant also works in their second shop, which is what
        // makes the multi-shop case below a real one rather than a fixture.
        jdbc.update("INSERT INTO shop_staff (shop_id, customer_id, is_default, active) "
                + "VALUES (?, ?, false, true)", secondShopOfPhoneMerchant, phoneOwner);

        phoneCategory = newCategory("Mobile Phones " + tag);
        sareeCategory = newCategory("Sarees " + tag);
    }

    @AfterEach
    void tidyUp() {
        for (long shop : new long[]{phoneShop, secondShopOfPhoneMerchant, sareeShop}) {
            jdbc.update("DELETE FROM shop_categories WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM inventory WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_product_variants WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_business_hours WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_staff WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shops WHERE id = ?", shop);
        }
        jdbc.update("DELETE FROM product_variant_attributes WHERE product_variant_id IN "
                + "(SELECT v.id FROM product_variants v JOIN products p ON p.id = v.product_id "
                + "WHERE p.category_id IN (?, ?))", phoneCategory, sareeCategory);
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
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    // ==================================================================

    @Test
    @DisplayName("the merchant's whole day, and the database after every step")
    void theWholeSequence() throws Exception {
        // 5. Create "moto edge 50 pro".
        String created = body(post("/api/shop/products"), phoneOwner, """
                {"name":"moto edge 50 pro %s","brand":"motorola","categoryId":%d,
                 "firstVariant":{"label":"8 gb and 128 gb","sellingPrice":30000,
                                 "mrp":35000,"costPrice":29000,"stock":4,
                                 "commerceMode":"ONLINE_PURCHASE"}}
                """.formatted(tag, phoneCategory));
        assertTrue(created.contains("moto edge 50 pro"), "create must answer with what it made");

        long productId = productId();
        long variantId = variantId(productId);

        // 6-7. Describe and price the variant the way the device did.
        body(put("/api/shop/variants/" + variantId), phoneOwner, """
                {"label":"8 gb and 128 gb","mrp":35000,"sellingPrice":30000,"costPrice":29000,
                 "available":true,
                 "attributes":[{"name":"RAM","value":"8 gb"},
                               {"name":"Storage","value":"128 gb"},
                               {"name":"Colour","value":"red"}]}
                """);

        // 8-9. Fetch product and variant back.
        String listed = body(get("/api/products/admin/all"), phoneOwner, null);
        assertTrue(listed.contains("moto edge 50 pro " + tag),
                "the merchant's own product must be on their list: " + listed);
        String variantRead = body(get("/api/shop/variants/" + variantId), phoneOwner, null);
        assertTrue(variantRead.contains("\"RAM\""), "details must read back: " + variantRead);
        assertTrue(variantRead.contains("red"), "including the colour: " + variantRead);

        // 10-12. Edit the variant: change a legitimate field.
        body(put("/api/shop/variants/" + variantId), phoneOwner, """
                {"sellingPrice":28999,"mrp":35000,"available":true}
                """);

        // 13-14. Fetch again, assert the persisted value - from the DATABASE.
        BigDecimal selling = jdbc.queryForObject(
                "SELECT selling_price FROM shop_product_variants "
                        + "WHERE shop_id = ? AND product_variant_id = ?",
                BigDecimal.class, phoneShop, variantId);
        assertEquals(0, new BigDecimal("28999").compareTo(selling),
                "the new price must be in this shop's own row");

        // 15-16. Edit the product itself - the screen that answered 403.
        MvcResult productSave = send(put("/api/shop/products/" + productId), phoneOwner, """
                {"name":"moto edge 50 pro max %s","brand":"Motorola","categoryId":%d,"active":true}
                """.formatted(tag, phoneCategory));
        assertEquals(200, productSave.getResponse().getStatus(),
                "Save Changes on a product this shop sells answered 403 on a real device: "
                        + productSave.getResponse().getContentAsString());

        // 17-18. Fetch again, assert persisted.
        String renamed = jdbc.queryForObject(
                "SELECT name FROM products WHERE id = ?", String.class, productId);
        assertEquals("moto edge 50 pro max " + tag, renamed,
                "the rename must reach the catalogue row");

        // 19. Relaunch: a second independent request, no session carried over.
        String afterRestart = body(get("/api/products/admin/all"), phoneOwner, null);
        assertTrue(afterRestart.contains("moto edge 50 pro max " + tag),
                "a product that vanishes on the next fetch was never really saved");
        assertTrue(afterRestart.contains("28999"),
                "and it must still carry the new price: " + afterRestart);

        // 20-22. The same mutations as an unrelated merchant.
        assertDenied(put("/api/shop/variants/" + variantId), sareeOwner, """
                {"sellingPrice":1,"available":true}
                """, "reprice another shop's variant");
        assertDenied(put("/api/shop/products/" + productId), sareeOwner, """
                {"name":"stolen","active":false}
                """, "rename another shop's product");
        assertDenied(put("/api/shop/variants/" + variantId + "/images"), sareeOwner, """
                {"imageUrls":[]}
                """, "wipe another shop's photos");

        // And merchant B changed nothing.
        assertEquals(0, new BigDecimal("28999").compareTo(jdbc.queryForObject(
                        "SELECT selling_price FROM shop_product_variants "
                                + "WHERE shop_id = ? AND product_variant_id = ?",
                        BigDecimal.class, phoneShop, variantId)),
                "merchant B's refused write still changed merchant A's price");
        assertEquals("moto edge 50 pro max " + tag, jdbc.queryForObject(
                        "SELECT name FROM products WHERE id = ?", String.class, productId),
                "merchant B's refused write still renamed merchant A's product");
    }

    /**
     * THE ROW THE DEVICE SAID WAS MISSING, asserted to exist.
     *
     * <p>"This shop no longer lists that item" was shown on a screen that was
     * simultaneously rendering that item's price and stock. That looked like
     * corrupt ownership; it was not. Creation writes the listing row, and this
     * is the assertion that says so - so any future regression in creation
     * fails here rather than on somebody's phone.
     */
    @Test
    @DisplayName("creating a product really does leave a listing row behind")
    void theListingRowIsReallyThere() throws Exception {
        body(post("/api/shop/products"), phoneOwner, """
                {"name":"moto edge 50 pro %s","brand":"motorola","categoryId":%d,
                 "firstVariant":{"label":"8 gb and 128 gb","sellingPrice":30000,
                                 "mrp":35000,"costPrice":29000,"stock":4,
                                 "commerceMode":"ONLINE_PURCHASE"}}
                """.formatted(tag, phoneCategory));

        long productId = productId();
        long variantId = variantId(productId);

        assertEquals(1, (int) jdbc.queryForObject(
                        "SELECT count(*) FROM shop_product_variants "
                                + "WHERE shop_id = ? AND product_variant_id = ?",
                        Integer.class, phoneShop, variantId),
                "the shop listing is the authoritative ownership row and it must exist");
        assertEquals(4, (int) jdbc.queryForObject(
                        "SELECT stock FROM inventory WHERE shop_id = ? AND product_variant_id = ?",
                        Integer.class, phoneShop, variantId),
                "and so must the opening stock");
        assertEquals(phoneShop, (long) jdbc.queryForObject(
                        "SELECT shop_id FROM shop_product_variants WHERE product_variant_id = ?",
                        Long.class, variantId),
                "the listing must belong to the shop that created it, not to Shop #1");
    }

    @Nested
    @DisplayName("read and write agree about who owns what")
    class ReadAndWriteAgree {

        @Test
        @DisplayName("a variant the shop does not list is neither shown nor editable")
        void unlistedVariantIsHiddenAndUneditable() throws Exception {
            body(post("/api/shop/products"), phoneOwner, """
                    {"name":"moto edge 50 pro %s","brand":"motorola","categoryId":%d,
                     "firstVariant":{"label":"8 gb and 128 gb","sellingPrice":30000,
                                     "mrp":35000,"stock":4,
                                     "commerceMode":"ONLINE_PURCHASE"}}
                    """.formatted(tag, phoneCategory));
            long productId = productId();
            long listedVariant = variantId(productId);

            // A second variant that exists CENTRALLY but that this shop does
            // not list - written directly, because no merchant route can make
            // one without also listing it.
            jdbc.update("INSERT INTO product_variants "
                    + "(product_id, quantity, unit, selling_price, mrp, available, active) "
                    + "VALUES (?, null, '12 gb and 256 gb', 40000, 45000, true, true)", productId);
            Long unlisted = jdbc.queryForObject(
                    "SELECT id FROM product_variants WHERE product_id = ? AND id <> ?",
                    Long.class, productId, listedVariant);
            assertNotNull(unlisted);

            // THE PROPERTY THAT MATTERS: the screen does not show it...
            String listed = body(get("/api/products/admin/all"), phoneOwner, null);
            assertFalse(listed.contains("12 gb and 256 gb"),
                    "a variant this shop does not list must not appear on its product "
                            + "screen - showing it is what would make an honest 404 on save "
                            + "look like corrupted data: " + listed);

            // ...and the write refuses it for the same reason the read hid it.
            assertEquals(404, send(put("/api/shop/variants/" + unlisted), phoneOwner, """
                    {"sellingPrice":1,"available":true}
                    """).getResponse().getStatus(),
                    "read and write must answer the same ownership question");
        }

        @Test
        @DisplayName("a missing route says so, and does not blame the data")
        void aMissingRouteIsNotAMissingListing() throws Exception {
            MvcResult result = mockMvc.perform(put("/api/shop/variants/not-a-real-route/99")
                    .with(authentication(tokenFor(phoneOwner)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")).andReturn();

            // PINS THE EXACT WORDING the merchant app keys on to tell a
            // routing 404 from a "your row is gone" 404. A newer app against
            // an older server produced the second message for the first
            // situation, and an afternoon went into hunting data that was
            // never wrong. If this prefix ever changes, that detection breaks
            // silently - so it is asserted here.
            assertEquals(404, result.getResponse().getStatus());
            assertTrue(result.getResponse().getContentAsString()
                            .contains("No endpoint exists at"),
                    "the no-route 404 must keep its distinctive wording: "
                            + result.getResponse().getContentAsString());
        }
    }

    @Nested
    @DisplayName("the catalogue is still shared")
    class TheCatalogueIsStillShared {

        @Test
        @DisplayName("a merchant may not rename a product another shop also sells")
        void renameRefusedWhenAnotherShopSellsIt() throws Exception {
            body(post("/api/shop/products"), phoneOwner, """
                    {"name":"moto edge 50 pro %s","brand":"motorola","categoryId":%d,
                     "firstVariant":{"label":"8 gb and 128 gb","sellingPrice":30000,
                                     "mrp":35000,"stock":4,
                                     "commerceMode":"ONLINE_PURCHASE"}}
                    """.formatted(tag, phoneCategory));
            long productId = productId();
            long variantId = variantId(productId);

            // The saree shop starts selling the same catalogue item.
            jdbc.update("INSERT INTO shop_product_variants "
                    + "(shop_id, product_variant_id, selling_price, available, active, created_at) "
                    + "VALUES (?, ?, 31000, true, true, now())", sareeShop, variantId);

            MvcResult result = send(put("/api/shop/products/" + productId), phoneOwner, """
                    {"name":"renamed by one merchant"}
                    """);

            assertEquals(400, result.getResponse().getStatus(),
                    "renaming a row another merchant is selling out of is a platform act");
            String message = result.getResponse().getContentAsString();
            assertTrue(message.contains("Other shops also sell"),
                    "and the merchant must be told why rather than refused with "
                            + "'you don't have permission': " + message);
            assertEquals("moto edge 50 pro " + tag, jdbc.queryForObject(
                    "SELECT name FROM products WHERE id = ?", String.class, productId));
        }

        @Test
        @DisplayName("but turning it off in my shop stays mine alone")
        void deactivatingIsShopLocal() throws Exception {
            body(post("/api/shop/products"), phoneOwner, """
                    {"name":"moto edge 50 pro %s","brand":"motorola","categoryId":%d,
                     "firstVariant":{"label":"8 gb and 128 gb","sellingPrice":30000,
                                     "mrp":35000,"stock":4,
                                     "commerceMode":"ONLINE_PURCHASE"}}
                    """.formatted(tag, phoneCategory));
            long productId = productId();
            long variantId = variantId(productId);
            jdbc.update("INSERT INTO shop_product_variants "
                    + "(shop_id, product_variant_id, selling_price, available, active, created_at) "
                    + "VALUES (?, ?, 31000, true, true, now())", sareeShop, variantId);

            body(put("/api/shop/products/" + productId), phoneOwner, """
                    {"active":false}
                    """);

            assertFalse(jdbc.queryForObject(
                            "SELECT active FROM shop_product_variants "
                                    + "WHERE shop_id = ? AND product_variant_id = ?",
                            Boolean.class, phoneShop, variantId),
                    "the phone shop's own listing must go inactive");
            assertTrue(jdbc.queryForObject(
                            "SELECT active FROM shop_product_variants "
                                    + "WHERE shop_id = ? AND product_variant_id = ?",
                            Boolean.class, sareeShop, variantId),
                    "and the saree shop must go on selling it - one merchant switching "
                            + "something off must not withdraw it from the marketplace");
            assertTrue(jdbc.queryForObject(
                            "SELECT active FROM products WHERE id = ?", Boolean.class, productId),
                    "the central catalogue row is not a merchant's to withdraw");
        }
    }

    @Nested
    @DisplayName("one merchant, two shops")
    class TwoShopsOneMerchant {

        @Test
        @DisplayName("the shop in scope is the shop that changes")
        void theSelectedShopIsTheOneMutated() throws Exception {
            body(post("/api/shop/products"), phoneOwner, """
                    {"name":"moto edge 50 pro %s","brand":"motorola","categoryId":%d,
                     "firstVariant":{"label":"8 gb and 128 gb","sellingPrice":30000,
                                     "mrp":35000,"stock":4,
                                     "commerceMode":"ONLINE_PURCHASE"}}
                    """.formatted(tag, phoneCategory));
            long productId = productId();
            long variantId = variantId(productId);

            // The merchant's OTHER shop also sells it, at its own price.
            jdbc.update("INSERT INTO shop_product_variants "
                    + "(shop_id, product_variant_id, selling_price, available, active, created_at) "
                    + "VALUES (?, ?, 32000, true, true, now())",
                    secondShopOfPhoneMerchant, variantId);

            // Acting in the DEFAULT shop, the price change must land there.
            body(put("/api/shop/variants/" + variantId), phoneOwner, """
                    {"sellingPrice":27999,"mrp":35000,"available":true}
                    """);

            assertEquals(0, new BigDecimal("27999").compareTo(jdbc.queryForObject(
                            "SELECT selling_price FROM shop_product_variants "
                                    + "WHERE shop_id = ? AND product_variant_id = ?",
                            BigDecimal.class, phoneShop, variantId)),
                    "the shop in scope must be the shop that changed");
            assertEquals(0, new BigDecimal("32000").compareTo(jdbc.queryForObject(
                            "SELECT selling_price FROM shop_product_variants "
                                    + "WHERE shop_id = ? AND product_variant_id = ?",
                            BigDecimal.class, secondShopOfPhoneMerchant, variantId)),
                    "and the merchant's OTHER shop must be untouched - a write that "
                            + "reached both would be a write that trusted no shop at all");
        }
    }

    // ==================================================================

    private long productId() {
        Long id = jdbc.queryForObject(
                "SELECT id FROM products WHERE name LIKE ?", Long.class,
                "moto edge 50 pro " + tag);
        assertNotNull(id, "the fixture must have produced a product");
        return id;
    }

    private long variantId(long productId) {
        Long id = jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE product_id = ? ORDER BY id ASC LIMIT 1",
                Long.class, productId);
        assertNotNull(id, "the fixture must have produced a variant");
        return id;
    }

    private void assertDenied(MockHttpServletRequestBuilder request, Long accountId,
                              String json, String what) throws Exception {
        int status = send(request, accountId, json).getResponse().getStatus();
        assertTrue(status == 403 || status == 404,
                "a merchant must not be able to " + what + " - got " + status);
    }

    private Long newMerchant(String kind) {
        Long id = merchantLifecycle.register(
                "Moto Merchant " + kind + " " + tag, "Moto Shop",
                null, null, null, true).getId();
        merchantLifecycle.transition(id, MerchantStatus.PENDING_REVIEW, "submitted");
        merchantLifecycle.transition(id, MerchantStatus.APPROVED, "checked");
        merchantLifecycle.transition(id, MerchantStatus.ACTIVE, "trading");
        return id;
    }

    private long newShop(Long merchant, String code) {
        long id = shopLifecycle.open(merchant, code, "Moto Shop",
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
