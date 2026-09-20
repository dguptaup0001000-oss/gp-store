package com.gpstore.catalog.shop;

import com.gpstore.exception.BadRequestException;
import com.gpstore.platform.Merchant;
import com.gpstore.platform.MerchantRepository;
import com.gpstore.platform.MerchantStatus;
import com.gpstore.platform.PlatformMode;
import com.gpstore.platform.PlatformProperties;
import com.gpstore.platform.Shop;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.ShopScopeSwitch;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A shopkeeper decides HOW they sell each thing on their shelf.
 *
 * <p>A jeweller, a barber and a kirana are not three kinds of product needing
 * three kinds of table. They are one shelf entry with a different answer to
 * "how do I get this?" - so the merchant sets that answer through the same
 * route they already use to set a price, and these are the rules that answer
 * has to obey.
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
@DisplayName("A shopkeeper sets how they sell")
class AShopkeeperSetsHowTheySellTest {

    @Autowired private ShopVariantEditing editing;
    @Autowired private ShopScopeSwitch shopScope;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;

    private final String tag = "mode" + System.nanoTime();
    private Long merchantId;
    private Long shopId;
    private Long categoryId;
    private Long productId;
    private Long variantId;

    @BeforeEach
    void oneShopWithOneThingOnTheShelf() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        Merchant m = new Merchant();
        m.setLegalName("Mode fixture " + tag);
        m.setDisplayName("Mode fixture");
        m.setStatus(MerchantStatus.ACTIVE);
        m.setIsDemo(Boolean.TRUE);
        m.setActive(Boolean.TRUE);
        merchantId = merchants.save(m).getId();

        Shop shop = new Shop();
        shop.setMerchantId(merchantId);
        shop.setCode("mode-shop-" + tag);
        shop.setDisplayName("Mode Shop " + tag);
        shop.setStatus(ShopStatus.ACTIVE);
        shop.setLatitude(21.11);
        shop.setLongitude(79.22);
        shop.setMaxDeliveryRadiusKm(new BigDecimal("10"));
        shop.setTimeZone("Asia/Kolkata");
        shop.setIsDemo(Boolean.TRUE);
        shop.setActive(Boolean.TRUE);
        shopId = shops.save(shop).getId();

        jdbc.update("INSERT INTO categories (name, active) VALUES (?, true)", "Salon " + tag);
        categoryId = jdbc.queryForObject("SELECT id FROM categories WHERE name = ?",
                Long.class, "Salon " + tag);
        jdbc.update("INSERT INTO products (name, brand, active, category_id) "
                + "VALUES (?, 'House', true, ?)", "Haircut " + tag, categoryId);
        productId = jdbc.queryForObject(
                "SELECT id FROM products WHERE name = ? ORDER BY id DESC LIMIT 1",
                Long.class, "Haircut " + tag);
        jdbc.update("INSERT INTO product_variants "
                + "(product_id, quantity, unit, mrp, selling_price, available, active) "
                + "VALUES (?, 1, 'each', 300, 250, true, true)", productId);
        variantId = jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE product_id = ? ORDER BY id LIMIT 1",
                Long.class, productId);
        jdbc.update("INSERT INTO shop_product_variants "
                + "(shop_id, product_variant_id, selling_price, mrp, available, active, "
                + " commerce_mode, price_mode, created_at, updated_at) "
                + "VALUES (?, ?, 250, 300, true, true, 'ONLINE_PURCHASE', 'EXACT_PRICE', "
                + " now(), now())", shopId, variantId);
    }

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        jdbc.update("DELETE FROM shop_product_variants WHERE shop_id = ?", shopId);
        jdbc.update("DELETE FROM shops WHERE id = ?", shopId);
        jdbc.update("DELETE FROM product_variants WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    @Test
    @DisplayName("a barber turns a shelf item into a service done at the shop")
    void aServiceIsJustAListingWithADifferentAnswer() {
        ShopVariantEditing.VariantView saved = asTheShopkeeper(() -> editing.update(variantId,
                edit(CommerceMode.SERVICE_AT_SHOP, ListingPriceMode.STARTING_FROM,
                        null, null, 30)));

        assertEquals(CommerceMode.SERVICE_AT_SHOP, saved.commerceMode());
        assertEquals("Service at Shop", saved.commerceLabel());
        assertEquals(30, saved.serviceDurationMinutes());
        assertEquals(OfflineAvailability.AVAILABLE, saved.offlineAvailability(),
                "something standing in a shop with nothing said about it reads as available");
    }

    @Test
    @DisplayName("an item sold online cannot be priced 'from'")
    void anOnlinePriceIsAPromise() {
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> asTheShopkeeper(() -> editing.update(variantId,
                        edit(CommerceMode.ONLINE_PURCHASE, ListingPriceMode.STARTING_FROM,
                                null, null, null))));

        assertTrue(refused.getMessage().contains("exact price"),
                "a cart totals this number and a receipt prints it, so 'from Rs 250' cannot "
                        + "be what the customer is charged: " + refused.getMessage());
    }

    @Test
    @DisplayName("a price range needs a top, and the top cannot be under the bottom")
    void aRangeHasToBeARange() {
        BadRequestException noTop = assertThrows(BadRequestException.class,
                () -> asTheShopkeeper(() -> editing.update(variantId,
                        edit(CommerceMode.VISIT_TO_BUY, ListingPriceMode.PRICE_RANGE,
                                null, null, null))));
        assertTrue(noTop.getMessage().contains("top price"), noTop.getMessage());

        BadRequestException upsideDown = assertThrows(BadRequestException.class,
                () -> asTheShopkeeper(() -> editing.update(variantId,
                        edit(CommerceMode.VISIT_TO_BUY, ListingPriceMode.PRICE_RANGE,
                                new BigDecimal("100"), null, null))));
        assertTrue(upsideDown.getMessage().contains("below the starting price"),
                upsideDown.getMessage());
    }

    @Test
    @DisplayName("saving a price without mentioning the mode does not reset it")
    void absentMeansUnchangedNotReset() {
        asTheShopkeeper(() -> editing.update(variantId,
                edit(CommerceMode.VISIT_TO_BUY, ListingPriceMode.STARTING_FROM,
                        null, OfflineAvailability.LIMITED_AVAILABILITY, null)));

        // A merchant screen that knows nothing about modes saves a price.
        ShopVariantEditing.VariantView after = asTheShopkeeper(() -> editing.update(variantId,
                new ShopVariantEditing.VariantEdit(null, null, null, null, null, null,
                        new BigDecimal("275"), new BigDecimal("300"), null,
                        null, null, null, null,
                        null, null, null, null, null, null)));

        assertEquals(CommerceMode.VISIT_TO_BUY, after.commerceMode(),
                "silently turning a Visit-to-Buy listing back into an online one puts an item "
                        + "into carts the shop cannot ship");
        assertEquals(OfflineAvailability.LIMITED_AVAILABILITY, after.offlineAvailability());
        assertEquals(0, new BigDecimal("275").compareTo(after.sellingPrice()));
    }

    @Test
    @DisplayName("switching a service back to a product clears the duration")
    void aProductHasNoDuration() {
        asTheShopkeeper(() -> editing.update(variantId,
                edit(CommerceMode.SERVICE_AT_SHOP, ListingPriceMode.STARTING_FROM,
                        null, null, 45)));

        ShopVariantEditing.VariantView back = asTheShopkeeper(() -> editing.update(variantId,
                edit(CommerceMode.ONLINE_PURCHASE, ListingPriceMode.EXACT_PRICE,
                        null, null, null)));

        assertNull(back.serviceDurationMinutes(),
                "a merchant switching back should not have to hunt for a field the screen "
                        + "no longer shows");
    }

    @Test
    @DisplayName("an absurd service duration is refused")
    void eightDaysIsNotAHaircut() {
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> asTheShopkeeper(() -> editing.update(variantId,
                        edit(CommerceMode.SERVICE_AT_SHOP, ListingPriceMode.STARTING_FROM,
                                null, null, 60 * 24 * 8))));
        assertTrue(refused.getMessage().contains("minutes"), refused.getMessage());
    }

    // ------------------------------------------------------------- fixture

    private ShopVariantEditing.VariantEdit edit(CommerceMode mode, ListingPriceMode price,
                                                BigDecimal priceMax,
                                                OfflineAvailability availability,
                                                Integer minutes) {
        return new ShopVariantEditing.VariantEdit(
                null, null, null, null, null, null,
                new BigDecimal("250"), new BigDecimal("300"), null,
                null, null, null, null,
                mode, price, priceMax, availability, minutes, List.of());
    }

    private <T> T asTheShopkeeper(java.util.function.Supplier<T> what) {
        return shopScope.within(shopId, what::get);
    }

    private void asTheShopkeeper(Runnable what) {
        shopScope.within(shopId, () -> { what.run(); return null; });
    }
}
