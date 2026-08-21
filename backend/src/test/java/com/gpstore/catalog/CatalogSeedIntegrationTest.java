package com.gpstore.catalog;

import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The catalog seeder, run for real against a real database.
 *
 * THE SECOND RUN IS THE POINT. The brief's section 16 asks for a seeder that
 * is safe to run repeatedly, and the only way to show that is to run it
 * twice and count. Asserting it from the code's shape would prove nothing -
 * this exact class of bug (a "check then insert" that races, or a lookup key
 * that is not actually unique) always looks correct in the source.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class CatalogSeedIntegrationTest {

    @Autowired private CatalogSeedService seedService;
    @Autowired private CatalogAuditService auditService;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private jakarta.persistence.EntityManager entityManager;

    @Test
    @DisplayName("seeding twice produces one catalogue, not two")
    void seedingIsIdempotent() {
        CatalogSeedService.SeedResult first = seedService.seed();

        assertTrue(first.total() > 900,
                "the generated catalogue should carry roughly a thousand products, got " + first.total());
        assertEquals(List.of(), first.problems(), "first seed reported problems");

        long productsAfterFirst = productRepository.count();
        long variantsAfterFirst = variantRepository.count();

        CatalogSeedService.SeedResult second = seedService.seed();

        assertEquals(0, second.inserted(),
                "a second run must insert nothing - every SKU already exists");
        assertEquals(first.total(), second.updated(),
                "a second run should update every record it finds");
        assertEquals(productsAfterFirst, productRepository.count(),
                "product count changed on the second run");
        assertEquals(variantsAfterFirst, variantRepository.count(),
                "variant count changed on the second run");
    }

    @Test
    @DisplayName("every seeded product is complete, priced sanely and marked as test data")
    void seededDataIsSaneAndHonestlyLabelled() {
        seedService.seed();

        List<ProductVariant> seeded = variantRepository.findSeededVariants();
        assertFalse(seeded.isEmpty(), "no seeded variants found");

        for (ProductVariant variant : seeded) {
            Product product = variant.getProduct();
            String where = "SKU " + variant.getSku();

            assertNotNull(product.getName(), where + " has no name");
            assertNotNull(product.getBrand(), where + " has no brand");
            assertNotNull(product.getCategory(), where + " has no category");
            assertNotNull(product.getSubcategory(), where + " has no subcategory");
            assertNotNull(variant.getQuantity(), where + " has no pack size");
            assertNotNull(variant.getUnit(), where + " has no unit");

            // The two claims that must never drift, because everything in
            // this catalogue is assumed and the pre-launch audit keys on them.
            assertTrue(Boolean.TRUE.equals(product.getIsTestData()),
                    where + " is not flagged as test data");
            assertFalse(Boolean.TRUE.equals(product.getPriceVerified()),
                    where + " claims a verified price, which nothing here has");

            assertTrue(variant.getSellingPrice().compareTo(BigDecimal.ZERO) > 0,
                    where + " is not priced");
            assertTrue(variant.getSellingPrice().compareTo(variant.getMrp()) <= 0,
                    where + " sells above its MRP");
        }
    }

    @Test
    @DisplayName("brands are normalised, so Shop by Brand shows one tile per brand")
    void brandsDoNotDifferOnlyByCase() {
        seedService.seed();

        Map<String, List<String>> byLowered = productRepository.findAll().stream()
                .map(Product::getBrand)
                .filter(b -> b != null && !b.isBlank())
                .distinct()
                .collect(Collectors.groupingBy(String::toLowerCase,
                        Collectors.mapping(Function.identity(), Collectors.toList())));

        List<List<String>> collisions = byLowered.values().stream()
                .filter(spellings -> spellings.size() > 1)
                .toList();

        assertEquals(List.of(), collisions,
                "these brands differ only by case or spacing and would render as separate tiles");
    }

    @Test
    @DisplayName("the audit finds no problems in a freshly seeded catalogue")
    void auditIsClean() {
        seedService.seed();

        CatalogAuditService.CatalogAudit audit = auditService.audit();

        // Asserted problem-by-problem rather than as an empty list, because
        // the audit deliberately counts the WHOLE products table - it is a
        // production tool, and a product with five images is worth flagging
        // whoever created it. This suite shares one database, so other tests'
        // fixtures show up in that total: ProductGalleryTest creates a
        // product with more than four images precisely to exercise the cap.
        //
        // Asserting an empty list made this test pass on a fresh database and
        // fail on a shared one, which is not a property worth having. These
        // are the problems the SEEDER could actually cause.
        for (String problem : audit.problems()) {
            assertFalse(problem.contains("duplicate SKU"), problem);
            assertFalse(problem.contains("priced above MRP"), problem);
            assertFalse(problem.contains("negative price"), problem);
            assertFalse(problem.contains("without a name"), problem);
            assertFalse(problem.contains("without a brand"), problem);
            assertFalse(problem.contains("not attached to a category"), problem);
            assertFalse(problem.contains("differ only by case"), problem);
            assertFalse(problem.contains("duplicated image URL"), problem);
        }
        assertTrue(audit.testProducts() > 900, "expected ~1000 test products");
        // Every test product must be price-unverified. Stated as ">=" rather
        // than "==" because pre-existing real products are unverified too -
        // the flag defaults false - so the unverified total is a superset.
        assertTrue(audit.priceUnverified() >= audit.testProducts(),
                "every test product must count as price-unverified");
    }

    /**
     * Images are expected to be ZERO here, and that is not a gap in the test.
     *
     * The seeder deliberately writes no image at all rather than inventing a
     * URL - see CatalogImageBackfillService on why a fabricated URL is worse
     * than an empty field. This asserts that deliberate emptiness, so that if
     * someone later "helpfully" makes the seeder synthesise image paths, this
     * test is what stops it.
     */
    @Test
    @DisplayName("the seeder invents no image URLs")
    void seederWritesNoImages() {
        seedService.seed();

        // Scoped to seeded products, NOT the whole table. The audit's
        // histogram covers everything, including products other tests in this
        // shared database created with images of their own - which is right
        // for the audit and wrong for this assertion.
        Number images = (Number) entityManager.createNativeQuery("""
                SELECT COUNT(*) FROM product_images pi
                JOIN products p ON p.id = pi.product_id
                WHERE p.is_test_data = TRUE
                """).getSingleResult();

        assertEquals(0L, images.longValue(),
                "the seeder must not create images - only the verified backfill may");
    }

    @Test
    @DisplayName("pack size is stripped correctly when searching an external catalogue")
    void packSizeIsStrippedFromSearchTerms() {
        assertEquals("Tata Salt Iodised",
                CatalogImageBackfillService.stripPackSize("Tata Salt Iodised 1 kg"));
        assertEquals("Fortune Sunlite Refined Sunflower Oil",
                CatalogImageBackfillService.stripPackSize("Fortune Sunlite Refined Sunflower Oil 1 l"));
        assertEquals("Maggi 2-Minute Masala Noodles",
                CatalogImageBackfillService.stripPackSize("Maggi 2-Minute Masala Noodles 70 g"));
    }
}
