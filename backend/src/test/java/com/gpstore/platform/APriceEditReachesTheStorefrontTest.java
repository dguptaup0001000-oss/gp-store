package com.gpstore.platform;

import com.gpstore.catalog.shop.ShopProductVariant;
import com.gpstore.catalog.shop.ShopProductVariantRepository;
import com.gpstore.dto.response.ProductResponse;
import com.gpstore.entity.Category;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.platform.api.ShopSelfServiceController;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;
import com.gpstore.service.ProductService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A SHOPKEEPER CHANGES A PRICE AND THE CUSTOMER SEES THE NEW PRICE.
 *
 * FOUND BY DRIVING THE REAL FLUTTER APP against a real two-shop backend, not
 * by reading the code and not by any test that existed. The merchant screen
 * saved a new price, the API confirmed it, the listing row was right - and
 * the customer's product page went on showing the old number, because every
 * browse endpoint is @Cacheable and nothing on the listing write path evicted
 * anything. Not for a moment: for the whole ten-minute TTL.
 *
 * WHY IT IS WORSE UNDER A MARKETPLACE. The price a customer sees IS the
 * shop's listing (§10: one central catalogue, per-shop terms), so a listing
 * edit that does not reach the storefront is a shop advertising a number it
 * has stopped charging - and the customer finds out at checkout, where the
 * order is priced from the row rather than from the cache.
 *
 * THE OTHER HALF, delisting, has the same shape and is checked here too: an
 * item taken off the shelf that stays on the shelf for ten minutes is an
 * order the shop cannot fill.
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
@DisplayName("A price edit reaches the storefront")
class APriceEditReachesTheStorefrontTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;
    @Autowired private ProductService productService;
    @Autowired private ShopSelfServiceController shopSelfService;
    @Autowired private CategoryRepository categories;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;
    @Autowired private ShopProductVariantRepository listings;
    @Autowired private org.springframework.cache.CacheManager caches;

    private final String tag = "price" + System.nanoTime();

    private long shopId;
    private Long categoryId;
    private Long productId;
    private Long variantId;

    @BeforeEach
    void anItemOnTheShelfAtSixtyRupees() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
        shopId = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();

        Category category = new Category();
        category.setName("Price category " + tag);
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        categoryId = categories.save(category).getId();

        Product product = new Product();
        product.setName("Repriced item " + tag);
        product.setCategory(categories.findById(categoryId).orElseThrow());
        product.setActive(true);
        productId = TenantContext.runWithin(TenantScope.platform(),
                () -> products.save(product).getId());

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setQuantity(1.0);
        variant.setUnit("kg");
        variant.setSellingPrice(new BigDecimal("60.00"));
        variant.setAvailable(Boolean.TRUE);
        variant.setActive(Boolean.TRUE);
        variantId = TenantContext.runWithin(TenantScope.platform(),
                () -> variants.save(variant).getId());

        TenantContext.runWithin(TenantScope.ofShop(shopId), () -> {
            ShopProductVariant listing = new ShopProductVariant();
            listing.setCommerceMode(com.gpstore.catalog.shop.CommerceMode.ONLINE_PURCHASE);
            listing.setProductVariantId(variantId);
            listing.setSellingPrice(new BigDecimal("60.00"));
            listing.setAvailable(Boolean.TRUE);
            listing.setActive(Boolean.TRUE);
            return listings.save(listing);
        });

        // AND IT HAS STOCK. Without an inventory row the shelf is empty, and
        // since §10 (Part 2) the storefront withholds the price of an item
        // nobody can buy - so a fixture with no stock would be measuring the
        // out-of-stock path while claiming to measure a reprice.
        jdbc.update("INSERT INTO inventory (product_variant_id, shop_id, stock, "
                + "minimum_stock, reserved_stock) VALUES (?, ?, 25, 1, 0)", variantId, shopId);

        // THE FIXTURE'S OWN WRITES GO STRAIGHT TO THE REPOSITORY, which is
        // not the path under test and does not evict. Clearing here means
        // each test starts from the database rather than from whatever the
        // test before it left in the cache - otherwise a green run could be
        // the previous test's eviction doing this one's work.
        clearBrowseCaches();
    }

    private void clearBrowseCaches() {
        for (String name : java.util.List.of("products", "brands", "newArrivals",
                "categoryProducts", "productDetail", "productSearch", "productFeed",
                "bestsellerTiles", "trending", "frequentlyBought")) {
            java.util.Optional.ofNullable(caches.getCache(name))
                    .ifPresent(org.springframework.cache.Cache::clear);
        }
    }

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        clearBrowseCaches();
        jdbc.update("DELETE FROM inventory WHERE product_variant_id = ?", variantId);
        jdbc.update("DELETE FROM shop_product_variants WHERE product_variant_id = ?", variantId);
        jdbc.update("DELETE FROM product_variants WHERE id = ?", variantId);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        TenantDefaults.install(PlatformMode.SINGLE_SHOP,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    @Test
    @DisplayName("the very next customer read carries the new price")
    void aRepriceIsVisibleImmediately() {
        // THE READ THAT POISONS THE CACHE, and it is not contrived: it is a
        // customer opening the product page a moment before the shopkeeper
        // edits it, which is the ordinary case in a shop that is trading.
        assertEquals(0, priceOnTheStorefront().compareTo(new BigDecimal("60.00")),
                "the fixture has to start where it says it starts");

        reprice(new BigDecimal("73.00"));

        assertEquals(0, priceOnTheStorefront().compareTo(new BigDecimal("73.00")),
                "THE SHOPKEEPER CHANGED THE PRICE AND THE CUSTOMER IS STILL BEING SHOWN THE "
                        + "OLD ONE. Under a marketplace the listing IS the price, so this is a "
                        + "shop advertising a number it has stopped charging - and the customer "
                        + "finds out at checkout, which prices from the row.");
    }

    @Test
    @DisplayName("delisting takes the item off the storefront immediately")
    void aDelistingIsVisibleImmediately() {
        assertTrue(feedHoldsTheItem(), "the fixture has to start on the shelf");

        TenantContext.runWithin(TenantScope.ofShop(shopId),
                () -> { shopSelfService.delistItem(variantId); return null; });

        assertTrue(!feedHoldsTheItem(),
                "AN ITEM THE SHOP HAS TAKEN OFF ITS SHELF WAS STILL BEING OFFERED. Ten minutes "
                        + "of a cached feed is ten minutes of orders the shop cannot fill.");
    }

    // -------------------------------------------------------------- fixtures

    private void reprice(BigDecimal price) {
        TenantContext.runWithin(TenantScope.ofShop(shopId), () ->
                // THROUGH THE REAL ENDPOINT, on the injected bean - which is
                // the Spring proxy, so the eviction the fix added actually
                // runs. Calling the repository directly would test a path no
                // shopkeeper uses and would pass with the bug in place.
                shopSelfService.upsertListing(variantId,
                        new ShopSelfServiceController.ListingUpdate(
                                price, null, price.add(new BigDecimal("20")),
                                Boolean.TRUE, Boolean.TRUE, null)));
    }

    private BigDecimal priceOnTheStorefront() {
        ProductResponse response = TenantContext.runWithin(TenantScope.ofShop(shopId),
                () -> productService.getProductById(productId));
        assertNotNull(response, "the shop lists this item, so its page must open");
        return response.getVariants().stream()
                .filter(v -> variantId.equals(v.getId()))
                .map(com.gpstore.dto.response.VariantResponse::getSellingPrice)
                .findFirst()
                .orElseThrow();
    }

    private boolean feedHoldsTheItem() {
        return TenantContext.runWithin(TenantScope.ofShop(shopId), () ->
                productService.browseAll(org.springframework.data.domain.PageRequest.of(0, 40,
                                org.springframework.data.domain.Sort.by(
                                        org.springframework.data.domain.Sort.Direction.DESC, "id")))
                        .getContent().stream()
                        .map(ProductResponse::getId)
                        .anyMatch(productId::equals));
    }
}
