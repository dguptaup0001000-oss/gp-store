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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Searching the town, not one shop's shelf.
 *
 * <p>Every existing search route narrows to one shop, because listings are
 * shop-owned and the tenant filter applies. A customer who had chosen no
 * storefront was searching Shop #1 and being told the town does not sell what
 * they asked for. These are the answers the marketplace search has to give
 * instead.
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
@DisplayName("Searching the town")
class SearchingTheTownTest {

    @Autowired private MarketplaceFeedService marketplace;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;

    private static final double LAT = 23.5151;
    private static final double LNG = 77.6161;

    private final String tag = "srch" + System.nanoTime();
    private Long merchantId;
    private Long categoryId;
    private final List<Long> shopIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();

    @BeforeEach
    void aTownWithABarberAJewellerAndAKirana() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        Merchant m = new Merchant();
        m.setLegalName("Search fixture " + tag);
        m.setDisplayName("Search fixture");
        m.setStatus(MerchantStatus.ACTIVE);
        m.setIsDemo(Boolean.TRUE);
        m.setActive(Boolean.TRUE);
        merchantId = merchants.save(m).getId();

        jdbc.update("INSERT INTO categories (name, active) VALUES (?, true)", "Mixed " + tag);
        categoryId = jdbc.queryForObject("SELECT id FROM categories WHERE name = ?",
                Long.class, "Mixed " + tag);
    }

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        for (Long shopId : shopIds) {
            jdbc.update("DELETE FROM shop_product_variants WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM shops WHERE id = ?", shopId);
        }
        for (Long productId : productIds) {
            jdbc.update("DELETE FROM product_variants WHERE product_id = ?", productId);
            jdbc.update("DELETE FROM products WHERE id = ?", productId);
        }
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    @Test
    @DisplayName("searching for a haircut finds the barber, not nothing")
    void aServiceIsFindable() {
        sell("Sharma Salon", 0.0008, "Mens Haircut", "House",
                "250", CommerceMode.SERVICE_AT_SHOP);
        sell("Kirana", 0.0010, "Atta", "Ashirvaad", "240", CommerceMode.ONLINE_PURCHASE);

        List<MarketplaceFeedView> results =
                marketplace.search("haircut", LAT, LNG, null, 0, 20);

        assertEquals(1, results.size());
        assertEquals(CommerceMode.SERVICE_AT_SHOP, results.get(0).commerceMode(),
                "a search that only looks at what a cart can hold finds neither the "
                        + "barber nor the jeweller");
    }

    @Test
    @DisplayName("a visit-only listing is findable and is not addable")
    void aJewellerIsFindable() {
        sell("Gupta Jewellers", 0.0008, "Gold Chain", "Local",
                "45000", CommerceMode.VISIT_TO_BUY);

        List<MarketplaceFeedView> results =
                marketplace.search("gold chain", LAT, LNG, null, 0, 20);

        assertEquals(1, results.size());
        assertTrue(!results.get(0).addable(),
                "finding it must not imply a cart can hold it");
    }

    @Test
    @DisplayName("every word typed has to match something")
    void twoWordsMeansBoth() {
        sell("Sari shop", 0.0008, "Blue Silk Saree", "Local",
                "3000", CommerceMode.VISIT_TO_BUY);
        sell("Paint shop", 0.0010, "Blue Emulsion Paint", "Asian",
                "800", CommerceMode.ONLINE_PURCHASE);

        assertEquals(2, marketplace.search("blue", LAT, LNG, null, 0, 20).size());
        assertEquals(1, marketplace.search("blue saree", LAT, LNG, null, 0, 20).size(),
                "a customer who typed two words meant both of them");
    }

    @Test
    @DisplayName("a search can still be narrowed to one mode when a screen is about one")
    void theModeFilterStillWorks() {
        sell("Showroom", 0.0008, "Steel Almirah", "Local",
                "9000", CommerceMode.VISIT_TO_BUY);
        sell("Online shop", 0.0010, "Steel Bucket", "Local",
                "300", CommerceMode.ONLINE_PURCHASE);

        List<MarketplaceFeedView> online = marketplace.search("steel", LAT, LNG,
                Set.of(CommerceMode.ONLINE_PURCHASE), 0, 20);

        assertEquals(1, online.size());
        assertTrue(online.get(0).addable());
    }

    @Test
    @DisplayName("the same product in five shops is one result, with a seller count")
    void oneCardPerProduct() {
        Long variantId = sell("Chemist A", 0.0008, "Paracetamol", "Generic",
                "20", CommerceMode.ONLINE_PURCHASE);
        alsoSells("Chemist B", 0.0020, variantId, "22", CommerceMode.ONLINE_PURCHASE);
        alsoSells("Chemist C", 0.0030, variantId, "19", CommerceMode.ONLINE_PURCHASE);

        List<MarketplaceFeedView> results =
                marketplace.search("paracetamol", LAT, LNG, null, 0, 20);

        assertEquals(1, results.size(), "five shops stocking one painkiller is one result");
        assertEquals(3, results.get(0).sellerCount());
    }

    @Test
    @DisplayName("an empty or noise-only search asks for nothing rather than everything")
    void nothingTypedIsNotEverything() {
        sell("Somewhere", 0.0008, "Anything", "Brand", "100", CommerceMode.ONLINE_PURCHASE);

        assertTrue(marketplace.search("", LAT, LNG, null, 0, 20).isEmpty());
        assertTrue(marketplace.search("   ", LAT, LNG, null, 0, 20).isEmpty());
        assertTrue(marketplace.search("%", LAT, LNG, null, 0, 20).isEmpty(),
                "a bare wildcard must not become 'return the whole marketplace'");
        assertTrue(marketplace.search("anything", null, null, null, 0, 20).isEmpty(),
                "with no pin there is no town to search");
    }

    // ------------------------------------------------------------- fixture

    private Long sell(String shopName, double latOffset, String product, String brand,
                      String price, CommerceMode mode) {
        Long shopId = newShop(shopName, latOffset);

        jdbc.update("INSERT INTO products (name, brand, active, category_id) "
                + "VALUES (?, ?, true, ?)", product + " " + tag, brand, categoryId);
        Long productId = jdbc.queryForObject(
                "SELECT id FROM products WHERE name = ? ORDER BY id DESC LIMIT 1",
                Long.class, product + " " + tag);
        productIds.add(productId);

        jdbc.update("INSERT INTO product_variants "
                + "(product_id, quantity, unit, mrp, selling_price, available, active) "
                + "VALUES (?, 1, 'each', ?, ?, true, true)",
                productId, new BigDecimal(price), new BigDecimal(price));
        Long variantId = jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE product_id = ? ORDER BY id LIMIT 1",
                Long.class, productId);

        list(shopId, variantId, price, mode);
        return variantId;
    }

    private void alsoSells(String shopName, double latOffset, Long variantId,
                           String price, CommerceMode mode) {
        list(newShop(shopName, latOffset), variantId, price, mode);
    }

    private Long newShop(String name, double latOffset) {
        Shop shop = new Shop();
        shop.setMerchantId(merchantId);
        shop.setCode((name + "-" + tag).toLowerCase(java.util.Locale.ROOT).replace(' ', '-'));
        shop.setDisplayName(name + " " + tag);
        shop.setStatus(ShopStatus.ACTIVE);
        shop.setLatitude(LAT + latOffset);
        shop.setLongitude(LNG);
        shop.setMaxDeliveryRadiusKm(new BigDecimal("10"));
        shop.setTimeZone("Asia/Kolkata");
        shop.setIsDemo(Boolean.TRUE);
        shop.setActive(Boolean.TRUE);
        Long shopId = shops.save(shop).getId();
        shopIds.add(shopId);
        return shopId;
    }

    private void list(Long shopId, Long variantId, String price, CommerceMode mode) {
        jdbc.update("INSERT INTO shop_product_variants "
                        + "(shop_id, product_variant_id, selling_price, mrp, available, active, "
                        + " commerce_mode, price_mode, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, true, true, ?, ?, now(), now())",
                shopId, variantId, new BigDecimal(price), new BigDecimal(price), mode.name(),
                mode.isBuyableOnline() ? "EXACT_PRICE" : "STARTING_FROM");
    }
}
