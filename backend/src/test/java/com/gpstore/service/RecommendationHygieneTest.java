package com.gpstore.service;

import com.gpstore.dto.response.ProductResponse;
import com.gpstore.entity.*;
import com.gpstore.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Recommendations are built from order history, which means they can outlive
 * the products they name. These are the two properties that stop that being
 * visible to a customer - and they matter more now that these lists are
 * cached, because a stale entry survives for the whole TTL rather than one
 * request.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class RecommendationHygieneTest {

    @Autowired private RecommendationService recommendationService;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private CacheManager cacheManager;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;

    @Test
    @DisplayName("Trending asks the database for a bounded number of rows")
    void trendingQueryIsBounded() {
        // The bug: the GROUP BY returned one row per DISTINCT PRODUCT EVER
        // ORDERED in the window - the entire ranked leaderboard - so the
        // service could take the top ten in Java. The result set grew with
        // trading volume, on a query the home screen calls on every open.
        LocalDateTime since = LocalDateTime.now().minusDays(3650);

        List<Object[]> rows = orderItemRepository.findTrendingProductIds(since, PageRequest.of(0, 10));

        assertTrue(rows.size() <= 10,
                "The limit must be applied by the database, got " + rows.size() + " rows");
    }

    @Test
    @DisplayName("Co-purchase ranking is bounded too")
    void frequentlyBoughtQueryIsBounded() {
        List<Object[]> rows =
                orderItemRepository.findFrequentlyBoughtWithProductId(1L, PageRequest.of(0, 5));

        assertTrue(rows.size() <= 5, "got " + rows.size() + " rows");
    }

    @Test
    @DisplayName("A retired product is never recommended, however well it once sold")
    void deactivatedProductsAreNotRecommended() {
        // findByIdIn does not filter on active, so before this the shop could
        // withdraw a product and go on advertising it in Trending, "bought
        // together" and "buy again" - and once these became cached, for ten
        // minutes at a time.
        Category category = newCategory();
        Product retired = newProduct(category);
        retired.setActive(false);
        productRepository.save(retired);
        clearCaches();

        List<ProductResponse> trending = recommendationService.trending(3650, 50);

        assertTrue(trending.stream().noneMatch(p -> p.getId().equals(retired.getId())),
                "A deactivated product must not appear in trending");
        assertTrue(trending.stream().allMatch(p -> Boolean.TRUE.equals(p.getActive())),
                "Every recommended product must still be on sale");
    }

    @Test
    @DisplayName("Trending never returns more than the caller asked for")
    void trendingRespectsItsLimit() {
        clearCaches();
        assertTrue(recommendationService.trending(3650, 5).size() <= 5);
        clearCaches();
        assertTrue(recommendationService.trending(3650, 1).size() <= 1);
    }

    @Test
    @DisplayName("Trending is cached - the same call does not re-aggregate")
    void trendingIsCached() {
        // Every other browse path was already cached; this one ran a GROUP BY
        // per home-screen open. The assertion is on the cache entry rather
        // than on timing, which would be flaky.
        clearCaches();
        recommendationService.trending(7, 10);

        var cache = cacheManager.getCache("trending");
        assertNotNull(cache, "the trending cache must be configured");
        // Spring's SimpleKeyGenerator wraps multiple @Cacheable arguments in a
        // SimpleKey - NOT a List, which is what this asserted first time and
        // is why it failed while the caching worked perfectly.
        assertNotNull(cache.get(new org.springframework.cache.interceptor.SimpleKey(7, 10)),
                "a trending result must be cached, keyed on its own arguments");
    }

    private void clearCaches() {
        cacheManager.getCacheNames().forEach(n -> {
            var c = cacheManager.getCache(n);
            if (c != null) c.clear();
        });
    }

    private Category newCategory() {
        Category category = new Category();
        category.setName("Rec Category " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        return categoryRepository.save(category);
    }

    private Product newProduct(Category category) {
        Product product = new Product();
        product.setName("Rec Product " + System.nanoTime());
        product.setBrand("TestBrand");
        product.setActive(true);
        product.setCategory(category);
        product = productRepository.save(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setQuantity(1.0);
        variant.setUnit("pc");
        variant.setMrp(new BigDecimal("100"));
        variant.setSellingPrice(new BigDecimal("90"));
        variant.setCostPrice(new BigDecimal("60"));
        variant.setAvailable(true);
        variant.setActive(true);
        variantRepository.save(variant);
        return product;
    }
}
