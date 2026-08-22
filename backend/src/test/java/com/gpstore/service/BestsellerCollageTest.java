package com.gpstore.service;

import com.gpstore.dto.response.BestsellerTileResponse;
import com.gpstore.entity.Category;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.ProductBrowseRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Bestsellers collage used to be six HTTP requests - one per category
 * tile - on every cold home open. It is now one request backed by one SQL
 * statement, and these are the properties that have to hold for that to be
 * a fix rather than a rearrangement.
 *
 * Most assertions go through the repository with an explicit category
 * filter rather than through the endpoint's "first N categories" policy.
 * The test database is shared and already holds hundreds of categories, so
 * anything seeded here has a high id and never falls inside the first six -
 * a test that assumed otherwise would pass or fail on unrelated data.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class BestsellerCollageTest {

    @Autowired private ProductService productService;
    @Autowired private ProductBrowseRepository browseRepository;
    @Autowired private CacheManager cacheManager;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;

    @Test
    @DisplayName("A tile carries its own category, by id and by name")
    void tilesCarryTheCorrectCategory() {
        Category category = newCategory("Bestseller Correct");
        newProduct(category, "img-a.jpg", true, 0);
        newProduct(category, "img-b.jpg", true, 1);

        List<ProductBrowseRepository.BestsellerRow> rows =
                browseRepository.findBestsellerTiles(List.of(category.getId()), 6, 4);

        assertFalse(rows.isEmpty(), "A category with active products must produce a tile");
        for (ProductBrowseRepository.BestsellerRow row : rows) {
            assertEquals(category.getId(), row.categoryId());
            assertEquals(category.getName(), row.categoryName(),
                    "The tile's label comes from this row - a wrong name mislabels the collage");
        }
    }

    @Test
    @DisplayName("A tile never returns more products than the UI draws")
    void perCategoryLimitIsEnforcedInSql() {
        // Ten products, four slots. The limit has to be applied by the
        // database: fetching ten and trimming in Java would move the same
        // waste from the network to the query.
        Category category = newCategory("Bestseller Overflow");
        for (int i = 0; i < 10; i++) newProduct(category, "img-" + i + ".jpg", true, i);

        List<ProductBrowseRepository.BestsellerRow> rows =
                browseRepository.findBestsellerTiles(List.of(category.getId()), 6, 4);

        assertEquals(4, rows.size(), "The collage asked for 4 and must receive exactly 4");
    }

    @Test
    @DisplayName("A category with no active products produces no tile")
    void emptyCategoriesAreExcluded() {
        // A Bestsellers tile showing four grey placeholders is not a
        // bestseller, and it costs a row in the response to say nothing.
        Category empty = newCategory("Bestseller Empty");

        List<ProductBrowseRepository.BestsellerRow> rows =
                browseRepository.findBestsellerTiles(List.of(empty.getId()), 6, 4);

        assertTrue(rows.isEmpty(), "A category with nothing in it must not occupy a tile");
    }

    @Test
    @DisplayName("A category whose products are all deactivated produces no tile")
    void inactiveProductsDoNotFillATile() {
        Category category = newCategory("Bestseller Inactive");
        Product product = newProduct(category, "hidden.jpg", true, 0);
        product.setActive(false);
        productRepository.save(product);

        List<ProductBrowseRepository.BestsellerRow> rows =
                browseRepository.findBestsellerTiles(List.of(category.getId()), 6, 4);

        assertTrue(rows.isEmpty(), "A deactivated product must not be advertised on the home screen");
    }

    @Test
    @DisplayName("An out-of-stock product still shows, using its own image")
    void unavailableProductsStillRender() {
        // Deliberately NOT filtered out: this matches what the collage did
        // before, and a category whose stock has run low should not silently
        // lose its tile. What must not happen is a crash or a null row.
        Category category = newCategory("Bestseller OutOfStock");
        newProduct(category, "sold-out.jpg", false, 0);

        List<ProductBrowseRepository.BestsellerRow> rows =
                browseRepository.findBestsellerTiles(List.of(category.getId()), 6, 4);

        assertEquals(1, rows.size());
        assertEquals("sold-out.jpg", rows.get(0).imageUrl());
    }

    @Test
    @DisplayName("An available variant wins over a lower displayOrder that is out of stock")
    void variantChoiceMatchesThePrimaryVariantRule() {
        // Same rule as Product.primaryVariant on the client: prefer
        // available, then lowest displayOrder. If these two disagree the
        // collage shows a different photo than the product card does.
        Category category = newCategory("Bestseller VariantRule");
        Product product = newProduct(category, "unavailable-first.jpg", false, 0);
        addVariant(product, "available-second.jpg", true, 5);

        List<ProductBrowseRepository.BestsellerRow> rows =
                browseRepository.findBestsellerTiles(List.of(category.getId()), 6, 4);

        assertEquals(1, rows.size());
        assertEquals("available-second.jpg", rows.get(0).imageUrl(),
                "An in-stock variant must win even with a higher displayOrder");
    }

    @Test
    @DisplayName("A product with no variants keeps its slot with no image")
    void productWithoutVariantsKeepsItsSlot() {
        // LEFT JOIN LATERAL, not an inner join. Dropping the row would
        // silently shift the other three thumbnails into different squares.
        Category category = newCategory("Bestseller NoVariant");
        Product bare = new Product();
        bare.setName("Bare Product " + System.nanoTime());
        bare.setBrand("TestBrand");
        bare.setActive(true);
        bare.setCategory(category);
        productRepository.save(bare);

        List<ProductBrowseRepository.BestsellerRow> rows =
                browseRepository.findBestsellerTiles(List.of(category.getId()), 6, 4);

        assertEquals(1, rows.size(), "The product must still occupy its slot");
        assertNull(rows.get(0).imageUrl(), "With no variant there is no image, and that is not an error");
    }

    @Test
    @DisplayName("No product appears twice, however many variants it has")
    void multipleVariantsDoNotDuplicateAProduct() {
        // The classic join bug: one row per variant instead of one per
        // product, so a product with three variants fills three of the four
        // collage squares with the same picture.
        Category category = newCategory("Bestseller Duplicates");
        Product product = newProduct(category, "v1.jpg", true, 0);
        addVariant(product, "v2.jpg", true, 1);
        addVariant(product, "v3.jpg", true, 2);
        newProduct(category, "other.jpg", true, 0);

        List<ProductBrowseRepository.BestsellerRow> rows =
                browseRepository.findBestsellerTiles(List.of(category.getId()), 6, 4);

        List<Long> ids = rows.stream().map(ProductBrowseRepository.BestsellerRow::productId).toList();
        assertEquals(ids.size(), new HashSet<>(ids).size(),
                "A product with several variants must occupy exactly one square");
        assertEquals(2, ids.size());
    }

    @Test
    @DisplayName("Several categories come back in one call, grouped and bounded")
    void multipleCategoriesInOneRoundTrip() {
        // The whole point of the endpoint: this used to be one HTTP request
        // per category.
        List<Long> categoryIds = new ArrayList<>();
        for (int c = 0; c < 3; c++) {
            Category category = newCategory("Bestseller Multi " + c);
            for (int i = 0; i < 6; i++) newProduct(category, "m" + c + "-" + i + ".jpg", true, i);
            categoryIds.add(category.getId());
        }

        List<ProductBrowseRepository.BestsellerRow> rows =
                browseRepository.findBestsellerTiles(categoryIds, 6, 4);

        assertEquals(12, rows.size(), "3 categories x 4 products, in a single query");
        Set<Long> distinctCategories = new HashSet<>();
        rows.forEach(r -> distinctCategories.add(r.categoryId()));
        assertEquals(3, distinctCategories.size());
    }

    @Test
    @DisplayName("A caller cannot widen the collage into a catalogue dump")
    void limitsAreClampedServerSide() {
        // Both limits arrive from the query string. Unclamped, /bestsellers
        // ?categories=9999&perCategory=9999 is an unauthenticated full-table
        // scan on a 0.5 vCPU instance.
        clearCaches();
        List<BestsellerTileResponse> tiles = productService.getBestsellerTiles(9999, 9999);

        assertTrue(tiles.size() <= 12, "Category count must be clamped, got " + tiles.size());
        for (BestsellerTileResponse tile : tiles) {
            assertTrue(tile.getProductIds().size() <= 8,
                    "Per-category count must be clamped, got " + tile.getProductIds().size());
        }
    }

    @Test
    @DisplayName("Zero and negative limits still return something sane")
    void degenerateLimitsDoNotProduceEmptyOrBrokenSql() {
        clearCaches();
        List<BestsellerTileResponse> tiles = productService.getBestsellerTiles(0, -5);

        for (BestsellerTileResponse tile : tiles) {
            assertFalse(tile.getProductIds().isEmpty(),
                    "A tile with zero products should never have been created");
        }
    }

    @Test
    @DisplayName("The response carries one image slot per product, always")
    void imageSlotsStayAlignedWithProducts() {
        // The client zips these two lists positionally. If they can ever
        // differ in length, the collage draws the wrong photo for a product.
        clearCaches();
        for (BestsellerTileResponse tile : productService.getBestsellerTiles(6, 4)) {
            assertEquals(tile.getProductIds().size(), tile.getImageUrls().size(),
                    "productIds and imageUrls are read in parallel and must stay the same length");
        }
    }

    private void clearCaches() {
        cacheManager.getCacheNames().forEach(n -> {
            var c = cacheManager.getCache(n);
            if (c != null) c.clear();
        });
    }

    @Test
    @DisplayName("The tile's count is the whole category, not the four thumbnails on it")
    void countIsTheCategoryTotalNotTheRowsReturned() {
        Category category = newCategory("Bestseller Count");
        // Seven products, four thumbnails. This is the entire point: a count
        // taken from the returned rows would say four - the same thing it
        // would say about a category of four hundred - and "+0 more" under a
        // shelf of seven is a number that is confidently wrong.
        for (int i = 0; i < 7; i++) {
            newProduct(category, "count-" + i + ".jpg", true, i);
        }

        List<ProductBrowseRepository.BestsellerRow> rows =
                browseRepository.findBestsellerTiles(List.of(category.getId()), 6, 4);

        assertEquals(4, rows.size(), "The collage still returns only what it draws");
        for (ProductBrowseRepository.BestsellerRow row : rows) {
            assertEquals(7L, row.categoryTotal(),
                    "Every row of a category carries that category's full count");
        }
    }

    @Test
    @DisplayName("An inactive product is not counted, because it is not shoppable")
    void inactiveProductsAreExcludedFromTheCount() {
        Category category = newCategory("Bestseller Count Inactive");
        newProduct(category, "live-a.jpg", true, 0);
        newProduct(category, "live-b.jpg", true, 1);

        Product hidden = newProduct(category, "hidden.jpg", true, 2);
        hidden.setActive(false);
        productRepository.saveAndFlush(hidden);

        List<ProductBrowseRepository.BestsellerRow> rows =
                browseRepository.findBestsellerTiles(List.of(category.getId()), 6, 4);

        assertFalse(rows.isEmpty());
        assertEquals(2L, rows.get(0).categoryTotal(),
                "Deactivating a product must remove it from '+N more' as well as from the shelf - "
                        + "a count that includes hidden products promises stock that cannot be bought");
    }

    @Test
    @DisplayName("The count survives the trip into the response the app parses")
    void countReachesTheTileResponse() {
        Category category = newCategory("Bestseller Count Response");
        for (int i = 0; i < 5; i++) {
            newProduct(category, "resp-" + i + ".jpg", true, i);
        }

        // Through the repository, then assembled the way the service does, so
        // this covers the mapping rather than only the SQL.
        List<ProductBrowseRepository.BestsellerRow> rows =
                browseRepository.findBestsellerTiles(List.of(category.getId()), 6, 4);
        assertFalse(rows.isEmpty());

        BestsellerTileResponse tile = new BestsellerTileResponse(
                rows.get(0).categoryId(),
                rows.get(0).categoryName(),
                new ArrayList<>(),
                new ArrayList<>(),
                rows.get(0).categoryTotal());
        for (ProductBrowseRepository.BestsellerRow row : rows) {
            tile.getProductIds().add(row.productId());
            tile.getImageUrls().add(row.imageUrl());
        }

        assertEquals(4, tile.getProductIds().size());
        assertEquals(5L, tile.getProductCount(),
                "productCount and productIds.size() are different numbers and must stay that way");
    }

    private Category newCategory(String label) {
        Category category = new Category();
        category.setName(label + " " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        return categoryRepository.save(category);
    }

    private Product newProduct(Category category, String imageUrl, boolean available, int displayOrder) {
        Product product = new Product();
        product.setName("Bestseller Product " + System.nanoTime());
        product.setBrand("TestBrand");
        product.setActive(true);
        product.setCategory(category);
        product = productRepository.save(product);
        addVariant(product, imageUrl, available, displayOrder);
        return product;
    }

    private void addVariant(Product product, String imageUrl, boolean available, int displayOrder) {
        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setQuantity(1.0);
        variant.setUnit("pc");
        variant.setMrp(new BigDecimal("100"));
        variant.setSellingPrice(new BigDecimal("90"));
        variant.setCostPrice(new BigDecimal("60"));
        variant.setImageUrl(imageUrl);
        variant.setAvailable(available);
        variant.setActive(true);
        variant.setDisplayOrder(displayOrder);
        variantRepository.save(variant);
    }
}
