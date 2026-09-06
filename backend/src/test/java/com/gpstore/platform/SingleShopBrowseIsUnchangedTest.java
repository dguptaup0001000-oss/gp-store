package com.gpstore.platform;

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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE SHOP THAT IS ALREADY TRADING KEEPS TRADING (§12).
 *
 * Everything the marketplace slices added to browsing is switched off under
 * SINGLE_SHOP, and this is the test that says so in terms of behaviour rather
 * than of configuration. Its fixture is the exact row the narrowing would
 * lose: a product with a real, priced, available variant that has NO
 * shop_product_variants row at all - which is what most of the live catalogue
 * looked like before listings existed, and what any variant priced by an
 * admin without listing it still looks like.
 *
 * If the shelf narrowing ever runs when it should not, this product vanishes
 * from the shop it is being sold in, on every screen at once. So every screen
 * is asked, not just the feed: the sibling test for the marketplace side
 * (EveryBrowseSurfaceShowsOneShelfTest) walks the same list in the other
 * direction.
 */
@SpringBootTest(properties = {
        "platform.mode=SINGLE_SHOP",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("One shop browses exactly as it always did")
class SingleShopBrowseIsUnchangedTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;
    @Autowired private ProductService productService;
    @Autowired private ProductBrowseRepository browseRepository;
    @Autowired private org.springframework.cache.CacheManager caches;
    @Autowired private CategoryRepository categories;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;

    private final String tag = "single" + System.nanoTime();
    private final String brand = "Unlistedbrand" + tag;

    private long shopId;
    private Long categoryId;
    private Long productId;
    private Long variantId;

    @BeforeEach
    void aPricedProductNobodyEverListed() {
        shopId = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();

        Category category = new Category();
        category.setName("Single category " + tag);
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        categoryId = categories.save(category).getId();

        Product product = new Product();
        product.setName("Priced but never listed " + tag);
        product.setBrand(brand);
        product.setCategory(categories.findById(categoryId).orElseThrow());
        product.setActive(true);
        productId = products.save(product).getId();

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setQuantity(1.0);
        variant.setUnit("kg");
        variant.setSellingPrice(new BigDecimal("77.00"));
        variant.setMrp(new BigDecimal("90.00"));
        variant.setAvailable(Boolean.TRUE);
        variant.setActive(Boolean.TRUE);
        variantId = variants.save(variant).getId();

        // THE POINT OF THE FIXTURE. Whatever else may create a listing row,
        // this product has none - that is the state §12 is about.
        jdbc.update("DELETE FROM shop_product_variants WHERE product_variant_id = ?", variantId);
    }

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        jdbc.update("DELETE FROM shop_product_variants WHERE product_variant_id = ?", variantId);
        jdbc.update("DELETE FROM product_variants WHERE id = ?", variantId);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        clearBrowseCaches();
    }

    @Test
    @DisplayName("it is on every browse surface, listing row or not")
    void everySurfaceStillShowsIt() {
        assertShown("the home feed", () -> ids(productService.browseAll(newestFirst()).getContent()));
        assertShown("GET /api/products", () -> ids(productService.getAllProducts(newestFirst())));
        assertShown("New Arrivals",
                () -> ids(productService.getNewArrivals(PageRequest.of(0, 40)).getContent()));
        assertShown("category browse", () -> ids(
                productService.browseByCategory(categoryId, PageRequest.of(0, 40)).getContent()));
        assertShown("category browse, sorted", () -> idsOf(
                productService.browseByCategoryFiltered(categoryId, "PRICE_LOW_HIGH", false, null, 0, 50)));
        assertShown("category browse, in stock only", () -> idsOf(
                productService.browseByCategoryFiltered(categoryId, "NAME_ASC", true, null, 0, 50)));
        assertShown("shop by brand", () -> idsOf(
                productService.browseByBrand(brand, "NAME_ASC", false, null, 0, 50)));
        assertShown("instant search",
                () -> ids(productService.searchInstant(tag, PageRequest.of(0, 50)).getContent()));
        assertShown("the bestsellers collage", () ->
                browseRepository.findBestsellerTiles(List.of(categoryId), 12, 8).stream()
                        .map(ProductBrowseRepository.BestsellerRow::productId)
                        .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    @Test
    @DisplayName("its brand still counts, and its product page still opens")
    void brandAndDetailAreUnchanged() {
        Set<String> brands = read(() -> productService.getBrandsWithCounts().stream()
                .map(BrandSummary::getBrand).collect(Collectors.toSet()));
        assertTrue(brands.contains(brand),
                "a brand whose only product has no listing row must keep its tile under one shop");

        assertNotNull(read(() -> productService.getProductById(productId)),
                "AND ITS PRODUCT PAGE MUST STILL OPEN. Requiring a listing here would 404 every "
                        + "product a single-shop catalogue priced before listings existed.");
    }

    // --------------------------------------------------------------- helpers

    private void assertShown(String surface, Supplier<Set<Long>> work) {
        assertTrue(read(work).contains(productId),
                surface + ": A LIVE PRODUCT HAS VANISHED FROM A WORKING SHOP. It is priced, "
                        + "available and active; it simply has no shop_product_variants row, "
                        + "which under one shop has never been required and must not become so.");
    }

    private PageRequest newestFirst() {
        return PageRequest.of(0, 40, Sort.by(Sort.Direction.DESC, "id"));
    }

    private <T> T read(Supplier<T> work) {
        clearBrowseCaches();
        // A single-shop deployment always has a shop in scope - TenantResolver
        // hands every request Shop #1 - so this is the ordinary case.
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
        return content.stream().map(ProductResponse::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @SuppressWarnings("unchecked")
    private static Set<Long> idsOf(Map<String, Object> browseResponse) {
        return ids((List<ProductResponse>) browseResponse.get("content"));
    }
}
