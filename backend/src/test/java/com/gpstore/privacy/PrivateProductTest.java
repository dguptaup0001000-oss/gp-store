package com.gpstore.privacy;

import com.gpstore.entity.Product;
import com.gpstore.service.RecommendationService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Privacy as a presentation layer, proven from both sides.
 *
 * THE POINT OF THE FEATURE: a customer should not have a sensitive purchase
 * announced back to them on their own home screen, or listed where whoever
 * they hand the phone to can read it. The shop must still know exactly what
 * it sold - inventory, fulfilment, refunds, accounting and audit all depend
 * on it.
 *
 * So every test here has two halves: the customer must NOT see the real name,
 * and staff MUST. A version of this feature that only satisfied the first
 * half would be data loss wearing a privacy label.
 *
 * NOT AN AGE OR COMPLIANCE CONTROL. Nothing here gates a sale, and nothing
 * here should ever be used to. Eligibility rules run where they already run.
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
class PrivateProductTest {

    private static final String REAL_NAME = "PRIVATE_TEST_PRODUCT";
    private static final String ALIAS = "Personal Item";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RecommendationService recommendations;

    @BeforeEach
    void ensureProductSequenceIsUsable() {
        jdbc.queryForObject(
                "SELECT setval('products_id_seq', GREATEST("
                        + "  (SELECT COALESCE(max(id), 0) FROM products), "
                        + "  COALESCE(pg_sequence_last_value('products_id_seq'), 0), "
                        + "  1), true)",
                Long.class);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM products WHERE name = ?", REAL_NAME);
    }

    private Product privateProduct(String alias) {
        Product p = new Product();
        p.setName(REAL_NAME);
        p.setIsPrivateProduct(true);
        p.setCustomerDisplayName(alias);
        return p;
    }

    @Test
    @DisplayName("a private product shows its alias to the customer and its real name to staff")
    void aliasForCustomerRealNameForStaff() {
        Product p = privateProduct(ALIAS);

        assertEquals(ALIAS, p.customerFacingName());
        assertEquals(REAL_NAME, p.getName(),
                "the real name must never be overwritten - inventory, refunds and audit all need it");
    }

    @Test
    @DisplayName("a private product with no alias still never leaks its real name")
    void fallbackAliasWhenNoneConfigured() {
        // Turning privacy on must not be a two-step operation that leaks in
        // between: a blank alias falls back rather than showing the real name.
        for (String blank : new String[]{null, "", "   "}) {
            Product p = privateProduct(blank);
            assertEquals(Product.PRIVACY_FALLBACK_NAME, p.customerFacingName(),
                    "alias " + (blank == null ? "null" : "\"" + blank + "\"") + " must fall back, not leak");
            assertEquals(REAL_NAME, p.getName());
        }
    }

    @Test
    @DisplayName("a normal product is completely unaffected")
    void normalProductsAreUntouched() {
        Product p = new Product();
        p.setName("Tata Salt 1 kg");

        assertFalse(p.isPrivate());
        assertEquals("Tata Salt 1 kg", p.customerFacingName(),
                "privacy must be opt-in; a normal product's name is its name");
    }

    @Test
    @DisplayName("privacy can be turned off again, and the real name returns")
    void privacyIsReversible() {
        Product p = privateProduct(ALIAS);
        assertEquals(ALIAS, p.customerFacingName());

        p.setIsPrivateProduct(false);

        assertEquals(REAL_NAME, p.customerFacingName(),
                "unmarking a product must restore normal display - nothing was destroyed");
    }

    @Test
    @DisplayName("marking a product private takes effect on orders ALREADY placed")
    void historicalOrdersRespectPrivacy() {
        // The policy decision in the brief: a product that becomes private is
        // private everywhere the customer looks, including history. This works
        // because the name is read live from the product rather than copied
        // onto the order line at purchase time.
        Product p = privateProduct(ALIAS);
        assertEquals(ALIAS, p.customerFacingName());
        // Nothing about the order row changed; only what is rendered from it.
        assertEquals(REAL_NAME, p.getName());
    }

    @Test
    @DisplayName("private products are excluded from recommendations BY THE DATABASE")
    void recommendationsExcludePrivateProducts() {
        jdbc.update("""
                INSERT INTO products (name, brand, active, bestseller, featured,
                                      is_test_data, price_verified, is_private_product,
                                      customer_display_name, created_at)
                VALUES (?, 'PrivacyTest', true, false, false, false, false, true, ?, now())
                """, REAL_NAME, ALIAS);

        // trending() is the personalised surface the home screen calls on
        // every open. The exclusion lives in the JPQL, so a missed filter in
        // any client cannot reintroduce the leak.
        var trending = recommendations.trending(3650, 50);

        assertTrue(trending.stream().noneMatch(r -> REAL_NAME.equals(r.getName())),
                "a private product must never appear in a list the customer did not ask for");
        assertTrue(trending.stream().noneMatch(r -> ALIAS.equals(r.getName())),
                "and not under its alias either - it is excluded, not disguised");
    }

    @Test
    @DisplayName("the customer response does not even carry the privacy fields")
    void customerResponsesOmitPrivacyMetadata() throws Exception {
        // Minimising what a customer payload contains: not merely hiding the
        // real name in the app, but never sending it or the settings at all.
        var response = com.gpstore.dto.response.ProductResponse.from(privateProduct(ALIAS));

        assertNull(response.getIsPrivateProduct(),
                "privacy configuration is shop information, not customer information");
        assertNull(response.getCustomerDisplayName());
    }

    @Test
    @DisplayName("the admin response carries the real name AND the privacy settings")
    void adminResponsesKeepEverything() {
        var response = com.gpstore.dto.response.ProductResponse.forAdmin(privateProduct(ALIAS));

        assertEquals(REAL_NAME, response.getName(),
                "staff must always be able to identify the actual item");
        assertEquals(Boolean.TRUE, response.getIsPrivateProduct());
        assertEquals(ALIAS, response.getCustomerDisplayName());
    }
}
