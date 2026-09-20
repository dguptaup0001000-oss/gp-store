package com.gpstore.engagement;

import com.gpstore.catalog.shop.CommerceMode;
import com.gpstore.platform.Merchant;
import com.gpstore.platform.MerchantRepository;
import com.gpstore.platform.MerchantStatus;
import com.gpstore.platform.PlatformMode;
import com.gpstore.platform.PlatformProperties;
import com.gpstore.platform.Shop;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.ShopStatus;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantDefaults;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A merchant with a showroom deserves to know whether their listing works.
 * They do NOT deserve to be told a number GP-STORE invented.
 *
 * <p>An online sale records itself - an order, a payment, a receipt. A
 * Visit-to-Buy listing has none of that: the customer sees the card, taps
 * Directions, walks in and pays in cash. So interest is the honest thing to
 * count, and these tests are what keep it from quietly becoming revenue.
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
@DisplayName("Interest is not a sale")
class InterestIsNotASaleTest {

    @Autowired private ListingEngagement engagement;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;

    private final String tag = "eng" + System.nanoTime();
    private Long merchantId;
    private Long mineId;
    private Long theirsId;
    private Long categoryId;
    private Long productId;
    private Long variantId;

    @BeforeEach
    void twoShopsOneShowroomListing() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        Merchant m = new Merchant();
        m.setLegalName("Engagement fixture " + tag);
        m.setDisplayName("Engagement fixture");
        m.setStatus(MerchantStatus.ACTIVE);
        m.setIsDemo(Boolean.TRUE);
        m.setActive(Boolean.TRUE);
        merchantId = merchants.save(m).getId();

        mineId = newShop("Mine");
        theirsId = newShop("Theirs");

        jdbc.update("INSERT INTO categories (name, active) VALUES (?, true)", "Jewellery " + tag);
        categoryId = jdbc.queryForObject("SELECT id FROM categories WHERE name = ?",
                Long.class, "Jewellery " + tag);
        jdbc.update("INSERT INTO products (name, brand, active, category_id) "
                + "VALUES (?, 'Local', true, ?)", "Gold Chain " + tag, categoryId);
        productId = jdbc.queryForObject(
                "SELECT id FROM products WHERE name = ? ORDER BY id DESC LIMIT 1",
                Long.class, "Gold Chain " + tag);
        jdbc.update("INSERT INTO product_variants "
                + "(product_id, quantity, unit, mrp, selling_price, available, active) "
                + "VALUES (?, 1, 'each', 45000, 45000, true, true)", productId);
        variantId = jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE product_id = ? ORDER BY id LIMIT 1",
                Long.class, productId);

        jdbc.update("INSERT INTO shop_product_variants "
                + "(shop_id, product_variant_id, selling_price, mrp, available, active, "
                + " commerce_mode, price_mode, created_at, updated_at) "
                + "VALUES (?, ?, 45000, 45000, true, true, 'VISIT_TO_BUY', 'STARTING_FROM', "
                + " now(), now())", mineId, variantId);
    }

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        for (Long shopId : new Long[] {mineId, theirsId}) {
            jdbc.update("DELETE FROM listing_engagement_events WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM shop_product_variants WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM shops WHERE id = ?", shopId);
        }
        jdbc.update("DELETE FROM product_variants WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    @Nested
    @DisplayName("What it refuses to claim")
    class TheLine {

        @Test
        @DisplayName("nothing in the report can carry a rupee")
        void thereIsNowhereToPutRevenue() {
            for (RecordComponent field :
                    ListingEngagement.EngagementReport.class.getRecordComponents()) {
                assertTrue(field.getType() != BigDecimal.class,
                        "a money field on this report is how 'four hundred people asked for "
                                + "directions' becomes 'this merchant sold Rs 1 lakh': "
                                + field.getName());
                String name = field.getName().toLowerCase(Locale.ROOT);
                assertTrue(!name.contains("revenue") && !name.contains("sales")
                                && !name.contains("conversion") && !name.contains("commission"),
                        "this report measures interest and must not name itself after trade: "
                                + field.getName());
            }
        }

        @Test
        @DisplayName("the numbers travel with the sentence that keeps them honest")
        void theNoteIsPartOfTheData() {
            ListingEngagement.EngagementReport report =
                    engagement.reportFor(mineId, LocalDateTime.now().minusDays(1),
                            LocalDateTime.now().plusDays(1));

            assertNotNull(report.note());
            assertTrue(report.note().contains("not"),
                    "a count without this sentence invites exactly the reading that would "
                            + "make it a lie, and a second client would not have it");
            assertTrue(report.note().toLowerCase(Locale.ROOT).contains("sale"));
        }

        @Test
        @DisplayName("no kind of event claims a purchase happened")
        void nothingRecordsASale() {
            for (EngagementKind kind : EngagementKind.values()) {
                String name = kind.name().toLowerCase(Locale.ROOT);
                assertTrue(!name.contains("bought") && !name.contains("purchase")
                                && !name.contains("sold") && !name.contains("visited"),
                        "GP-STORE cannot tell a customer who walked in and bought from one "
                                + "who changed their mind on the way: " + kind);
            }
        }
    }

    @Nested
    @DisplayName("What it records")
    class TheCounting {

        @Test
        @DisplayName("a tap on Directions is counted, and as the mode the listing was in")
        void directionsAreCounted() {
            engagement.record(mineId, variantId, EngagementKind.ASKED_DIRECTIONS, 55L);
            engagement.record(mineId, variantId, EngagementKind.ASKED_DIRECTIONS, null);
            engagement.record(mineId, variantId, EngagementKind.OPENED_DETAIL, null);

            ListingEngagement.EngagementReport report = report();

            assertEquals(3, report.total());
            assertEquals(2L, report.byMode().get(CommerceMode.VISIT_TO_BUY)
                    .get(EngagementKind.ASKED_DIRECTIONS));
        }

        @Test
        @DisplayName("an anonymous tap counts, because most browsing is anonymous")
        void anonymousStillCounts() {
            engagement.record(mineId, variantId, EngagementKind.VIEWED_CARD, null);

            assertEquals(1, report().total(),
                    "refusing signed-out taps would make this a survey of logged-in users "
                            + "rather than a measure of interest");
        }

        @Test
        @DisplayName("the mode comes from the listing, never from the caller")
        void theCallerCannotAssertTheMode() {
            engagement.record(mineId, variantId, EngagementKind.VIEWED_CARD, null);

            String stored = jdbc.queryForObject(
                    "SELECT commerce_mode FROM listing_engagement_events WHERE shop_id = ?",
                    String.class, mineId);

            assertEquals("VISIT_TO_BUY", stored,
                    "a client that could assert the mode could dress ordinary online "
                            + "browsing up as showroom interest");
        }

        @Test
        @DisplayName("an event against a shop that does not list it writes nothing")
        void anotherShopsListingIsNotRecordable() {
            engagement.record(theirsId, variantId, EngagementKind.ASKED_DIRECTIONS, null);

            Long rows = jdbc.queryForObject(
                    "SELECT count(*) FROM listing_engagement_events WHERE shop_id = ?",
                    Long.class, theirsId);
            assertEquals(0L, rows);
        }

        @Test
        @DisplayName("a merchant sees their own interest and nobody else's")
        void oneShopsNumbersStayItsOwn() {
            jdbc.update("INSERT INTO shop_product_variants "
                    + "(shop_id, product_variant_id, selling_price, mrp, available, active, "
                    + " commerce_mode, price_mode, created_at, updated_at) "
                    + "VALUES (?, ?, 44000, 45000, true, true, 'VISIT_TO_BUY', 'STARTING_FROM', "
                    + " now(), now())", theirsId, variantId);

            engagement.record(mineId, variantId, EngagementKind.ASKED_DIRECTIONS, null);
            engagement.record(theirsId, variantId, EngagementKind.ASKED_DIRECTIONS, null);
            engagement.record(theirsId, variantId, EngagementKind.CALLED_SHOP, null);

            assertEquals(1, report().total(),
                    "a competitor's interest in the same chain is not this merchant's number");
        }

        @Test
        @DisplayName("rubbish is dropped rather than stored or thrown")
        void badInputIsSilentlyNothing() {
            engagement.record(mineId, variantId, EngagementKind.of("TELEPORTED"), null);
            engagement.record(null, variantId, EngagementKind.VIEWED_CARD, null);
            engagement.record(mineId, null, EngagementKind.VIEWED_CARD, null);
            engagement.record(mineId, 999999999L, EngagementKind.VIEWED_CARD, null);

            assertEquals(0, report().total(),
                    "analytics must never break browsing, and must never become a way to "
                            + "discover which listing ids a shop has");
        }
    }

    // ------------------------------------------------------------- fixture

    private ListingEngagement.EngagementReport report() {
        return engagement.reportFor(mineId,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));
    }

    private Long newShop(String name) {
        Shop shop = new Shop();
        shop.setMerchantId(merchantId);
        shop.setCode((name + "-" + tag).toLowerCase(Locale.ROOT));
        shop.setDisplayName(name + " " + tag);
        shop.setStatus(ShopStatus.ACTIVE);
        shop.setLatitude(21.0);
        shop.setLongitude(79.0);
        shop.setMaxDeliveryRadiusKm(new BigDecimal("10"));
        shop.setTimeZone("Asia/Kolkata");
        shop.setIsDemo(Boolean.TRUE);
        shop.setActive(Boolean.TRUE);
        return shops.save(shop).getId();
    }
}
