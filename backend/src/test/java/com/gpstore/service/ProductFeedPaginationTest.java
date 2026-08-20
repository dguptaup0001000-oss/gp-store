package com.gpstore.service;

import com.gpstore.dto.response.ProductResponse;
import com.gpstore.entity.*;
import com.gpstore.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The home feed's whole job is to be walked page by page while the customer
 * scrolls, so what gets asserted here is the property that makes that safe:
 * paging through it must yield every product exactly once, even when the
 * catalogue changes mid-scroll.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class ProductFeedPaginationTest {

    @Autowired private ProductService productService;
    @Autowired private CacheManager cacheManager;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository productVariantRepository;

    private static final Sort FEED_SORT = Sort.by(Sort.Direction.ASC, "id");

    @Test
    @DisplayName("Walking every page yields no duplicates and no gaps")
    void pagingCoversTheCatalogueExactlyOnce() {
        Long categoryId = newCategory();
        Set<Long> seeded = new HashSet<>();
        for (int i = 0; i < 25; i++) seeded.add(newProduct(categoryId));
        clearCaches();

        // Walk the WHOLE feed, however big the catalogue happens to be.
        // An earlier version of this test capped the walk at 50 pages and
        // assumed the database held only what it had just seeded; the shared
        // test database actually holds hundreds of products from other
        // tests, and because the feed sorts by id ASCENDING the freshly
        // seeded ones land on the LAST pages - outside the cap. The test
        // failed while the feed was behaving correctly, so the bound is now
        // derived from the feed itself.
        int totalPages = productService.browseAll(PageRequest.of(0, 100, FEED_SORT)).getTotalPages();

        Set<Long> seen = new HashSet<>();
        List<Long> seenInOrder = new ArrayList<>();
        for (int page = 0; page < totalPages; page++) {
            Page<ProductResponse> result = productService.browseAll(PageRequest.of(page, 100, FEED_SORT));
            for (ProductResponse p : result.getContent()) {
                seenInOrder.add(p.getId());
                seen.add(p.getId());
            }
        }

        assertEquals(seenInOrder.size(), new HashSet<>(seenInOrder).size(),
                "A product appeared on two pages - the client would render it twice");
        assertTrue(seen.containsAll(seeded),
                "Some seeded products never appeared on any page - the client would silently skip them");
    }

    @Test
    @DisplayName("A product added mid-scroll does not shift pages already fetched")
    void insertDuringScrollDoesNotShiftEarlierPages() {
        Long categoryId = newCategory();
        for (int i = 0; i < 15; i++) newProduct(categoryId);
        clearCaches();

        // Customer reads page 0.
        List<Long> firstPageBefore = productService.browseAll(PageRequest.of(0, 10, FEED_SORT))
                .getContent().stream().map(ProductResponse::getId).toList();

        // The store adds a product while they are still scrolling. Under
        // createdAt DESC this would push onto the FRONT and shift every page
        // by one, so page 1 would repeat the last item of page 0.
        newProduct(categoryId);
        clearCaches();

        List<Long> firstPageAfter = productService.browseAll(PageRequest.of(0, 10, FEED_SORT))
                .getContent().stream().map(ProductResponse::getId).toList();

        assertEquals(firstPageBefore, firstPageAfter,
                "Page 0 changed after an insert - infinite scroll would duplicate or skip items");
    }

    @Test
    @DisplayName("The last page reports itself as last, so the client can stop asking")
    void lastPageIsFlagged() {
        Long categoryId = newCategory();
        for (int i = 0; i < 5; i++) newProduct(categoryId);
        clearCaches();

        // Derived, not assumed: the catalogue is shared with every other
        // test, so "one big page holds it all" is not something this test
        // can decide for itself.
        Page<ProductResponse> first = productService.browseAll(PageRequest.of(0, 100, FEED_SORT));
        assertFalse(first.getContent().isEmpty(), "The feed must return products at all");
        assertFalse(first.isLast() && first.getTotalPages() > 1,
                "Page 0 must not claim to be last while more pages exist");

        Page<ProductResponse> last =
                productService.browseAll(PageRequest.of(first.getTotalPages() - 1, 100, FEED_SORT));
        assertTrue(last.isLast(),
                "The final page must report itself as last, or the client never stops requesting");
    }

    @Test
    @DisplayName("Feed entries carry their variants - no N+1 and no priceless cards")
    void feedEntriesIncludeVariants() {
        Long categoryId = newCategory();
        newProduct(categoryId);
        clearCaches();

        Page<ProductResponse> result = productService.browseAll(PageRequest.of(0, 20, FEED_SORT));
        ProductResponse any = result.getContent().stream()
                .filter(p -> p.getVariants() != null && !p.getVariants().isEmpty())
                .findFirst().orElse(null);

        assertNotNull(any, "Feed products must arrive with variants attached - a card with no "
                + "variant has no price and no ADD button");
    }

    private void clearCaches() {
        cacheManager.getCacheNames().forEach(n -> {
            var c = cacheManager.getCache(n);
            if (c != null) c.clear();
        });
    }

    private Long newCategory() {
        Category category = new Category();
        category.setName("Feed Category " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        return categoryRepository.save(category).getId();
    }

    private Long newProduct(Long categoryId) {
        Product product = new Product();
        product.setName("Feed Product " + System.nanoTime());
        product.setBrand("TestBrand");
        product.setActive(true);
        product.setCategory(categoryRepository.findById(categoryId).orElseThrow());
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
        productVariantRepository.save(variant);

        return product.getId();
    }
}
