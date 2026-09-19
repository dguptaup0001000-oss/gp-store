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
 * A phone shop EDITS the phone it already sells, and adds a department for it.
 *
 * <p>WHAT A REAL DEVICE FOUND, after the create bug was fixed. Deepak Phone
 * Shop opened its own Motorola Edge 50 Pro, changed the variant's prices, and
 * tapped Save. The app said "Couldn't save variant - please check the values
 * and try again" - the values being MRP 35000, selling 30000, cost 29000, which
 * are perfectly ordinary numbers. Then the merchant opened Categories, typed
 * "charger", tapped Save, and was told "You don't have permission to do that."
 *
 * <p>NEITHER FAILURE IS ABOUT VALUES OR ABOUT PERMISSION IN THE SENSE THE
 * MESSAGES CLAIM. Both are the same architectural mistake that made Add Product
 * fail before it: the merchant's screen is calling the PLATFORM's catalogue
 * route. {@code PUT /api/product-variants/{id}} and {@code POST /api/categories}
 * are both guarded by CatalogDefinitionAuthorization, which - correctly - hands
 * a marketplace's shared taxonomy to CATALOG_DEFINE and nobody else. A
 * shopkeeper holds CATALOG_MANAGE. So the request is refused at the route,
 * before any controller, and Flutter's catch-all turns a 403 into a sentence
 * about values.
 *
 * <p>Adding stock to your own shelf, repricing it, and organising it into your
 * own departments were never platform acts. This test asserts the merchant can
 * do all three on their own shop and none of them on anybody else's.
 */
@SpringBootTest(properties = {
        // Without this the resolver short-circuits to Shop #1 and both shops
        // below are the same shop under two names.
        "platform.mode=MULTI_SHOP_PRODUCTION",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@DisplayName("A merchant edits what they actually sell")
class AMerchantEditsWhatTheySellTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;
    @Autowired private MerchantLifecycleService merchantLifecycle;
    @Autowired private ShopLifecycleService shopLifecycle;

    private final String tag = "editp" + System.nanoTime();

    private long phoneShop;
    private long sareeShop;
    private Long phoneMerchant;
    private Long sareeMerchant;
    private Long phoneOwner;
    private Long sareeOwner;
    private Long phoneCategory;
    private Long sareeCategory;

    /**
     * TWO SHOPS THIS TEST MADE, AND SHOP #1 IS NOT ONE OF THEM - a fixture that
     * tears down by tenant would empty the live shop in a shared database.
     */
    @BeforeEach
    void twoMerchantsInDifferentTrades() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        phoneMerchant = newMerchant("phone");
        sareeMerchant = newMerchant("saree");
        phoneShop = newShop(phoneMerchant, "EPHN-" + tag);
        sareeShop = newShop(sareeMerchant, "ESAR-" + tag);
        phoneOwner = newStaffFor(phoneShop, "phone");
        sareeOwner = newStaffFor(sareeShop, "saree");

        phoneCategory = newCategory("Mobile Phones " + tag);
        sareeCategory = newCategory("Sarees " + tag);
    }

    @AfterEach
    void tidyUp() {
        for (long shop : new long[]{phoneShop, sareeShop}) {
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

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the variant a real device could not save")
    class TheVariantThatWouldNotSave {

        /**
         * THE EXACT DEVICE REPRODUCTION. Pack size 8, unit "8 gb and 128 gb",
         * MRP 35000, selling 30000, cost 29000, available on.
         */
        @Test
        @DisplayName("the merchant saves their own variant at 35000/30000/29000")
        void merchantCanSaveTheirOwnVariant() throws Exception {
            long variant = phoneVariant();

            MvcResult result = send(put("/api/shop/variants/" + variant), phoneOwner, """
                    {"label":"8 GB + 128 GB","mrp":35000,"sellingPrice":30000,
                     "costPrice":29000,"available":true,
                     "attributes":[{"name":"RAM","value":"8 GB"},
                                   {"name":"Storage","value":"128 GB"},
                                   {"name":"Colour","value":"Black"}]}
                    """);

            assertEquals(200, result.getResponse().getStatus(),
                    "A shopkeeper repricing their own shelf is not a platform act. This "
                            + "returned 403 on a real phone and the app rendered it as "
                            + "\"Couldn't save variant - please check the values\": "
                            + result.getResponse().getContentAsString());
        }

        @Test
        @DisplayName("it is still 35000/30000 when read back out of the database")
        void thePricesPersistToTheDatabase() throws Exception {
            long variant = phoneVariant();

            body(put("/api/shop/variants/" + variant), phoneOwner, """
                    {"label":"8 GB + 128 GB","mrp":35000,"sellingPrice":30000,
                     "costPrice":29000,"available":true}
                    """);

            // READ FROM THE DATABASE, not from the object the service returned.
            BigDecimal selling = jdbc.queryForObject(
                    "SELECT selling_price FROM shop_product_variants "
                            + "WHERE shop_id = ? AND product_variant_id = ?",
                    BigDecimal.class, phoneShop, variant);
            BigDecimal mrp = jdbc.queryForObject(
                    "SELECT mrp FROM shop_product_variants "
                            + "WHERE shop_id = ? AND product_variant_id = ?",
                    BigDecimal.class, phoneShop, variant);

            assertEquals(0, new BigDecimal("30000").compareTo(selling),
                    "the shop's own price row must carry what the merchant typed");
            assertEquals(0, new BigDecimal("35000").compareTo(mrp),
                    "MRP is what the customer sees struck through");
        }

        @Test
        @DisplayName("a fresh fetch through the merchant API still has it")
        void itSurvivesAReload() throws Exception {
            long variant = phoneVariant();
            body(put("/api/shop/variants/" + variant), phoneOwner, """
                    {"label":"8 GB + 128 GB","mrp":35000,"sellingPrice":30000,
                     "costPrice":29000,"available":true}
                    """);

            String listings = body(get("/api/shop/listings"), phoneOwner, null);
            assertTrue(listings.contains("30000"),
                    "a price that vanishes on the next fetch was never saved: " + listings);
        }

        @Test
        @DisplayName("cost price below selling price is perfectly legal")
        void costBelowSellingIsAccepted() throws Exception {
            long variant = phoneVariant();
            MvcResult result = send(put("/api/shop/variants/" + variant), phoneOwner, """
                    {"mrp":35000,"sellingPrice":30000,"costPrice":29000,"available":true}
                    """);
            assertEquals(200, result.getResponse().getStatus(),
                    "buying at 29000 and selling at 30000 is a shop making a living, "
                            + "not a validation error: " + result.getResponse().getContentAsString());
        }

        @Test
        @DisplayName("the merchant's own cost price never comes back in the response")
        void costPriceIsNotReturned() throws Exception {
            long variant = phoneVariant();
            body(put("/api/shop/variants/" + variant), phoneOwner, """
                    {"mrp":35000,"sellingPrice":30000,"costPrice":29000,"available":true}
                    """);

            // WRITABLE, NEVER READABLE. What a shop paid is its private
            // business; it is set here and it is not echoed, so a response
            // captured anywhere - a log, a proxy, a screenshot - cannot carry
            // the merchant's margin.
            String read = body(get("/api/shop/variants/" + variant), phoneOwner, null);
            assertFalse(read.contains("29000"),
                    "cost price came back out of the merchant variant read: " + read);
            assertFalse(read.toLowerCase(java.util.Locale.ROOT).contains("costprice"),
                    "the field itself must not be in the response shape: " + read);
        }

        @Test
        @DisplayName("a customer browsing the shop never sees cost price")
        void costPriceNeverReachesTheStorefront() throws Exception {
            long variant = phoneVariant();
            body(put("/api/shop/variants/" + variant), phoneOwner, """
                    {"mrp":35000,"sellingPrice":30000,"costPrice":29000,"available":true}
                    """);

            String feed = body(get("/api/products/feed?page=0&size=50"), phoneOwner, null);
            assertFalse(feed.contains("29000"),
                    "the wholesale margin reached a browse surface: " + feed);
        }

        @Test
        @DisplayName("selling above MRP is refused, and says so")
        void sellingAboveMrpIsRefused() throws Exception {
            long variant = phoneVariant();
            MvcResult result = send(put("/api/shop/variants/" + variant), phoneOwner, """
                    {"mrp":30000,"sellingPrice":35000,"available":true}
                    """);
            assertEquals(400, result.getResponse().getStatus(),
                    "charging above the printed maximum price is the one price rule that "
                            + "is not the merchant's to choose");
        }

        @Test
        @DisplayName("a zero or negative price is refused deterministically")
        void nonPositivePriceIsRefused() throws Exception {
            long variant = phoneVariant();
            assertEquals(400, send(put("/api/shop/variants/" + variant), phoneOwner, """
                    {"sellingPrice":0,"available":true}
                    """).getResponse().getStatus());
            assertEquals(400, send(put("/api/shop/variants/" + variant), phoneOwner, """
                    {"sellingPrice":-5,"available":true}
                    """).getResponse().getStatus());
        }
    }

    @Nested
    @DisplayName("one merchant cannot edit another's shelf")
    class CrossShopWrites {

        @Test
        @DisplayName("the saree shop cannot reprice the phone shop's phone")
        void sareeShopCannotRepriceAPhone() throws Exception {
            long variant = phoneVariant();

            MvcResult result = send(put("/api/shop/variants/" + variant), sareeOwner, """
                    {"sellingPrice":1,"available":true}
                    """);

            assertTrue(result.getResponse().getStatus() == 404
                            || result.getResponse().getStatus() == 403,
                    "naming another shop's variant must not reprice it, got "
                            + result.getResponse().getStatus());

            BigDecimal stillMine = jdbc.queryForObject(
                    "SELECT selling_price FROM shop_product_variants "
                            + "WHERE shop_id = ? AND product_variant_id = ?",
                    BigDecimal.class, phoneShop, variant);
            assertTrue(stillMine.compareTo(BigDecimal.ONE) > 0,
                    "the phone shop's price was rewritten by a saree shop");

            Integer stolen = jdbc.queryForObject(
                    "SELECT count(*) FROM shop_product_variants "
                            + "WHERE shop_id = ? AND product_variant_id = ?",
                    Integer.class, sareeShop, variant);
            assertEquals(0, stolen,
                    "a cross-shop write must not quietly create the caller a listing of "
                            + "its own for somebody else's item");
        }

        @Test
        @DisplayName("a forged X-Shop-Id does not move the write to another shop")
        void forgedShopHeaderIsIgnored() throws Exception {
            long variant = phoneVariant();

            MockHttpServletRequestBuilder forged = put("/api/shop/variants/" + variant)
                    .header("X-Shop-Id", String.valueOf(phoneShop));
            MvcResult result = send(forged, sareeOwner, """
                    {"sellingPrice":1,"available":true}
                    """);

            assertFalse(result.getResponse().getStatus() == 200,
                    "a header the client chooses must never widen what the credential "
                            + "may touch");
        }
    }

    @Nested
    @DisplayName("a variant is whatever tells two things apart")
    class GenericAttributes {

        @Test
        @DisplayName("a phone is stored as RAM and Storage, not as a pack size")
        void phoneAttributesPersist() throws Exception {
            long variant = phoneVariant();

            body(put("/api/shop/variants/" + variant), phoneOwner, """
                    {"label":"8 GB + 128 GB","sellingPrice":30000,"mrp":35000,
                     "attributes":[{"name":"RAM","value":"8 GB"},
                                   {"name":"Storage","value":"128 GB"},
                                   {"name":"Colour","value":"Black"}]}
                    """);

            List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT name, value, display_order FROM product_variant_attributes "
                            + "WHERE product_variant_id = ? ORDER BY display_order", variant);
            assertEquals(3, rows.size(), "three facts about the phone, three rows");
            assertEquals("RAM", rows.get(0).get("name"));
            assertEquals("8 GB", rows.get(0).get("value"));
            assertEquals("Storage", rows.get(1).get("name"));
            assertEquals("Colour", rows.get(2).get("name"),
                    "the order the merchant typed is the order a customer reads");
        }

        @Test
        @DisplayName("shoes and sarees use the same mechanism, with no new schema")
        void otherTradesUseTheSameTable() throws Exception {
            long variant = phoneVariant();

            body(put("/api/shop/variants/" + variant), phoneOwner, """
                    {"sellingPrice":4999,"mrp":5999,
                     "attributes":[{"name":"Size","value":"9"},
                                   {"name":"Material","value":"Silk"}]}
                    """);

            Integer rows = jdbc.queryForObject(
                    "SELECT count(*) FROM product_variant_attributes WHERE product_variant_id = ?",
                    Integer.class, variant);
            assertEquals(2, rows,
                    "a shoe size and a saree's material need no column of their own");
        }

        @Test
        @DisplayName("a grocery pack size still fills quantity and unit")
        void groceryVariantsRemainCompatible() throws Exception {
            long variant = phoneVariant();

            body(put("/api/shop/variants/" + variant), phoneOwner, """
                    {"quantity":5,"unit":"kg","sellingPrice":250,"mrp":260}
                    """);

            java.util.Map<String, Object> row = jdbc.queryForMap(
                    "SELECT quantity, unit FROM product_variants WHERE id = ?", variant);
            assertEquals(5.0, ((Number) row.get("quantity")).doubleValue(), 0.0001,
                    "the pack size is what weighs a basket for delivery and must survive");
            assertEquals("kg", row.get("unit"));
        }

        @Test
        @DisplayName("re-sending the same details changes nothing")
        void replacingAttributesIsIdempotent() throws Exception {
            long variant = phoneVariant();
            String payload = """
                    {"sellingPrice":30000,"mrp":35000,
                     "attributes":[{"name":"RAM","value":"8 GB"},
                                   {"name":"Storage","value":"128 GB"}]}
                    """;

            body(put("/api/shop/variants/" + variant), phoneOwner, payload);
            body(put("/api/shop/variants/" + variant), phoneOwner, payload);

            Integer rows = jdbc.queryForObject(
                    "SELECT count(*) FROM product_variant_attributes WHERE product_variant_id = ?",
                    Integer.class, variant);
            assertEquals(2, rows,
                    "a retry after a dropped response must not double every detail");
        }

        @Test
        @DisplayName("the same detail twice keeps one value, not two")
        void duplicateAttributeNamesCollapse() throws Exception {
            long variant = phoneVariant();
            body(put("/api/shop/variants/" + variant), phoneOwner, """
                    {"sellingPrice":30000,"mrp":35000,
                     "attributes":[{"name":"RAM","value":"8 GB"},
                                   {"name":"ram","value":"12 GB"}]}
                    """);

            Integer rows = jdbc.queryForObject(
                    "SELECT count(*) FROM product_variant_attributes WHERE product_variant_id = ?",
                    Integer.class, variant);
            assertEquals(1, rows,
                    "a variant with two answers to \"how much RAM\" has none");
        }

        @Test
        @DisplayName("a second variant can be added to a product this shop sells")
        void aSecondVariantCanBeAdded() throws Exception {
            long first = phoneVariant();
            Long productId = jdbc.queryForObject(
                    "SELECT product_id FROM product_variants WHERE id = ?", Long.class, first);

            MvcResult result = send(post("/api/shop/products/" + productId + "/variants"),
                    phoneOwner, """
                    {"label":"12 GB + 256 GB","sellingPrice":35000,"mrp":39000,
                     "attributes":[{"name":"RAM","value":"12 GB"},
                                   {"name":"Storage","value":"256 GB"}]}
                    """);

            assertEquals(200, result.getResponse().getStatus(),
                    "a phone comes in more than one size and the merchant had no route "
                            + "to the second: " + result.getResponse().getContentAsString());

            Integer variants = jdbc.queryForObject(
                    "SELECT count(*) FROM product_variants WHERE product_id = ?",
                    Integer.class, productId);
            assertEquals(2, variants);
        }

        @Test
        @DisplayName("another shop cannot bolt a variant onto this shop's product")
        void crossShopVariantCreateIsRefused() throws Exception {
            long first = phoneVariant();
            Long productId = jdbc.queryForObject(
                    "SELECT product_id FROM product_variants WHERE id = ?", Long.class, first);

            MvcResult result = send(post("/api/shop/products/" + productId + "/variants"),
                    sareeOwner, """
                    {"label":"pirated","sellingPrice":1}
                    """);

            assertTrue(result.getResponse().getStatus() >= 400,
                    "a saree shop added a variant to a phone shop's product, got "
                            + result.getResponse().getStatus());
            Integer variants = jdbc.queryForObject(
                    "SELECT count(*) FROM product_variants WHERE product_id = ?",
                    Integer.class, productId);
            assertEquals(1, variants, "and the product must be untouched");
        }
    }

    @Nested
    @DisplayName("a shop organises its own departments")
    class ShopCategories {

        @Test
        @DisplayName("the merchant adds \"charger\" to their own shop")
        void merchantCanAddAShopCategory() throws Exception {
            MvcResult result = send(post("/api/shop/categories"), phoneOwner, """
                    {"name":"charger"}
                    """);

            assertEquals(200, result.getResponse().getStatus(),
                    "the Add Category button exists in the merchant app and answered "
                            + "\"You don't have permission to do that\" on a real phone: "
                            + result.getResponse().getContentAsString());
        }

        @Test
        @DisplayName("it appears in that shop's departments after a reload")
        void shopCategoryShowsInMine() throws Exception {
            body(post("/api/shop/categories"), phoneOwner, """
                    {"name":"charger"}
                    """);

            String mine = body(get("/api/shop/categories"), phoneOwner, null);
            assertTrue(mine.contains("charger"),
                    "a department the merchant created must be in their own list: " + mine);
        }

        @Test
        @DisplayName("the saree shop does not inherit \"charger\"")
        void shopCategoryDoesNotLeak() throws Exception {
            body(post("/api/shop/categories"), phoneOwner, """
                    {"name":"charger"}
                    """);

            String theirs = body(get("/api/shop/categories"), sareeOwner, null);
            assertFalse(theirs.contains("charger"),
                    "one merchant creating a department must not put it on every other "
                            + "merchant's screen: " + theirs);
        }

        @Test
        @DisplayName("a shop category does not enter the platform taxonomy")
        void shopCategoryIsNotGlobal() throws Exception {
            body(post("/api/shop/categories"), phoneOwner, """
                    {"name":"charger"}
                    """);

            Integer global = jdbc.queryForObject(
                    "SELECT count(*) FROM categories WHERE lower(name) = 'charger'",
                    Integer.class);
            assertEquals(0, global,
                    "a merchant typing a word must not add a row every shop on GP-STORE "
                            + "then sees in its category tree");
        }

        @Test
        @DisplayName("the same name twice is a conflict, not a second row")
        void duplicateShopCategoryIsRefused() throws Exception {
            body(post("/api/shop/categories"), phoneOwner, """
                    {"name":"charger"}
                    """);
            MvcResult second = send(post("/api/shop/categories"), phoneOwner, """
                    {"name":"Charger"}
                    """);

            assertEquals(409, second.getResponse().getStatus(),
                    "two departments with the same name is a menu nobody can use: "
                            + second.getResponse().getContentAsString());
        }

        @Test
        @DisplayName("two shops may each have their own \"charger\"")
        void twoShopsMayShareAName() throws Exception {
            assertEquals(200, send(post("/api/shop/categories"), phoneOwner, """
                    {"name":"charger"}
                    """).getResponse().getStatus());
            assertEquals(200, send(post("/api/shop/categories"), sareeOwner, """
                    {"name":"charger"}
                    """).getResponse().getStatus(),
                    "uniqueness is per shop - one merchant naming a department must not "
                            + "take that word away from every other merchant");
        }

        @Test
        @DisplayName("the merchant still cannot edit the platform's taxonomy")
        void merchantStillCannotRewriteTheSharedTree() throws Exception {
            MvcResult result = send(post("/api/categories"), phoneOwner, """
                    {"name":"Phone Accessories %s","description":"mine"}
                    """.formatted(tag));

            assertEquals(403, result.getResponse().getStatus(),
                    "shop-local categories must not become a side door into the shared "
                            + "tree every merchant reads");
        }

        @Test
        @DisplayName("one shop cannot rename or delete another shop's department")
        void crossShopCategoryWriteIsRefused() throws Exception {
            String created = body(post("/api/shop/categories"), phoneOwner, """
                    {"name":"charger"}
                    """);
            long id = idOf(created);

            assertTrue(send(put("/api/shop/categories/" + id), sareeOwner, """
                    {"name":"hijacked"}
                    """).getResponse().getStatus() >= 400,
                    "a saree shop renamed a phone shop's department");

            String mine = body(get("/api/shop/categories"), phoneOwner, null);
            assertTrue(mine.contains("charger"), "and the original must survive: " + mine);
        }
    }

    // ------------------------------------------------------------------

    /** Creates a phone on the phone shop's shelf and returns its variant id. */
    private long phoneVariant() throws Exception {
        body(post("/api/shop/products"), phoneOwner, """
                {"name":"Moto Edge 50 Pro %s","brand":"Motorola","categoryId":%d,
                 "firstVariant":{"label":"8 GB + 128 GB","sellingPrice":30000,
                                 "mrp":35000,"costPrice":29000,"stock":3}}
                """.formatted(tag, phoneCategory));
        Long variant = jdbc.queryForObject(
                "SELECT v.id FROM product_variants v JOIN products p ON p.id = v.product_id "
                        + "WHERE p.name = ?", Long.class, "Moto Edge 50 Pro " + tag);
        assertNotNull(variant, "the fixture must have produced a variant");
        return variant;
    }

    private static long idOf(String json) {
        int at = json.indexOf("\"id\"");
        assertTrue(at >= 0, "no id in " + json);
        int start = at;
        while (start < json.length() && !Character.isDigit(json.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }

    private Long newMerchant(String kind) {
        Long id = merchantLifecycle.register(
                "Edit Product Merchant " + kind + " " + tag, "Edit Product Shop",
                null, null, null, true).getId();
        merchantLifecycle.transition(id, MerchantStatus.PENDING_REVIEW, "submitted");
        merchantLifecycle.transition(id, MerchantStatus.APPROVED, "checked");
        merchantLifecycle.transition(id, MerchantStatus.ACTIVE, "trading");
        return id;
    }

    private long newShop(Long merchant, String code) {
        long id = shopLifecycle.open(merchant, code, "Edit Product Shop",
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
