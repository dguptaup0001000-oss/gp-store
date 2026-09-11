package com.gpstore.platform;

import com.gpstore.entity.Category;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.catalog.shop.ShopProductVariant;
import com.gpstore.catalog.shop.ShopProductVariantRepository;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A kirana's storefront shows the kirana's shelf.
 *
 * FOUND BY RUNNING TWO REAL SHOPS, not by reading the code. Products and
 * variants are central by design (§10) - one row per item for the whole
 * marketplace, and what makes a shop's catalogue its own is which of them it
 * LISTS, at what price. The browse query asked the other question: "does the
 * catalogue have a price for this", which is true for every item on the
 * marketplace. So a customer who opened Shop A's storefront was shown Shop
 * B's goods.
 *
 * NOT A DATA LEAK, and worth being precise about: nothing shop-owned crosses
 * (no price, no stock, no order), and an unlisted item cannot be bought - the
 * catalogue price fallback is switched off under a marketplace, so
 * add-to-cart refuses it. What it is instead is a shop advertising the shop
 * next door's stock, and a customer discovering the difference at the moment
 * they try to buy.
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
@DisplayName("A storefront shows its own shelf")
class StorefrontShowsItsOwnShelfTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private ProductService productService;
    @Autowired private org.springframework.cache.CacheManager caches;
    @Autowired private CategoryRepository categories;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;
    @Autowired private ShopProductVariantRepository listings;

    private final String tag = "shelf" + System.nanoTime();

    private long shopA;
    private long shopB;
    private Long merchantB;
    private Long categoryId;
    private Long productAId;
    private Long productBId;
    private String nameA;
    private String nameB;

    @BeforeEach
    void twoShopsWithDifferentShelves() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
        shopA = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();

        Merchant second = new Merchant();
        second.setLegalName("Shelf fixture " + tag);
        second.setDisplayName("Shelf B");
        second.setStatus(MerchantStatus.ACTIVE);
        second.setIsDemo(Boolean.TRUE);
        second.setActive(Boolean.TRUE);
        merchantB = merchants.save(second).getId();

        Shop b = new Shop();
        b.setMerchantId(merchantB);
        b.setCode("SHELF-" + tag);
        b.setDisplayName("Shelf B");
        b.setStatus(ShopStatus.ACTIVE);
        b.setIsDemo(Boolean.TRUE);
        b.setActive(Boolean.TRUE);
        shopB = shops.save(b).getId();

        Category category = new Category();
        category.setName("Shelf category " + tag);
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        categoryId = categories.save(category).getId();

        nameA = "Only A sells this " + tag;
        nameB = "Only B sells this " + tag;
        productAId = newListedProduct(nameA, shopA, new BigDecimal("60.00"));
        productBId = newListedProduct(nameB, shopB, new BigDecimal("185.00"));
    }

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        jdbc.update("DELETE FROM shop_product_variants WHERE product_variant_id IN "
                + "(SELECT id FROM product_variants WHERE product_id IN (?, ?))",
                productAId, productBId);
        jdbc.update("DELETE FROM product_variants WHERE product_id IN (?, ?)",
                productAId, productBId);
        jdbc.update("DELETE FROM products WHERE id IN (?, ?)", productAId, productBId);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM shops WHERE id = ?", shopB);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantB);
        TenantDefaults.install(PlatformMode.SINGLE_SHOP,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    @Test
    @DisplayName("each shop offers what it lists, and nothing the other lists")
    void neitherShopAdvertisesTheOthersGoods() {
        Set<Long> shelfA = browsableBy(shopA);
        Set<Long> shelfB = browsableBy(shopB);

        assertTrue(shelfA.contains(productAId), "Shop A must show what Shop A lists");
        assertTrue(shelfB.contains(productBId), "Shop B must show what Shop B lists");

        assertFalse(shelfA.contains(productBId),
                "SHOP A IS ADVERTISING SHOP B'S GOODS. A customer standing in one kirana's "
                        + "storefront is shown the other's stock, and finds out it is not for "
                        + "sale here only when they try to buy it.");
        assertFalse(shelfB.contains(productAId),
                "and the same in the other direction");
    }

    @Test
    @DisplayName("delisting takes an item off this shop's storefront and nobody else's")
    void delistingIsLocal() {
        TenantContext.runWithin(TenantScope.ofShop(shopA), () -> {
            ShopProductVariant listing = listings
                    .findByProductVariantId(firstVariantOf(productAId)).orElseThrow();
            listing.setAvailable(Boolean.FALSE);
            return listings.save(listing);
        });

        assertFalse(browsableBy(shopA).contains(productAId),
                "an item this shop has taken off its shelf must stop being offered");
        assertTrue(browsableBy(shopB).contains(productBId),
                "ONE SHOP DROPPING A LINE IS NOT THE MARKETPLACE DROPPING IT. Shop B's shelf is "
                        + "not Shop A's to change.");
    }

    // ------------------------------------------------------------ fixtures

    private Set<Long> browsableBy(long shopId) {
        // THE FEED IS CACHED, per shop, and this fixture changes what is on
        // the shelf mid-test. A cached page from before the change is not a
        // stale test - it is the test asking the cache a question about the
        // database.
        java.util.Optional.ofNullable(caches.getCache("productFeed"))
                .ifPresent(org.springframework.cache.Cache::clear);
        return TenantContext.runWithin(TenantScope.ofShop(shopId), () ->
                // NEWEST FIRST, and a small page. The test database holds fifteen
                // thousand sellable products; page 0 of an unordered query says
                // nothing about whether this fixture's product is offered.
                productService.browseAll(PageRequest.of(0, 40,
                        org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "id"))).getContent().stream()
                        // BY ID, NOT BY NAME. A card carries the
                        // customer-facing name, which is deliberately not the
                        // real one for a private product - an assertion on
                        // names would be testing the privacy rule by accident.
                        .map(com.gpstore.dto.response.ProductResponse::getId)
                        .collect(Collectors.toSet()));
    }

    private Long firstVariantOf(Long productId) {
        return jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE product_id = ? ORDER BY id LIMIT 1",
                Long.class, productId);
    }

    /** A central product, listed by exactly one shop. */
    private Long newListedProduct(String name, long shopId, BigDecimal price) {
        Product product = new Product();
        product.setName(name);
        product.setCategory(categories.findById(categoryId).orElseThrow());
        product.setActive(true);
        Long productId = TenantContext.runWithin(TenantScope.platform(),
                () -> products.save(product).getId());

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setQuantity(1.0);
        variant.setUnit("kg");
        variant.setSellingPrice(price);
        variant.setAvailable(Boolean.TRUE);
        variant.setActive(Boolean.TRUE);
        Long variantId = TenantContext.runWithin(TenantScope.platform(),
                () -> variants.save(variant).getId());

        TenantContext.runWithin(TenantScope.ofShop(shopId), () -> {
            ShopProductVariant listing = new ShopProductVariant();
            listing.setProductVariantId(variantId);
            listing.setSellingPrice(price);
            listing.setAvailable(Boolean.TRUE);
            listing.setActive(Boolean.TRUE);
            return listings.save(listing);
        });
        return productId;
    }
}
