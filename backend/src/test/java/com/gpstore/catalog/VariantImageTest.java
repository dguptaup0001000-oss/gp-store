package com.gpstore.catalog;

import com.gpstore.entity.Category;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductImage;
import com.gpstore.entity.ProductVariant;
import com.gpstore.exception.BadRequestException;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.ProductImageRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;
import com.gpstore.service.ProductService;
import com.gpstore.service.VariantImageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Photos of a variant: the 1 kg packet's front, back and side.
 *
 * THE TWO THINGS THAT MATTER MOST HERE ARE BOTH ABOUT NOT BREAKING ANYTHING.
 *
 * A variant nobody has photographed - which is every variant that existed
 * before V22 - must keep working exactly as it did, with its single
 * imageUrl and an empty gallery. And a product's own gallery rows, written
 * before variants could have any, must not start belonging to a variant.
 * Those two are asserted before any of the new behaviour is.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("Variant photos")
class VariantImageTest {

    private static final String MARKER = "VARIANT_IMAGE_TEST";

    @Autowired private VariantImageService imageService;
    @Autowired private ProductService productService;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private ProductImageRepository imageRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private org.springframework.cache.CacheManager cacheManager;
    @Autowired private JdbcTemplate jdbc;

    private Product product;
    private ProductVariant oneKg;
    private ProductVariant fiveHundredG;

    @BeforeEach
    void setUp() {
        cleanUp();

        Category category = new Category();
        category.setName(MARKER + "-category");
        category.setActive(true);
        category = categoryRepository.save(category);

        product = new Product();
        product.setName(MARKER + "-nirma-namak");
        product.setBrand("Nirma");
        product.setCategory(category);
        product.setActive(true);
        product = productRepository.save(product);

        oneKg = newVariant(1.0, "kg", "https://cdn.example.com/original-1kg.jpg");
        fiveHundredG = newVariant(500.0, "g", "https://cdn.example.com/original-500g.jpg");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM product_images WHERE product_id IN "
                + "(SELECT id FROM products WHERE name LIKE ?)", MARKER + "%");
        jdbc.update("DELETE FROM product_variants WHERE product_id IN "
                + "(SELECT id FROM products WHERE name LIKE ?)", MARKER + "%");
        jdbc.update("DELETE FROM products WHERE name LIKE ?", MARKER + "%");
        jdbc.update("DELETE FROM categories WHERE name LIKE ?", MARKER + "%");
    }

    // ------------------------------------------- nothing existing may break

    @Test
    @DisplayName("a variant nobody has photographed keeps the thumbnail it always had")
    void existingSingleImageVariantsAreUntouched() {
        // The state every variant in the database is in right now.
        assertEquals(List.of(), imageService.imagesFor(oneKg.getId()),
                "A variant with no photo rows must report an empty gallery, not fail.");

        assertEquals("https://cdn.example.com/original-1kg.jpg",
                variantRepository.findById(oneKg.getId()).orElseThrow().getImageUrl(),
                "The existing imageUrl must not have been touched by anything.");
    }

    @Test
    @DisplayName("a product's own gallery rows do not become a variant's")
    void productLevelImagesStayProductLevel() {
        // Rows written before V22 have a null variant. They must keep meaning
        // what they meant: pictures of the product, not of any one pack size.
        ProductImage productLevel = new ProductImage();
        productLevel.setProduct(product);
        productLevel.setImageUrl("https://cdn.example.com/product-wide.jpg");
        productLevel.setSortOrder(0);
        imageRepository.save(productLevel);

        assertEquals(List.of(), imageService.imagesFor(oneKg.getId()),
                "A product-level image leaked into a variant's gallery.");
        assertEquals(1, imageRepository.findByProductIdOrderBySortOrderAsc(product.getId()).size(),
                "The product's own gallery must still be readable.");
    }

    // --------------------------------------------------------- the feature

    @Test
    @DisplayName("five photos are stored in the order they were sent")
    void orderIsPreservedBecauseTheFirstOneIsPrimary() {
        List<String> sent = List.of(
                "https://cdn.example.com/front.jpg",
                "https://cdn.example.com/back.jpg",
                "https://cdn.example.com/side.jpg",
                "https://cdn.example.com/ingredients.jpg",
                "https://cdn.example.com/extra.jpg");

        assertEquals(sent, imageService.replaceImages(oneKg.getId(), sent));
        assertEquals(sent, imageService.imagesFor(oneKg.getId()),
                "Order is meaning here - the first photo is the one every listing shows.");
    }

    @Test
    @DisplayName("the primary photo becomes the variant's thumbnail everywhere else")
    void thePrimaryPhotoDrivesTheExistingField() {
        // THE BACKWARD-COMPATIBILITY HINGE. Listings, cart lines and order
        // items all read imageUrl and know nothing about galleries. Keeping it
        // equal to the first photo is what makes this feature appear
        // everywhere the old field already did, with no client change.
        imageService.replaceImages(oneKg.getId(), List.of(
                "https://cdn.example.com/front.jpg", "https://cdn.example.com/back.jpg"));

        assertEquals("https://cdn.example.com/front.jpg",
                variantRepository.findById(oneKg.getId()).orElseThrow().getImageUrl());
    }

    @Test
    @DisplayName("a sixth photo is refused")
    void theLimitIsFive() {
        List<String> six = List.of("a", "b", "c", "d", "e", "f").stream()
                .map(n -> "https://cdn.example.com/" + n + ".jpg")
                .toList();

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> imageService.replaceImages(oneKg.getId(), six));
        assertTrue(refused.getMessage().contains("5"),
                "The refusal should say what the limit is.");

        assertEquals(List.of(), imageService.imagesFor(oneKg.getId()),
                "A refused request must write nothing at all.");
    }

    @Test
    @DisplayName("the same photo picked twice is stored once")
    void duplicatesAreCollapsed() {
        // A real thing a person does at a picker. Two identical thumbnails in
        // a gallery reads as a broken shop rather than a slip.
        List<String> withDupes = List.of(
                "https://cdn.example.com/front.jpg",
                "https://cdn.example.com/back.jpg",
                "https://cdn.example.com/front.jpg");

        assertEquals(
                List.of("https://cdn.example.com/front.jpg", "https://cdn.example.com/back.jpg"),
                imageService.replaceImages(oneKg.getId(), withDupes),
                "The first occurrence wins, so the primary photo is still what the admin put first.");
    }

    @Test
    @DisplayName("re-sending the same list is harmless")
    void replacingIsIdempotent() {
        List<String> five = List.of("1", "2", "3", "4", "5").stream()
                .map(n -> "https://cdn.example.com/" + n + ".jpg")
                .toList();

        imageService.replaceImages(oneKg.getId(), five);
        // The retry after a dropped response. If the delete and the inserts
        // reached the database in the wrong order this would trip the
        // five-image trigger from V22 - which is exactly what it did before
        // the flush between them.
        assertDoesNotThrow(() -> imageService.replaceImages(oneKg.getId(), five));

        assertEquals(five, imageService.imagesFor(oneKg.getId()));
        assertEquals(5, imageRepository.countByProductVariantId(oneKg.getId()),
                "Re-sending must replace, not append.");
    }

    @Test
    @DisplayName("removing a photo is just sending the shorter list")
    void removalIsAReplace() {
        imageService.replaceImages(oneKg.getId(), List.of(
                "https://cdn.example.com/front.jpg",
                "https://cdn.example.com/back.jpg",
                "https://cdn.example.com/side.jpg"));

        assertEquals(
                List.of("https://cdn.example.com/front.jpg", "https://cdn.example.com/side.jpg"),
                imageService.replaceImages(oneKg.getId(), List.of(
                        "https://cdn.example.com/front.jpg",
                        "https://cdn.example.com/side.jpg")));
    }

    @Test
    @DisplayName("two variants of one product keep separate photos")
    void variantsDoNotShareGalleries() {
        // The whole reason these hang off the variant: the 1 kg packet and the
        // 500 g packet are different pictures.
        imageService.replaceImages(oneKg.getId(), List.of("https://cdn.example.com/1kg-front.jpg"));
        imageService.replaceImages(fiveHundredG.getId(), List.of("https://cdn.example.com/500g-front.jpg"));

        assertEquals(List.of("https://cdn.example.com/1kg-front.jpg"),
                imageService.imagesFor(oneKg.getId()));
        assertEquals(List.of("https://cdn.example.com/500g-front.jpg"),
                imageService.imagesFor(fiveHundredG.getId()));
    }

    // --------------------------------------------------- what the app sees

    @Test
    @DisplayName("product detail carries each variant's photos; listings do not")
    void galleriesReachTheDetailScreenOnly() {
        imageService.replaceImages(oneKg.getId(), List.of(
                "https://cdn.example.com/1kg-front.jpg", "https://cdn.example.com/1kg-back.jpg"));

        // replaceImages evicts the catalogue caches, but this test asks the
        // service directly and a stale entry from an earlier assertion would
        // make the result meaningless.
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());

        var detail = productService.getProductById(product.getId());
        assertNotNull(detail);

        var detailVariant = detail.getVariants().stream()
                .filter(v -> v.getId().equals(oneKg.getId()))
                .findFirst().orElseThrow();

        assertEquals(List.of("https://cdn.example.com/1kg-front.jpg",
                        "https://cdn.example.com/1kg-back.jpg"),
                detailVariant.getImages(),
                "The detail screen is the one place a gallery belongs.");

        var otherVariant = detail.getVariants().stream()
                .filter(v -> v.getId().equals(fiveHundredG.getId()))
                .findFirst().orElseThrow();
        assertEquals(List.of(), otherVariant.getImages(),
                "A variant with no photos gets an empty list, not the other variant's.");
    }

    // ------------------------------------------------------------ fixtures

    private ProductVariant newVariant(Double quantity, String unit, String imageUrl) {
        ProductVariant v = new ProductVariant();
        v.setProduct(product);
        v.setQuantity(quantity);
        v.setUnit(unit);
        v.setImageUrl(imageUrl);
        v.setMrp(new BigDecimal("20.00"));
        v.setSellingPrice(new BigDecimal("19.00"));
        v.setCostPrice(new BigDecimal("14.00"));
        v.setAvailable(true);
        return variantRepository.save(v);
    }
}
