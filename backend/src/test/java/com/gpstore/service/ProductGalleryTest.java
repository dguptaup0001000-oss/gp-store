package com.gpstore.service;

import com.gpstore.dto.response.ProductResponse;
import com.gpstore.entity.*;
import com.gpstore.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Backward compatibility is the whole risk in this feature, so it is what
 * gets asserted: a product that predates product_images must behave exactly
 * as it did before, and one with a gallery must return it in the order it
 * was given.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class ProductGalleryTest {

    @Autowired private ProductService productService;
    @Autowired private CacheManager cacheManager;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository productVariantRepository;
    @Autowired private ProductImageRepository productImageRepository;

    @Test
    @DisplayName("A product with no gallery rows returns images=[] and still works")
    void legacyProductWithoutGalleryIsUnaffected() {
        Long productId = newProduct();
        clearCaches();

        ProductResponse response = productService.getProductById(productId);

        assertNotNull(response, "An existing product must still resolve");
        assertNotNull(response.getImages(), "images must never be null - clients iterate it directly");
        assertTrue(response.getImages().isEmpty(),
                "A product predating this feature must not gain a fabricated gallery");
        assertFalse(response.getVariants().isEmpty(),
                "The variant thumbnail is what such a product falls back to, so it must still be there");
    }

    @Test
    @DisplayName("A product with a gallery returns every image in sort order")
    void galleryIsReturnedInSortOrder() {
        Long productId = newProduct();
        // Inserted deliberately out of order: if the query ever loses its
        // ORDER BY, the gallery silently reshuffles between requests and
        // this is the only thing that would catch it.
        addImage(productId, "https://cdn.example.com/e.jpg", 4);
        addImage(productId, "https://cdn.example.com/a.jpg", 0);
        addImage(productId, "https://cdn.example.com/c.jpg", 2);
        addImage(productId, "https://cdn.example.com/b.jpg", 1);
        addImage(productId, "https://cdn.example.com/d.jpg", 3);
        clearCaches();

        ProductResponse response = productService.getProductById(productId);

        assertEquals(
                List.of("https://cdn.example.com/a.jpg",
                        "https://cdn.example.com/b.jpg",
                        "https://cdn.example.com/c.jpg",
                        "https://cdn.example.com/d.jpg",
                        "https://cdn.example.com/e.jpg"),
                response.getImages(),
                "The gallery must come back in sort_order, not insertion order");
    }

    @Test
    @DisplayName("Blank image URLs are dropped rather than rendered as broken tiles")
    void blankUrlsAreFiltered() {
        Long productId = newProduct();
        addImage(productId, "https://cdn.example.com/real.jpg", 0);
        addImage(productId, "   ", 1);
        clearCaches();

        ProductResponse response = productService.getProductById(productId);

        assertEquals(1, response.getImages().size(), "A blank URL is not an image");
        assertEquals("https://cdn.example.com/real.jpg", response.getImages().get(0));
    }

    private void clearCaches() {
        cacheManager.getCacheNames().forEach(n -> {
            var c = cacheManager.getCache(n);
            if (c != null) c.clear();
        });
    }

    private void addImage(Long productId, String url, int sortOrder) {
        ProductImage image = new ProductImage();
        image.setProduct(productRepository.findById(productId).orElseThrow());
        image.setImageUrl(url);
        image.setSortOrder(sortOrder);
        productImageRepository.save(image);
    }

    private Long newProduct() {
        Category category = new Category();
        category.setName("Gallery Category " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        category = categoryRepository.save(category);

        Product product = new Product();
        product.setName("Gallery Product " + System.nanoTime());
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
        variant.setImageUrl("https://cdn.example.com/variant-thumb.jpg");
        productVariantRepository.save(variant);

        return product.getId();
    }
}
