package com.gpstore.catalog;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Removes the test catalogue before launch.
 *
 * THE ORDERED-PRODUCT RULE IS THE WHOLE DESIGN. A test product that somebody
 * placed an order against cannot simply be deleted: order_items reference it,
 * and deleting the product either violates the foreign key or - if someone
 * later "fixes" that with ON DELETE CASCADE - silently destroys order history.
 * An order that cannot be rendered is a worse outcome than a leftover test
 * product, so those are RETAINED and reported by id for a human to decide on.
 *
 * DELETION ORDER is children first: inventory and images reference variants
 * and products, so removing a product before them would fail. Doing it in one
 * transaction means a failure at any step leaves the catalogue exactly as it
 * was rather than half-deleted.
 */
@Service
public class CatalogCleanupService {

    private static final Logger log = LoggerFactory.getLogger(CatalogCleanupService.class);

    private final EntityManager entityManager;

    public CatalogCleanupService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public record CleanupResult(int productsDeleted, int variantsDeleted, int imagesDeleted,
                                int inventoryDeleted, int retainedBecauseOrdered,
                                List<Long> retainedProductIds, String note) {}

    @Transactional
    @SuppressWarnings("unchecked")
    public CleanupResult deleteTestProducts() {
        // Products that have been ordered - kept, whatever else happens.
        List<Long> ordered = entityManager.createNativeQuery("""
                SELECT DISTINCT p.id
                FROM products p
                JOIN product_variants v ON v.product_id = p.id
                JOIN order_items oi     ON oi.product_variant_id = v.id
                WHERE p.is_test_data = TRUE
                """).getResultList().stream()
                .map(o -> ((Number) o).longValue())
                .toList();

        String exclusion = ordered.isEmpty() ? "" : " AND p.id NOT IN (:kept)";

        int images = execute("""
                DELETE FROM product_images
                WHERE product_id IN (
                    SELECT p.id FROM products p WHERE p.is_test_data = TRUE
                """ + exclusion + ")", ordered);

        int inventory = execute("""
                DELETE FROM inventory
                WHERE product_variant_id IN (
                    SELECT v.id FROM product_variants v
                    JOIN products p ON p.id = v.product_id
                    WHERE p.is_test_data = TRUE
                """ + exclusion + ")", ordered);

        int variants = execute("""
                DELETE FROM product_variants
                WHERE product_id IN (
                    SELECT p.id FROM products p WHERE p.is_test_data = TRUE
                """ + exclusion + ")", ordered);

        int products = execute("""
                DELETE FROM products p
                WHERE p.is_test_data = TRUE
                """ + exclusion, ordered);

        log.warn("Catalog cleanup removed {} test products ({} retained because ordered)",
                products, ordered.size());

        return new CleanupResult(products, variants, images, inventory,
                ordered.size(), ordered,
                ordered.isEmpty()
                        ? "All test products removed. Categories were left in place - they are "
                          + "shared with real products and are not test data."
                        : "Products listed in retainedProductIds were kept because orders "
                          + "reference them. Deleting them would break order history. Cancel or "
                          + "archive those orders first if they must go.");
    }

    private int execute(String sql, List<Long> kept) {
        var query = entityManager.createNativeQuery(sql);
        if (!kept.isEmpty()) {
            query.setParameter("kept", kept);
        }
        return query.executeUpdate();
    }
}
