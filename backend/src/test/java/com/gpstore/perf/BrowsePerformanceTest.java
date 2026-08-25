package com.gpstore.perf;

import com.gpstore.entity.*;
import com.gpstore.repository.*;
import com.gpstore.service.ProductService;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Forensic measurement of the BROWSE path - the traffic the distributed load
 * test actually generates (browse VUs, cart/checkout at zero).
 *
 * The checkout perf tests measure the write path. Nothing measured the read
 * path, which is the one that was under 10,000 concurrent users, so the cost
 * of a single browse request was never a known number.
 *
 * Cold vs warm is measured separately on purpose. These endpoints are
 * @Cacheable, so the warm number is what production mostly serves - but the
 * cold number is what every instance pays after a deploy, after a TTL
 * expiry, and for every distinct (category, page) or (keyword, page) key. A
 * load test that walks many categories and keywords is mostly paying the
 * COLD cost, which is why it has to be measured rather than assumed away.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "outbox.purge-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "payment.expiry-interval-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "idempotency.cleanup-interval-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-interval-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000",
        "delivery.late-flag-interval-ms=3600000"
})
class BrowsePerformanceTest {

    private static final int PRODUCTS_PER_CATEGORY = 20;

    @Autowired private ProductService productService;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private CacheManager cacheManager;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository productVariantRepository;
    @Autowired private InventoryRepository inventoryRepository;

    @Test
    @DisplayName("Cost of one browse-category request, cold and warm")
    void browseCategoryCost() {
        Long categoryId = seedCategory(PRODUCTS_PER_CATEGORY);
        clearAllCaches();

        QueryCounter.Result cold = QueryCounter.measure(entityManagerFactory,
                () -> productService.browseByCategory(categoryId, PageRequest.of(0, 20)));
        QueryCounter.Result warm = QueryCounter.measure(entityManagerFactory,
                () -> productService.browseByCategory(categoryId, PageRequest.of(0, 20)));

        System.out.println("[BROWSE] category (" + PRODUCTS_PER_CATEGORY + " products) COLD: " + cold);
        System.out.println("[BROWSE] category (" + PRODUCTS_PER_CATEGORY + " products) WARM: " + warm);
        assertTrue(cold.queryCount() > 0, "a cold catalog browse must hit PostgreSQL");
        assertEquals(0L, warm.queryCount(),
                "a warm L1/Redis catalog hit must not query PostgreSQL on every request");
    }

    @Test
    @DisplayName("Cost of one instant-search request, cold and warm")
    void instantSearchCost() {
        seedCategory(PRODUCTS_PER_CATEGORY);
        clearAllCaches();

        QueryCounter.Result cold = QueryCounter.measure(entityManagerFactory,
                () -> productService.searchInstant("Perf", PageRequest.of(0, 20)));
        QueryCounter.Result warm = QueryCounter.measure(entityManagerFactory,
                () -> productService.searchInstant("Perf", PageRequest.of(0, 20)));

        System.out.println("[BROWSE] search-instant COLD: " + cold);
        System.out.println("[BROWSE] search-instant WARM: " + warm);
        assertTrue(cold.queryCount() > 0, "a cold search must hit PostgreSQL");
        assertEquals(0L, warm.queryCount(),
                "a warm search cache hit must not query PostgreSQL on every request");
    }

    @Test
    @DisplayName("Cost of one product-detail request, cold and warm")
    void productDetailCost() {
        Long categoryId = seedCategory(3);
        Long productId = productRepository.findByCategoryIdAndActiveTrue(categoryId, PageRequest.of(0, 1))
                .getContent().get(0).getId();
        clearAllCaches();

        QueryCounter.Result cold = QueryCounter.measure(entityManagerFactory,
                () -> productService.getProductById(productId));
        QueryCounter.Result warm = QueryCounter.measure(entityManagerFactory,
                () -> productService.getProductById(productId));

        System.out.println("[BROWSE] product-detail COLD: " + cold);
        System.out.println("[BROWSE] product-detail WARM: " + warm);
    }

    /**
     * The full k6 browse iteration: category, then search 10% of the time,
     * then one product detail. Reported as the cost of ONE simulated user
     * action, which is the unit that matters when reasoning about how many
     * concurrent users a fixed connection pool can serve.
     */
    @Test
    @DisplayName("Cost of one complete k6 browse iteration, all cold")
    void fullBrowseIterationCostCold() {
        Long categoryId = seedCategory(PRODUCTS_PER_CATEGORY);
        Long productId = productRepository.findByCategoryIdAndActiveTrue(categoryId, PageRequest.of(0, 1))
                .getContent().get(0).getId();
        clearAllCaches();

        QueryCounter.Result iteration = QueryCounter.measure(entityManagerFactory, () -> {
            productService.browseByCategory(categoryId, PageRequest.of(0, 20));
            productService.searchInstant("Perf", PageRequest.of(0, 20));
            productService.getProductById(productId);
        });

        System.out.println("[BROWSE] FULL ITERATION (category+search+detail) COLD: " + iteration);
    }

    /**
     * Serialized size of one browse-category response.
     *
     * Measured because the load test received ~215 KB per request on
     * average, which is not the shape of an error body - and because on 0.5
     * vCPU, JSON serialization of a large payload is real CPU time on the
     * request thread, not a rounding error. Query count says nothing about
     * this cost.
     */
    @Test
    @DisplayName("Serialized JSON size of one browse-category response")
    void browseCategoryPayloadSize() throws Exception {
        Long categoryId = seedCategory(PRODUCTS_PER_CATEGORY);
        clearAllCaches();

        var page = productService.browseByCategory(categoryId, PageRequest.of(0, 20));

        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        byte[] json = mapper.writeValueAsBytes(page.getContent());

        System.out.println("[BROWSE] category payload: " + json.length + " bytes for "
                + page.getContent().size() + " products ("
                + (page.getContent().isEmpty() ? 0 : json.length / page.getContent().size())
                + " bytes/product)");
        page.getContent().forEach(product ->
                assertTrue(product.getVariants() == null || product.getVariants().size() <= 1,
                        "browse listings must send at most one representative variant"));
    }

    private void clearAllCaches() {
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        });
    }

    private Long seedCategory(int products) {
        Category category = new Category();
        category.setName("Perf Category " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        category = categoryRepository.save(category);

        for (int i = 0; i < products; i++) {
            Product product = new Product();
            product.setName("Perf Item " + System.nanoTime());
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
            variant = productVariantRepository.save(variant);

            Inventory inventory = new Inventory();
            inventory.setProductVariant(variant);
            inventory.setStock(500);
            inventoryRepository.save(inventory);
        }
        return category.getId();
    }
}
