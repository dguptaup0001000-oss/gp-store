package com.gpstore.catalog;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Counts the catalogue's health straight from the database.
 *
 * NATIVE SQL rather than loading entities: every figure here is an aggregate
 * over the whole products table, and fetching a thousand objects into memory
 * on a 512 MB instance to count them would be the wrong shape entirely.
 *
 * This is what answers the brief's final-report questions, and it answers
 * them from the live database rather than from what the seeder believes it
 * wrote - which is the only way the answer stays true a month from now.
 */
@Service
public class CatalogAuditService {

    private final EntityManager entityManager;

    public CatalogAuditService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public record CatalogAudit(
            long totalProducts,
            long testProducts,
            long realProducts,
            long withOneImage,
            long withTwoImages,
            long withThreeImages,
            long withFourImages,
            long withoutImages,
            long withThumbnail,
            long priceVerified,
            long priceUnverified,
            long totalBrands,
            long totalCategories,
            long totalSubcategories,
            long bestsellers,
            long featured,
            long outOfStock,
            List<String> problems) {}

    @Transactional(readOnly = true)
    public CatalogAudit audit() {
        List<String> problems = new ArrayList<>();

        long total = count("SELECT COUNT(*) FROM products");
        long test = count("SELECT COUNT(*) FROM products WHERE is_test_data = TRUE");

        // Image histogram in ONE pass. Counting "products with exactly n
        // images" as four separate queries would scan product_images four
        // times for a number nobody needs to be that fresh.
        long[] histogram = new long[5];
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT image_count, COUNT(*)
                FROM (
                    SELECT p.id, COUNT(pi.id) AS image_count
                    FROM products p
                    LEFT JOIN product_images pi ON pi.product_id = p.id
                    GROUP BY p.id
                ) counted
                GROUP BY image_count
                """).getResultList();
        long withoutImages = 0;
        for (Object[] row : rows) {
            int bucket = ((Number) row[0]).intValue();
            long howMany = ((Number) row[1]).longValue();
            if (bucket == 0) {
                withoutImages = howMany;
            } else if (bucket <= 4) {
                histogram[bucket] = howMany;
            } else {
                problems.add(howMany + " product(s) carry more than 4 images, which exceeds the cap");
            }
        }

        long duplicateSkus = count("""
                SELECT COALESCE(SUM(c - 1), 0) FROM (
                    SELECT COUNT(*) AS c FROM product_variants
                    WHERE sku IS NOT NULL GROUP BY sku HAVING COUNT(*) > 1
                ) d
                """);
        if (duplicateSkus > 0) {
            problems.add(duplicateSkus + " duplicate SKU(s) present");
        }

        long badPrices = count("""
                SELECT COUNT(*) FROM product_variants
                WHERE selling_price IS NOT NULL AND mrp IS NOT NULL AND selling_price > mrp
                """);
        if (badPrices > 0) {
            problems.add(badPrices + " variant(s) priced above MRP");
        }

        long negativePrices = count("""
                SELECT COUNT(*) FROM product_variants
                WHERE selling_price < 0 OR mrp < 0
                """);
        if (negativePrices > 0) {
            problems.add(negativePrices + " variant(s) with a negative price");
        }

        long namelessProducts = count(
                "SELECT COUNT(*) FROM products WHERE name IS NULL OR TRIM(name) = ''");
        if (namelessProducts > 0) {
            problems.add(namelessProducts + " product(s) without a name");
        }

        long brandless = count("""
                SELECT COUNT(*) FROM products
                WHERE is_test_data = TRUE AND (brand IS NULL OR TRIM(brand) = '')
                """);
        if (brandless > 0) {
            problems.add(brandless + " test product(s) without a brand");
        }

        long categoryless = count("""
                SELECT COUNT(*) FROM products
                WHERE is_test_data = TRUE AND category_id IS NULL
                """);
        if (categoryless > 0) {
            problems.add(categoryless + " test product(s) not attached to a category");
        }

        long duplicateImages = count("""
                SELECT COALESCE(SUM(c - 1), 0) FROM (
                    SELECT COUNT(*) AS c FROM product_images
                    GROUP BY product_id, image_url HAVING COUNT(*) > 1
                ) d
                """);
        if (duplicateImages > 0) {
            problems.add(duplicateImages + " duplicated image URL(s) within a single product");
        }

        // Brands are a string column, not a table, so "how many brands" is a
        // DISTINCT over that column - and case-insensitive, because that is
        // exactly how a stray "AMUL" would hide from a plain DISTINCT while
        // showing up as its own tile in the app.
        long brands = count("""
                SELECT COUNT(DISTINCT LOWER(TRIM(brand))) FROM products
                WHERE brand IS NOT NULL AND TRIM(brand) <> ''
                """);
        long rawBrands = count("""
                SELECT COUNT(DISTINCT brand) FROM products
                WHERE brand IS NOT NULL AND TRIM(brand) <> ''
                """);
        if (rawBrands > brands) {
            problems.add((rawBrands - brands) + " brand name(s) differ only by case or whitespace");
        }

        return new CatalogAudit(
                total,
                test,
                total - test,
                histogram[1], histogram[2], histogram[3], histogram[4],
                withoutImages,
                count("SELECT COUNT(*) FROM product_variants WHERE image_url IS NOT NULL AND image_url <> ''"),
                count("SELECT COUNT(*) FROM products WHERE price_verified = TRUE"),
                count("SELECT COUNT(*) FROM products WHERE price_verified = FALSE"),
                brands,
                count("SELECT COUNT(*) FROM categories"),
                count("SELECT COUNT(DISTINCT subcategory) FROM products WHERE subcategory IS NOT NULL"),
                count("SELECT COUNT(*) FROM products WHERE bestseller = TRUE"),
                count("SELECT COUNT(*) FROM products WHERE featured = TRUE"),
                count("""
                      SELECT COUNT(*) FROM inventory i
                      WHERE COALESCE(i.stock, 0) - COALESCE(i.reserved_stock, 0) <= 0
                      """),
                problems);
    }

    private long count(String sql) {
        Query query = entityManager.createNativeQuery(sql);
        Object result = query.getSingleResult();
        return result == null ? 0L : ((Number) result).longValue();
    }
}
