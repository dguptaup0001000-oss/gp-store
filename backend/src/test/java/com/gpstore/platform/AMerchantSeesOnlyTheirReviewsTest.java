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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * A shopkeeper reads the reviews of what THEY sell, and nobody else's.
 *
 * <p>WHAT THIS WAS BEFORE. {@code GET /api/reviews} was
 * {@code reviewRepository.findAll(pageable)} - every product review on
 * GP-STORE, for every product, by every customer of every merchant - and
 * {@code AdminReviewResponse} carries {@code customerName} AND
 * {@code customerEmail}. Review is not a {@code ShopOwned} entity, so no
 * tenant filter narrowed it. The only gate was REVIEWS_MODERATE, and
 * {@code Role.ADMIN} - which every shop owner holds - is granted
 * EVERY_SHOP_PERMISSION, so REVIEWS_MODERATE came with it.
 *
 * <p>So a merchant onboarded an hour ago, selling nothing, could page through
 * the name and email address of every customer who had ever reviewed anything
 * on the platform. That is a personal-data leak across tenants rather than an
 * operational-data one, which is why it is worth its own file.
 *
 * <p>AND IT WENT FURTHER THAN READING. {@code DELETE /api/reviews/{id}/moderate}
 * takes the same permission. A review belongs to a PRODUCT, and a product is
 * shared by every shop selling it - so one merchant hiding a review removes it
 * from the storefront of every other merchant selling that item. Hiding is a
 * platform act for exactly the reason §20 already gives for shop ratings, and
 * {@code hidingIsAPlatformAct} is what says so.
 */
@SpringBootTest(properties = {
        // Without this the resolver short-circuits to Shop #1 and both shops
        // below are the same shop - see AShopStocksItsOwnShelfTest.
        "platform.mode=MULTI_SHOP_PRODUCTION",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@DisplayName("A merchant reads the reviews of what they sell")
class AMerchantSeesOnlyTheirReviewsTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;
    @Autowired private MerchantLifecycleService merchantLifecycle;
    @Autowired private ShopLifecycleService shopLifecycle;

    private final String tag = "rev" + System.nanoTime();

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
    private Long shopper;
    private Long phoneReview;
    private Long sareeReview;

    /** Two shops this test made. Shop #1 is never read or written here. */
    @BeforeEach
    void twoShopsEachWithAReviewedProduct() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        phoneMerchant = newMerchant("phone");
        sareeMerchant = newMerchant("saree");
        phoneShop = newShop(phoneMerchant, "RPHN-" + tag);
        sareeShop = newShop(sareeMerchant, "RSAR-" + tag);
        phoneOwner = newStaffFor(phoneShop, "phone");
        sareeOwner = newStaffFor(sareeShop, "saree");

        phoneCategory = newCategory("Review Phones " + tag);
        sareeCategory = newCategory("Review Sarees " + tag);

        // Each shop lists one product, so each has exactly one product whose
        // reviews are legitimately theirs to read.
        phoneProduct = newListedProduct(phoneShop, phoneCategory, "Edge Review Phone " + tag);
        sareeProduct = newListedProduct(sareeShop, sareeCategory, "Silk Review Saree " + tag);

        // One customer, whose name and email are the thing that must not leak.
        shopper = newCustomer("Reviewing Shopper " + tag, tag + "-shopper@example.test");
        phoneReview = newReview(phoneProduct, shopper, 5, "great phone " + tag);
        sareeReview = newReview(sareeProduct, shopper, 2, "poor saree " + tag);
    }

    @AfterEach
    void tidyUp() {
        jdbc.update("DELETE FROM product_review_reasons WHERE review_id IN (?, ?)",
                phoneReview, sareeReview);
        jdbc.update("DELETE FROM reviews WHERE id IN (?, ?)", phoneReview, sareeReview);
        for (long shop : new long[]{phoneShop, sareeShop}) {
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
        jdbc.update("DELETE FROM customers WHERE id in (?, ?, ?)",
                phoneOwner, sareeOwner, shopper);
        TenantDefaults.install(PlatformMode.SINGLE_SHOP,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the list")
    class TheList {

        @Test
        @DisplayName("a merchant sees reviews of what they sell, and not of what they do not")
        void reviewsAreNarrowedToTheShelf() throws Exception {
            String phones = body(get("/api/reviews?size=100"), phoneOwner, null);

            assertTrue(phones.contains("great phone " + tag),
                    "the phone shop must still read reviews of the phone it sells: " + phones);
            assertFalse(phones.contains("poor saree " + tag),
                    "a phone shop has no business reading reviews of a saree it does not "
                            + "stock. This was findAll(): every review on the platform. Body: "
                            + phones);
        }

        @Test
        @DisplayName("and the other way round, so the narrowing is not one shop's luck")
        void theSareeShopIsNarrowedToo() throws Exception {
            String sarees = body(get("/api/reviews?size=100"), sareeOwner, null);

            assertTrue(sarees.contains("poor saree " + tag), "its own: " + sarees);
            assertFalse(sarees.contains("great phone " + tag),
                    "the leak must not run the other way either: " + sarees);
        }

        @Test
        @DisplayName("a shop that sells nothing reads nobody's reviews")
        void anEmptyShelfReadsNothing() throws Exception {
            // A shop onboarded and not yet stocked - the exact state a real new
            // merchant is in, and the state in which the old code handed them
            // the whole platform's reviewers.
            long freshMerchant = newMerchant("fresh");
            long freshShop = newShop(freshMerchant, "RFSH-" + tag);
            Long freshOwner = newStaffFor(freshShop, "fresh");
            try {
                String seen = body(get("/api/reviews?size=100"), freshOwner, null);
                assertFalse(seen.contains("great phone " + tag)
                                || seen.contains("poor saree " + tag),
                        "a merchant selling nothing must read nothing: " + seen);
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

        @Test
        @DisplayName("a reviewer's email address never reaches a merchant")
        void theEmailIsNotAMerchantsToRead() throws Exception {
            String phones = body(get("/api/reviews?size=100"), phoneOwner, null);

            assertTrue(phones.contains("great phone " + tag),
                    "the review itself is still there - this is about one field: " + phones);
            assertFalse(phones.contains(tag + "-shopper@example.test"),
                    "AdminReviewResponse carried customerEmail, so every merchant reading "
                            + "reviews was collecting reviewers' email addresses. A merchant "
                            + "answering a review needs the review, not a mailing list. Body: "
                            + phones);
        }

        @Test
        @DisplayName("the reported queue is narrowed the same way")
        void reportedIsNarrowedToo() throws Exception {
            jdbc.update("UPDATE reviews SET reported_at = now(), report_reason = 'spam' "
                    + "WHERE id IN (?, ?)", phoneReview, sareeReview);

            String phones = body(get("/api/reviews/reported?size=100"), phoneOwner, null);

            assertTrue(phones.contains("great phone " + tag), "its own flagged review: " + phones);
            assertFalse(phones.contains("poor saree " + tag),
                    "a second listing endpoint over the same rows is a second leak: " + phones);
        }
    }

    @Nested
    @DisplayName("moderation")
    class Moderation {

        @Test
        @DisplayName("hiding a review of somebody else's product is refused")
        void hidingIsAPlatformAct() throws Exception {
            MvcResult result = send(
                    delete("/api/reviews/" + sareeReview + "/moderate?reason=ABUSIVE_LANGUAGE"),
                    phoneOwner, null);

            // 404, NOT 403, and that is this codebase's convention rather than a
            // slip: CrossShopAccessException maps to "Not found" so a refusal
            // does not confirm that another tenant's row exists. See
            // GlobalExceptionHandler.handleCrossShop.
            assertEquals(404, result.getResponse().getStatus(),
                    "a review belongs to a PRODUCT, and a product is shared by every shop "
                            + "selling it - so one merchant hiding a review takes it off every "
                            + "other merchant's storefront too. Body: "
                            + result.getResponse().getContentAsString());

            Integer stillVisible = jdbc.queryForObject(
                    "SELECT count(*) FROM reviews WHERE id = ? AND hidden_at IS NULL",
                    Integer.class, sareeReview);
            assertEquals(1, stillVisible, "a refused moderation must change nothing");
        }

        @Test
        @DisplayName("responding as the shop to a review of a product you do not sell is refused")
        void respondingIsRefusedOffTheShelf() throws Exception {
            MvcResult result = send(
                    post("/api/reviews/" + sareeReview + "/respond"), phoneOwner,
                    "{\"text\":\"we are sorry\"}");

            assertEquals(404, result.getResponse().getStatus(),
                    "answering AS the shop under a review of something you have never "
                            + "stocked puts a stranger's words on another merchant's product. "
                            + "Body: " + result.getResponse().getContentAsString());
        }

        @Test
        @DisplayName("a merchant can still moderate and answer on their own shelf")
        void ownShelfStillWorks() throws Exception {
            // THE HALF THAT MUST NOT BREAK. A narrowing that also took away the
            // shopkeeper's real work would be a worse bug than the leak.
            MvcResult responded = send(
                    post("/api/reviews/" + phoneReview + "/respond"), phoneOwner,
                    "{\"text\":\"thanks for buying from us\"}");
            assertTrue(responded.getResponse().getStatus() < 300,
                    "the phone shop must still answer reviews of its own phone: "
                            + responded.getResponse().getStatus() + " "
                            + responded.getResponse().getContentAsString());
        }
    }

    @Nested
    @DisplayName("the platform's own view")
    class ThePlatformView {

        @Test
        @DisplayName("Super Admin still sees every review, unmasked")
        void platformKeepsTheWholePicture() throws Exception {
            // PRESERVED DELIBERATELY. The platform owner moderates GP-STORE and
            // needs the whole picture; narrowing them would be a different bug.
            String all = bodyAs(get("/api/reviews?size=100"), phoneOwner,
                    Role.SUPER_ADMIN, null);

            assertTrue(all.contains("great phone " + tag) && all.contains("poor saree " + tag),
                    "the platform sees across shops: " + all);
            assertTrue(all.contains(tag + "-shopper@example.test"),
                    "and keeps the operational detail a merchant does not get: " + all);
        }
    }

    // ------------------------------------------------------------------

    private Long newMerchant(String kind) {
        Long id = merchantLifecycle.register(
                "Review Merchant " + kind + " " + tag, "Review Shop",
                null, null, null, true).getId();
        merchantLifecycle.transition(id, MerchantStatus.PENDING_REVIEW, "submitted");
        merchantLifecycle.transition(id, MerchantStatus.APPROVED, "checked");
        merchantLifecycle.transition(id, MerchantStatus.ACTIVE, "trading");
        return id;
    }

    private long newShop(Long merchant, String code) {
        long id = shopLifecycle.open(merchant, code, "Review Shop",
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

    /** A product listed on one shop's shelf: catalogue row, variant, listing. */
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

    private Long newReview(Long productId, Long customerId, int rating, String comment) {
        jdbc.update("INSERT INTO reviews (product_id, customer_id, rating, comment, "
                + "review_date, active) VALUES (?, ?, ?, ?, now(), true)",
                productId, customerId, rating, comment);
        return jdbc.queryForObject(
                "SELECT id FROM reviews WHERE comment = ?", Long.class, comment);
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
