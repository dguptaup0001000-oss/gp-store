package com.gpstore.platform;

import com.gpstore.catalog.shop.ShopProductVariant;
import com.gpstore.catalog.shop.ShopProductVariantRepository;
import com.gpstore.dto.response.BrandSummary;
import com.gpstore.dto.response.ProductResponse;
import com.gpstore.entity.Category;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.ProductBrowseRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;
import com.gpstore.service.ProductService;
import com.gpstore.service.RecommendationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EVERY WAY INTO THE CATALOGUE STOPS AT THIS SHOP'S SHELF.
 *
 * Slice 16 fixed the feed and category browse, which were JPQL. It did not
 * fix search, brand browse, filtered category browse, the bestseller collage,
 * the brand counts, the product page or the recommendation rails - all of
 * which reach the catalogue by a different route, and four of which are
 * native SQL where Hibernate's shop filter does not apply at all. One
 * un-narrowed route is the whole leak: a customer standing in Shop A who
 * types a brand into search, or opens a shared link, or taps a bestseller
 * tile, is looking at Shop B's stock again.
 *
 * SO THIS TEST IS A MATRIX, not an example. It walks every browse surface the
 * customer app can reach and asks each one the same two questions: does Shop
 * A see what Shop A lists, and does Shop A see anything Shop B lists. A
 * surface added later without the narrowing does not quietly pass here - it
 * is simply not covered, which is why the list below is written out one
 * method per surface rather than looped over an abstraction.
 *
 * THE CACHES ARE CLEARED BEFORE EVERY READ. Nearly all of these surfaces are
 * @Cacheable and the fixture changes the shelf mid-test; a cached answer from
 * before the change would be the test asking the cache a question about the
 * database. (Cache keys are already shop-aware - see CacheConfig.keyGenerator
 * - so this is about staleness, not about cross-shop bleed.)
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
@DisplayName("Every browse surface shows one shelf")
class EveryBrowseSurfaceShowsOneShelfTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private ProductService productService;
    @Autowired private RecommendationService recommendations;
    @Autowired private ProductBrowseRepository browseRepository;
    @Autowired private org.springframework.cache.CacheManager caches;
    @Autowired private CategoryRepository categories;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;
    @Autowired private ShopProductVariantRepository listings;

    private final String tag = "surface" + System.nanoTime();
    private final String brandA = "OnlyAbrand" + tag;
    private final String brandB = "OnlyBbrand" + tag;

    private long shopA;
    private long shopB;
    private Long merchantB;
    private Long categoryId;
    private Long productAId;
    private Long productBId;
    private Long orderId;

    @BeforeEach
    void twoShopsWithDifferentShelves() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
        shopA = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();

        Merchant second = new Merchant();
        second.setLegalName("Surface fixture " + tag);
        second.setDisplayName("Surface B");
        second.setStatus(MerchantStatus.ACTIVE);
        second.setIsDemo(Boolean.TRUE);
        second.setActive(Boolean.TRUE);
        merchantB = merchants.save(second).getId();

        Shop b = new Shop();
        b.setMerchantId(merchantB);
        b.setCode("SURF-" + tag);
        b.setDisplayName("Surface B");
        b.setStatus(ShopStatus.ACTIVE);
        b.setIsDemo(Boolean.TRUE);
        b.setActive(Boolean.TRUE);
        shopB = shops.save(b).getId();

        Category category = new Category();
        category.setName("Surface category " + tag);
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        categoryId = categories.save(category).getId();

        productAId = newListedProduct("Only A sells this " + tag, brandA, shopA, new BigDecimal("60.00"));
        productBId = newListedProduct("Only B sells this " + tag, brandB, shopB, new BigDecimal("185.00"));
    }

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        if (orderId != null) {
            jdbc.update("DELETE FROM order_items WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM orders WHERE id = ?", orderId);
            orderId = null;
        }
        jdbc.update("DELETE FROM shop_product_variants WHERE product_variant_id IN "
                        + "(SELECT id FROM product_variants WHERE product_id IN (?, ?))",
                productAId, productBId);
        jdbc.update("DELETE FROM product_variants WHERE product_id IN (?, ?)", productAId, productBId);
        jdbc.update("DELETE FROM products WHERE id IN (?, ?)", productAId, productBId);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM shops WHERE id = ?", shopB);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantB);
        clearBrowseCaches();
        TenantDefaults.install(PlatformMode.SINGLE_SHOP,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    // ------------------------------------------------------------- surfaces

    @Test
    @DisplayName("the endless feed")
    void feed() {
        assertShelfOnly("the home feed", shop -> ids(
                productService.browseAll(newestFirst()).getContent()));
    }

    @Test
    @DisplayName("the customer product list")
    void productList() {
        assertShelfOnly("GET /api/products", shop -> ids(
                productService.getAllProducts(newestFirst())));
    }

    @Test
    @DisplayName("new arrivals")
    void newArrivals() {
        assertShelfOnly("New Arrivals", shop -> ids(
                productService.getNewArrivals(PageRequest.of(0, 40)).getContent()));
    }

    @Test
    @DisplayName("category browse")
    void categoryBrowse() {
        assertShelfOnly("category browse", shop -> ids(
                productService.browseByCategory(categoryId, PageRequest.of(0, 40)).getContent()));
    }

    @Test
    @DisplayName("category browse with a sort and a filter")
    void filteredCategoryBrowse() {
        // Every sort option, because each one reads a different aggregate and
        // the shelf narrowing lives in the aggregates. BEST_SELLING in
        // particular reads order history, which is shop-owned data.
        for (String sort : List.of("PRICE_LOW_HIGH", "PRICE_HIGH_LOW", "NAME_ASC", "NEWEST",
                "DISCOUNT", "BEST_SELLING", "HIGHEST_RATED")) {
            assertShelfOnly("category browse sorted by " + sort, shop -> idsOf(
                    productService.browseByCategoryFiltered(categoryId, sort, false, null, 0, 50)));
        }
    }

    @Test
    @DisplayName("category browse filtered to in-stock only")
    void inStockOnlyBrowse() {
        assertShelfOnly("category browse, in stock only", shop -> idsOf(
                productService.browseByCategoryFiltered(categoryId, "NAME_ASC", true, null, 0, 50)));
    }

    @Test
    @DisplayName("shop by brand")
    void brandBrowse() {
        // Asked by the OTHER shop's brand name: this is the query a customer
        // runs after seeing a brand tile that should not have been there.
        Set<Long> aOnBsBrand = read(shopA, () -> idsOf(
                productService.browseByBrand(brandB, "NAME_ASC", false, null, 0, 50)));
        assertTrue(aOnBsBrand.isEmpty(),
                "SHOP A BROWSED A BRAND ONLY SHOP B STOCKS AND WAS SHOWN SHOP B'S PRODUCT: "
                        + aOnBsBrand);

        Set<Long> aOnItsOwnBrand = read(shopA, () -> idsOf(
                productService.browseByBrand(brandA, "NAME_ASC", false, null, 0, 50)));
        assertTrue(aOnItsOwnBrand.contains(productAId),
                "and Shop A must still find its own brand");
    }

    @Test
    @DisplayName("the brand list, and its counts")
    void brandCounts() {
        Set<String> brandsA = read(shopA, () -> productService.getBrandsWithCounts().stream()
                .map(BrandSummary::getBrand).collect(Collectors.toSet()));
        Set<String> brandsB = read(shopB, () -> productService.getBrandsWithCounts().stream()
                .map(BrandSummary::getBrand).collect(Collectors.toSet()));

        assertTrue(brandsA.contains(brandA), "Shop A stocks its own brand");
        assertFalse(brandsA.contains(brandB),
                "SHOP A IS OFFERING A BRAND TILE FOR A BRAND IT DOES NOT STOCK. The tile opens "
                        + "on an empty grid, and the count on it is a count of the shop next door.");
        assertTrue(brandsB.contains(brandB));
        assertFalse(brandsB.contains(brandA), "and the same in the other direction");
    }

    @Test
    @DisplayName("instant search")
    void search() {
        // One keyword that matches BOTH fixture products by name. An
        // un-narrowed search answers with both, whichever shop is asking.
        assertShelfOnly("instant search", shop -> ids(
                productService.searchInstant(tag, PageRequest.of(0, 50)).getContent()));
    }

    @Test
    @DisplayName("instant search for a brand this shop has never stocked")
    void searchForTheOtherShopsBrand() {
        Set<Long> found = read(shopA, () -> ids(
                productService.searchInstant(brandB, PageRequest.of(0, 50)).getContent()));
        // NOT "empty": instant search is deliberately fuzzy, and the two
        // fixture brands are one character apart, so Shop A's own product is
        // a legitimate near-match for a brand it does not stock. What must
        // never come back is the product behind that brand.
        assertFalse(found.contains(productBId),
                "SEARCH IS THE EASIEST DOOR INTO ANOTHER SHOP'S STOCK - type a brand this shop "
                        + "has never carried and, unnarrowed, the marketplace answers: " + found);
    }

    @Test
    @DisplayName("the bestsellers collage")
    void bestsellerTiles() {
        assertShelfOnly("the bestsellers collage", shop ->
                browseRepository.findBestsellerTiles(List.of(categoryId), 12, 8).stream()
                        .map(ProductBrowseRepository.BestsellerRow::productId)
                        .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    @Test
    @DisplayName("the collage's \"+N more\" counts this shop's shelf")
    void bestsellerTotalsAreThisShopsShelf() {
        long totalForA = read(shopA, () ->
                browseRepository.findBestsellerTiles(List.of(categoryId), 12, 8).stream()
                        .map(ProductBrowseRepository.BestsellerRow::categoryTotal)
                        .findFirst().orElse(0L));
        // The category was created by this fixture and holds exactly two
        // products, one per shop. A tile that says "2" is counting the shop
        // next door's product into this shop's "+N more".
        assertTrue(totalForA == 1L,
                "the tile promises N more products in this category and must mean N this shop "
                        + "sells; it said " + totalForA);
    }

    @Test
    @DisplayName("the product page, reached by id")
    void productDetail() {
        assertNotNull(read(shopA, () -> productService.getProductById(productAId)),
                "Shop A must be able to open its own product");
        assertNull(read(shopA, () -> productService.getProductById(productBId)),
                "A DEEP LINK, A SHARED CARD OR A TYPED ID MUST NOT OPEN ANOTHER SHOP'S PRODUCT "
                        + "PAGE. Every list around it is narrowed; if this one is not, the "
                        + "narrowing is decoration - the catalogue is readable one id at a time.");
        assertNull(read(shopB, () -> productService.getProductById(productAId)),
                "and the same in the other direction");
    }

    @Test
    @DisplayName("trending stops recommending what this shop has delisted")
    void trendingFollowsTheShelf() {
        // SHOP B, because Shop #1 carries the test database's whole trading
        // history: one fixture order there ranks below hundreds of real ones
        // and never reaches the leaderboard, which would make this test pass
        // for the wrong reason. Shop B was created a moment ago and has sold
        // exactly what this line sells it.
        orderId = placeOrderFor(shopB, firstVariantOf(productBId));

        Set<Long> before = read(shopB, () -> ids(recommendations.trending(30, 20)));
        assertTrue(before.contains(productBId),
                "the fixture's own order must make its product trend, or this test proves nothing");

        delist(shopB, firstVariantOf(productBId));

        Set<Long> after = read(shopB, () -> ids(recommendations.trending(30, 20)));
        assertFalse(after.contains(productBId),
                "\"WE SOLD IT HERE ONCE\" IS NOT \"WE SELL IT HERE NOW\". A shop that has taken a "
                        + "line off its shelf must stop recommending it - the card would carry no "
                        + "price of this shop's, because there is no listing behind it.");
    }

    // ------------------------------------------------------------ assertions

    /**
     * The two questions every surface answers: this shop sees its own, and
     * this shop sees nothing of the other's - in both directions, so a
     * narrowing that accidentally pins every shop to Shop #1 fails too.
     */
    private void assertShelfOnly(String surface, java.util.function.Function<Long, Set<Long>> read) {
        Set<Long> seenByA = read(shopA, () -> read.apply(shopA));
        Set<Long> seenByB = read(shopB, () -> read.apply(shopB));

        assertTrue(seenByA.contains(productAId), surface + ": Shop A must show what Shop A lists");
        assertTrue(seenByB.contains(productBId), surface + ": Shop B must show what Shop B lists");
        assertFalse(seenByA.contains(productBId),
                surface + ": SHOP A IS SHOWING SHOP B'S INVENTORY");
        assertFalse(seenByB.contains(productAId),
                surface + ": SHOP B IS SHOWING SHOP A'S INVENTORY");
    }

    // -------------------------------------------------------------- fixtures

    private PageRequest newestFirst() {
        // The test database holds fifteen thousand sellable products; page 0
        // of an unordered query says nothing about whether this fixture's
        // product is offered.
        return PageRequest.of(0, 40, Sort.by(Sort.Direction.DESC, "id"));
    }

    private <T> T read(long shopId, Supplier<T> work) {
        clearBrowseCaches();
        return TenantContext.runWithin(TenantScope.ofShop(shopId), work::get);
    }

    private void clearBrowseCaches() {
        for (String name : List.of("products", "brands", "newArrivals", "categoryProducts",
                "productDetail", "productSearch", "productFeed", "bestsellerTiles",
                "trending", "frequentlyBought")) {
            java.util.Optional.ofNullable(caches.getCache(name))
                    .ifPresent(org.springframework.cache.Cache::clear);
        }
    }

    private static Set<Long> ids(List<ProductResponse> content) {
        // BY ID, NOT BY NAME. A card carries the customer-facing name, which
        // is deliberately not the real one for a private product.
        return content.stream().map(ProductResponse::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @SuppressWarnings("unchecked")
    private static Set<Long> idsOf(Map<String, Object> browseResponse) {
        return ids((List<ProductResponse>) browseResponse.get("content"));
    }

    private Long firstVariantOf(Long productId) {
        return jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE product_id = ? ORDER BY id LIMIT 1",
                Long.class, productId);
    }

    private void delist(long shopId, Long variantId) {
        TenantContext.runWithin(TenantScope.ofShop(shopId), () -> {
            ShopProductVariant listing = listings.findByProductVariantId(variantId).orElseThrow();
            listing.setAvailable(Boolean.FALSE);
            return listings.save(listing);
        });
    }

    /** A minimal delivered order, so the trending aggregate has something real to count. */
    private Long placeOrderFor(long shopId, Long variantId) {
        String number = "SURF-" + System.nanoTime();
        jdbc.update("INSERT INTO orders (order_number, shop_id, order_date, total_amount, order_status) "
                + "VALUES (?, ?, now(), ?, ?)", number, shopId, new BigDecimal("60.00"), "DELIVERED");
        Long id = jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number = ?", Long.class, number);
        jdbc.update("INSERT INTO order_items (id, order_id, product_variant_id, quantity, price, "
                        + "total_price, active) VALUES (nextval('order_items_seq'), ?, ?, 3, ?, ?, true)",
                id, variantId, new BigDecimal("60.00"), new BigDecimal("180.00"));
        return id;
    }

    /** A central product, listed by exactly one shop. */
    private Long newListedProduct(String name, String brand, long shopId, BigDecimal price) {
        Product product = new Product();
        product.setName(name);
        product.setBrand(brand);
        product.setCategory(categories.findById(categoryId).orElseThrow());
        product.setActive(true);
        Long productId = TenantContext.runWithin(TenantScope.platform(),
                () -> products.save(product).getId());

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setQuantity(1.0);
        variant.setUnit("kg");
        variant.setSellingPrice(price);
        variant.setMrp(price.add(new BigDecimal("10.00")));
        variant.setAvailable(Boolean.TRUE);
        variant.setActive(Boolean.TRUE);
        Long variantId = TenantContext.runWithin(TenantScope.platform(),
                () -> variants.save(variant).getId());

        TenantContext.runWithin(TenantScope.ofShop(shopId), () -> {
            ShopProductVariant listing = new ShopProductVariant();
            listing.setCommerceMode(com.gpstore.catalog.shop.CommerceMode.ONLINE_PURCHASE);
            listing.setProductVariantId(variantId);
            listing.setSellingPrice(price);
            listing.setAvailable(Boolean.TRUE);
            listing.setActive(Boolean.TRUE);
            return listings.save(listing);
        });
        return productId;
    }
}
