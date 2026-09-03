package com.gpstore.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpstore.dto.response.ProductResponse;
import com.gpstore.entity.Category;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;
import com.gpstore.service.ProductService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

/**
 * The product image pipeline, end to end, in the one direction that matters:
 * READING.
 *
 * <p>WHY THIS EXISTS. "Product images are not showing" has several possible
 * causes and only one of them is the app - the URL can be missing from the
 * database, dropped by the entity-to-DTO mapping, renamed by JSON
 * serialization, or misparsed by Dart. Three of those four are on this side,
 * and none of them fails loudly: every one of them ends at a product card
 * drawing a placeholder, which looks identical to a product that genuinely
 * has no photograph.
 *
 * <p>THE FIELD NAME ASSERTION IS THE POINT. Flutter's generated
 * ProductVariant.fromJson reads the key "imageUrl". If Jackson were ever
 * configured with a snake_case naming strategy - a one-line change in
 * application.properties, made for some unrelated endpoint - every image in
 * the app would silently disappear while every backend test still passed.
 * That is exactly the class of failure this pins.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class ProductImagePipelineTest {

    /** A URL shape, not a real host - nothing here makes a network request. */
    private static final String IMAGE = "https://res.cloudinary.com/demo/image/upload/v1/gp/atta.jpg";

    @Autowired private ProductService productService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("A stored variant image URL survives the trip to the browse response")
    void imageUrlSurvivesCategoryBrowse() {
        Category category = newCategory();
        newProduct(category, IMAGE);

        List<ProductResponse> page = productService
                .browseByCategory(category.getId(), PageRequest.of(0, 20))
                .getContent();

        assertThat(page).isNotEmpty();
        assertThat(page.get(0).getVariants())
                .as("the browse endpoint must return variants at all - a product card "
                        + "reads its picture from the variant, so an empty list is an "
                        + "invisible image")
                .isNotEmpty();
        assertThat(page.get(0).getVariants().get(0).getImageUrl()).isEqualTo(IMAGE);
    }

    @Test
    @DisplayName("The JSON key is imageUrl, which is the key Flutter parses")
    void jsonFieldIsCamelCase() throws Exception {
        Category category = newCategory();
        newProduct(category, IMAGE);

        ProductResponse product = productService
                .browseByCategory(category.getId(), PageRequest.of(0, 20))
                .getContent()
                .get(0);

        String json = objectMapper.writeValueAsString(product);

        assertThat(json)
                .as("Flutter's generated fromJson reads \"imageUrl\" - any other spelling "
                        + "is an app full of placeholders and a backend suite that stays green")
                .contains("\"imageUrl\"")
                .doesNotContain("\"image_url\"");
        assertThat(json).contains(IMAGE);
    }

    @Test
    @DisplayName("A product with no photograph reports null, not an empty string")
    void missingImageIsNull() {
        Category category = newCategory();
        newProduct(category, null);

        var variant = productService
                .browseByCategory(category.getId(), PageRequest.of(0, 20))
                .getContent()
                .get(0)
                .getVariants()
                .get(0);

        // The distinction matters on the client: Dart checks for null AND for
        // empty, but only because it cannot trust which one arrives. Keeping
        // null meaningful here is what lets "no image" stay distinguishable
        // from "image failed to load" if that ever needs separating.
        assertThat(variant.getImageUrl()).isNull();
    }

    // ------------------------------------------------------- the worklist
    //
    // These are about WHICH products the image backfill can see. The bug they
    // pin cost the whole feature: a live catalogue's products carry
    // isTestData = false, so the original query excluded every one of them
    // and the backfill reported a clean run having examined nothing.

    @Test
    @DisplayName("A live product with no photograph is on the backfill's worklist")
    void liveProductWithoutImageIsFound() {
        Category category = newCategory();
        ProductVariant mine = newProduct(category, null);

        // BY ID, not by dereferencing every row. anySatisfy runs the lambda
        // against each element until one passes, so walking
        // getProduct().getCategory().getId() over a shared worklist throws
        // NullPointerException - not an assertion failure - the moment any
        // other test has an unfinished product in the table. That is a fault
        // in this assertion, not in the code under test: the query is correct
        // and the test still goes red.
        assertThat(variantRepository.findVariantsWithoutRealImages())
                .as("a shop's own products are not test data, and they are exactly "
                        + "the ones that need photographs")
                .extracting(ProductVariant::getId)
                .contains(mine.getId());
    }

    @Test
    @DisplayName("A placeholder URL counts as needing an image, not as having one")
    void placeholderCountsAsMissing() {
        Category category = newCategory();
        // The exact shape found in production: a text-rendering service asked
        // to draw the product's own name on a coloured square. It resolves,
        // returns 200, and is not a photograph of anything.
        ProductVariant mine = newProduct(category, "https://placehold.co/400x400/FFE9C7/8A4B08/png?text=Gemini%0AVanaspati");

        assertThat(variantRepository.findVariantsWithoutRealImages())
                .as("a URL that renders the product's name is a picture of some words - "
                        + "every check that only asks 'is there a URL' is answered yes "
                        + "while the customer sees no product")
                .extracting(ProductVariant::getId)
                .contains(mine.getId());
    }

    @Test
    @DisplayName("A real photograph is NOT on the worklist")
    void realImageIsLeftAlone() {
        Category category = newCategory();
        ProductVariant mine = newProduct(category, IMAGE);

        assertThat(variantRepository.findVariantsWithoutRealImages())
                .as("re-running the backfill must not replace photographs it already found")
                .extracting(ProductVariant::getId)
                .doesNotContain(mine.getId());
    }

    private Category newCategory() {
        Category category = new Category();
        category.setName("Image Pipeline " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        return categoryRepository.save(category);
    }

    /**
     * Returns the variant it created, so a test can name its OWN row.
     *
     * The worklist query returns the whole catalogue's missing photographs,
     * including rows other tests are part-way through writing. Identifying
     * this test's product by variant id rather than by walking every result's
     * category keeps the assertion about the thing the test actually created.
     */
    private ProductVariant newProduct(Category category, String imageUrl) {
        Product product = new Product();
        product.setName("Image Pipeline Product " + System.nanoTime());
        product.setBrand("PipelineBrand");
        product.setActive(true);
        product.setCategory(category);
        Product saved = productRepository.save(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(saved);
        variant.setQuantity(1.0);
        variant.setUnit("kg");
        variant.setMrp(new BigDecimal("100"));
        variant.setSellingPrice(new BigDecimal("90"));
        variant.setCostPrice(new BigDecimal("60"));
        variant.setImageUrl(imageUrl);
        variant.setAvailable(true);
        variant.setActive(true);
        variant.setDisplayOrder(0);
        return variantRepository.save(variant);
    }
}
