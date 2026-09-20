package com.gpstore.platform.api;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The card collapses; the detail expands.
 *
 * <p>The feed shows ONE card per product, because five shops selling the same
 * drink is one drink and not five results. The moment the customer taps it,
 * though, "who has it, where, and for how much" is the entire question. These
 * are two views of the same rows, and this proves neither one is inventing or
 * losing anything the other has.
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
@DisplayName("Who has it, and where")
class WhoHasItAndWhereTest {

    @Autowired private MarketplaceFeedService marketplace;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;

    private static final double LAT = 22.3131;
    private static final double LNG = 78.4141;

    private final String tag = "offer" + System.nanoTime();
    private Long merchantId;
    private Long categoryId;
    private Long productId;
    private Long variantId;
    private final List<Long> shopIds = new ArrayList<>();

    @BeforeEach
    void aProductSoldThreeDifferentWays() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        Merchant m = new Merchant();
        m.setLegalName("Offer fixture " + tag);
        m.setDisplayName("Offer fixture");
        m.setStatus(MerchantStatus.ACTIVE);
        m.setIsDemo(Boolean.TRUE);
        m.setActive(Boolean.TRUE);
        merchantId = merchants.save(m).getId();

        jdbc.update("INSERT INTO categories (name, active) VALUES (?, true)", "Drinks " + tag);
        categoryId = jdbc.queryForObject("SELECT id FROM categories WHERE name = ?",
                Long.class, "Drinks " + tag);
        jdbc.update("INSERT INTO products (name, brand, active, category_id) "
                + "VALUES (?, 'Fizz', true, ?)", "Cola " + tag, categoryId);
        productId = jdbc.queryForObject(
                "SELECT id FROM products WHERE name = ? ORDER BY id DESC LIMIT 1",
                Long.class, "Cola " + tag);
        jdbc.update("INSERT INTO product_variants "
                + "(product_id, quantity, unit, mrp, selling_price, available, active) "
                + "VALUES (?, 1, 'bottle', 50, 45, true, true)", productId);
        variantId = jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE product_id = ? ORDER BY id LIMIT 1",
                Long.class, productId);
    }

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        for (Long shopId : shopIds) {
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

    @Test
    @DisplayName("five shops selling one drink is one card and five offers")
    void theCardCollapsesAndTheDetailExpands() {
        sells("Nearest kirana", 0.0008, "45", CommerceMode.ONLINE_PURCHASE);
        sells("Corner shop", 0.0020, "44", CommerceMode.ONLINE_PURCHASE);
        sells("Cold store", 0.0035, "42", CommerceMode.VISIT_TO_BUY);

        List<MarketplaceFeedView> cards = marketplace.page(LAT, LNG,
                java.util.Set.of(CommerceMode.ONLINE_PURCHASE), categoryId, 0, 20);
        assertEquals(1, cards.size(),
                "repeating the same drink once per shop is a worse answer to 'what can I "
                        + "buy near me' than showing it once");

        List<MarketplaceOfferView> offers = marketplace.offersOf(productId, LAT, LNG);
        assertEquals(3, offers.size(),
                "the customer tapped the card to find out who has it - answering with only "
                        + "the one the feed happened to pick hides the rest");
    }

    @Test
    @DisplayName("the detail does not hide a shop that delivers behind the mode you tapped")
    void allThreeModesComeBack() {
        sells("Showroom", 0.0008, "42", CommerceMode.VISIT_TO_BUY);
        sells("Delivers it", 0.0030, "45", CommerceMode.ONLINE_PURCHASE);

        List<MarketplaceOfferView> offers = marketplace.offersOf(productId, LAT, LNG);

        assertTrue(offers.stream().anyMatch(MarketplaceOfferView::addable),
                "a customer looking at a Visit-to-Buy card for something a shop two streets "
                        + "further delivers should be told so");
        assertTrue(offers.stream().anyMatch(o -> !o.addable()));
    }

    @Test
    @DisplayName("a visit-to-buy offer says where to go")
    void aPosterWithNoShopBehindItIsUseless() {
        sells("Showroom", 0.0008, "42", CommerceMode.VISIT_TO_BUY);

        MarketplaceOfferView offer = marketplace.offersOf(productId, LAT, LNG).get(0);

        assertNotNull(offer.whereToGo(), "telling a customer to visit without saying where "
                + "is a poster with no shop behind it");
        assertTrue(offer.whereToGo().contains("Bajaj Nagar"), offer.whereToGo());
        assertNotNull(offer.shopLatitude(), "something has to go into a maps app");
        assertNotNull(offer.shopLongitude());
        assertFalse(offer.addable(),
                "the cart must not offer to hold something the shop will not ship");
    }

    @Test
    @DisplayName("nearest first, so the screen does not have to re-sort")
    void nearestFirst() {
        sells("Far", 0.0060, "40", CommerceMode.VISIT_TO_BUY);
        sells("Near", 0.0008, "49", CommerceMode.VISIT_TO_BUY);

        List<MarketplaceOfferView> offers = marketplace.offersOf(productId, LAT, LNG);

        assertTrue(offers.get(0).distanceKm() <= offers.get(1).distanceKm());
        assertTrue(offers.get(0).shopName().startsWith("Near"));
    }

    @Test
    @DisplayName("a pin nobody serves gets nothing, not everything")
    void noPinNoMarketplace() {
        sells("Somewhere", 0.0008, "45", CommerceMode.ONLINE_PURCHASE);

        assertTrue(marketplace.offersOf(productId, null, null).isEmpty(),
                "inventing a location would show a customer in one town the shops of another");
        assertTrue(marketplace.offersOf(productId, -40.0, -70.0).isEmpty());
    }

    // ------------------------------------------------------------- fixture

    private void sells(String name, double latOffset, String price, CommerceMode mode) {
        Shop shop = new Shop();
        shop.setMerchantId(merchantId);
        shop.setCode((name + "-" + tag).toLowerCase(java.util.Locale.ROOT).replace(' ', '-'));
        shop.setDisplayName(name + " " + tag);
        shop.setStatus(ShopStatus.ACTIVE);
        shop.setLatitude(LAT + latOffset);
        shop.setLongitude(LNG);
        shop.setMaxDeliveryRadiusKm(new BigDecimal("10"));
        shop.setTimeZone("Asia/Kolkata");
        shop.setAddressLine("12 Market Road");
        shop.setLocality("Bajaj Nagar");
        shop.setCity("Nagpur");
        shop.setPincode("440010");
        shop.setIsDemo(Boolean.TRUE);
        shop.setActive(Boolean.TRUE);
        Long shopId = shops.save(shop).getId();
        shopIds.add(shopId);

        jdbc.update("INSERT INTO shop_product_variants "
                        + "(shop_id, product_variant_id, selling_price, mrp, available, active, "
                        + " commerce_mode, price_mode, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 50, true, true, ?, ?, now(), now())",
                shopId, variantId, new BigDecimal(price), mode.name(),
                mode.isBuyableOnline() ? "EXACT_PRICE" : "STARTING_FROM");
    }
}
