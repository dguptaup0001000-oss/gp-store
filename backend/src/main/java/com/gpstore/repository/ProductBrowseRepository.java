package com.gpstore.repository;

import com.gpstore.entity.Product;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Backs "Shop by Brand" and filtered category browsing - sorting, filtering
 * (in-stock, keyword), and pagination all pushed into one SQL query instead
 * of loading every active product for a brand/category into Java to sort
 * there (the previous approach, which also meant Best Selling/Highest Rated
 * pulled the ENTIRE order_items/reviews tables into a HashMap on every
 * request just to look up two numbers per product).
 *
 * Plain EntityManager native queries rather than a derived/JPQL query
 * because the ORDER BY column depends on which of 8 sort options was
 * requested, and price/discount/best-selling/highest-rated all need
 * aggregates from other tables (product_variants, order_items, reviews)
 * that a single static query can't express for every sort option at once.
 * The ORDER BY fragment and WHERE conditions are built from a fixed
 * whitelist keyed off the sort/filter enum values - never from raw request
 * strings - so this stays injection-safe despite being string-built SQL.
 */
@Repository
public class ProductBrowseRepository {

    @PersistenceContext
    private EntityManager entityManager;

    public record BrowseResult(List<Product> products, long totalElements) {}

    /** Exactly one of brand/categoryId is non-null - the two callers (brand vs. category browsing). */
    public BrowseResult browse(
            String brand,
            Long categoryId,
            String sort,
            boolean inStockOnly,
            String keyword,
            int page,
            int size) {

        List<String> conditions = new ArrayList<>();
        conditions.add("p.active = true");
        conditions.add("COALESCE(v.sellable, false) = true");
        conditions.add(brand != null ? "LOWER(p.brand) = LOWER(:brand)" : "p.category_id = :categoryId");

        boolean hasKeyword = keyword != null && !keyword.isBlank();
        if (hasKeyword) {
            conditions.add("p.name ILIKE CONCAT('%', :keyword, '%')");
        }
        if (inStockOnly) {
            conditions.add("COALESCE(v.in_stock, false) = true");
        }

        // NOTE: this text block deliberately ends right after the last JOIN,
        // with no trailing WHERE - Java text blocks strip trailing
        // whitespace from every line, so a "WHERE " written at the end of
        // the block here previously lost its space at runtime and produced
        // "...p.id WHEREp.active = true" (Postgres syntax error 42601).
        // The WHERE clause is built and joined separately below instead,
        // as a plain string literal (not a text block), where trailing
        // spaces are preserved exactly as written.
        String fromAndJoins = """
                FROM products p
                LEFT JOIN (
                    SELECT product_id,
                           MIN(CASE WHEN available = true AND selling_price IS NOT NULL
                                         AND selling_price > 0 THEN selling_price END) AS min_price,
                           MAX(CASE WHEN mrp IS NOT NULL AND mrp > 0 AND mrp > selling_price
                                    THEN (mrp - selling_price) / mrp ELSE 0 END) AS max_discount,
                           BOOL_OR(available = true AND selling_price IS NOT NULL
                                    AND selling_price > 0) AS in_stock,
                           BOOL_OR(available = true AND selling_price IS NOT NULL
                                    AND selling_price > 0
                                    AND (active IS NULL OR active = true)) AS sellable
                    FROM product_variants
                    GROUP BY product_id
                ) v ON v.product_id = p.id
                LEFT JOIN (
                    SELECT pv.product_id, SUM(oi.quantity) AS units_sold
                    FROM order_items oi
                    JOIN product_variants pv ON pv.id = oi.product_variant_id
                    GROUP BY pv.product_id
                ) bs ON bs.product_id = p.id
                LEFT JOIN (
                    SELECT product_id, AVG(rating) AS avg_rating
                    FROM reviews
                    WHERE active = true
                    GROUP BY product_id
                ) rt ON rt.product_id = p.id
                """ + "WHERE " + String.join(" AND ", conditions);

        String orderBy = switch (sort == null ? "" : sort.toUpperCase()) {
            case "PRICE_LOW_HIGH" -> "COALESCE(v.min_price, 0) ASC";
            case "PRICE_HIGH_LOW" -> "COALESCE(v.min_price, 0) DESC";
            case "NAME_ASC" -> "LOWER(p.name) ASC";
            case "NAME_DESC" -> "LOWER(p.name) DESC";
            case "NEWEST" -> "p.created_at DESC";
            case "DISCOUNT" -> "COALESCE(v.max_discount, 0) DESC";
            case "BEST_SELLING" -> "COALESCE(bs.units_sold, 0) DESC";
            case "HIGHEST_RATED" -> "COALESCE(rt.avg_rating, 0) DESC";
            default -> "p.id ASC";
        };

        Query dataQuery = entityManager.createNativeQuery(
                "SELECT p.* " + fromAndJoins + " ORDER BY " + orderBy + " LIMIT :limit OFFSET :offset",
                Product.class);
        Query countQuery = entityManager.createNativeQuery("SELECT COUNT(*) " + fromAndJoins);

        for (Query q : List.of(dataQuery, countQuery)) {
            if (brand != null) {
                q.setParameter("brand", brand);
            } else {
                q.setParameter("categoryId", categoryId);
            }
            if (hasKeyword) {
                q.setParameter("keyword", keyword.trim());
            }
        }
        dataQuery.setParameter("limit", size);
        dataQuery.setParameter("offset", (long) page * size);

        @SuppressWarnings("unchecked")
        List<Product> products = dataQuery.getResultList();
        long totalElements = ((Number) countQuery.getSingleResult()).longValue();

        return new BrowseResult(products, totalElements);
    }

    /**
     * One row per product that belongs in the Bestsellers collage:
     * (categoryId, categoryName, productId, imageUrl).
     *
     * WHY THIS EXISTS. The collage is six category tiles, each showing four
     * thumbnails, and the app was fetching it as SIX separate HTTP requests -
     * one per category - on every cold home open. Six round trips, six auth
     * filter chains, six connection acquisitions and six result sets, to
     * render twenty-four small images. On a 0.5 vCPU instance measured at
     * 131ms p95 under 750 concurrent browsers, that is the single largest
     * avoidable multiplier on the home screen.
     *
     * ONE QUERY, AND IT STAYS ONE QUERY however many categories the collage
     * grows to. ROW_NUMBER partitions by category so the per-category limit
     * is applied inside the database rather than by fetching everything and
     * trimming in Java, and DENSE_RANK caps how many categories come back -
     * so the result set is bounded at categoryLimit x perCategory rows (24
     * today) no matter how large the catalogue gets.
     *
     * A PROJECTION, NOT ENTITIES. The tile renders four image URLs and a
     * category name and nothing else - no price, no description, no ratings,
     * no variant list. Returning Product entities would hydrate all of that,
     * pay Hibernate's mapping cost for it, and serialize it over mobile data
     * to be thrown away. Selecting the four columns the UI actually uses is
     * what keeps this response small rather than merely fewer.
     *
     * THE LATERAL JOIN picks each product's display variant using the same
     * rule as Product.primaryVariant on the client: prefer an available
     * variant, then the lowest displayOrder, with id as the tie-break so the
     * choice is deterministic rather than whatever the planner returns. It
     * is a LEFT JOIN LATERAL, so a product with no variants at all still
     * appears - with a null image, which the tile already renders as a
     * placeholder icon.
     *
     * Categories with no active products are excluded by the inner join: a
     * Bestsellers tile showing four grey placeholders is not a bestseller.
     */
    /**
     * categoryTotal is how many active products the category holds ALTOGETHER,
     * not how many came back in this row set - it is what the tile's "+N more"
     * counts. Repeated identically on every row of a category, because a
     * window function is the cheapest place to get it: the CTE has already
     * scanned those rows to rank them, so COUNT(*) OVER the same partition
     * costs no extra scan and no second query. Counting in Java would instead
     * count the four rows that survived the rank filter and cheerfully report
     * "+0 more" for a category of two hundred.
     */
    public record BestsellerRow(Long categoryId, String categoryName, Long productId,
                                String imageUrl, long categoryTotal) {}

    /**
     * [categoryIds] narrows which categories are considered; null or empty
     * means "every active category", which is what the endpoint uses. The
     * filter is not there for the endpoint - it is there because the service
     * owns the "first N categories" policy while this owns the SQL, and a
     * caller that already knows which categories it wants should not have to
     * go through that policy to ask for them. It is also the only way to
     * assert anything about specific seeded data against a shared test
     * database, where freshly created categories never fall inside the first
     * N by id.
     */
    public List<BestsellerRow> findBestsellerTiles(
            Collection<Long> categoryIds, int categoryLimit, int perCategory) {

        boolean filterByIds = categoryIds != null && !categoryIds.isEmpty();
        String categoryFilter = filterByIds ? " AND c.id IN (:categoryIds)" : "";

        Query query = entityManager.createNativeQuery("""
                WITH ranked AS (
                    SELECT c.id                                                      AS category_id,
                           c.name                                                    AS category_name,
                           p.id                                                      AS product_id,
                           pv.image_url                                              AS image_url,
                           ROW_NUMBER() OVER (PARTITION BY c.id ORDER BY p.id)       AS product_rank,
                           COUNT(*)     OVER (PARTITION BY c.id)                     AS category_total,
                           DENSE_RANK() OVER (ORDER BY c.id)                         AS category_rank
                    FROM categories c
                    JOIN products p
                      ON p.category_id = c.id
                     AND p.active = true
                    LEFT JOIN LATERAL (
                        SELECT v.image_url
                        FROM product_variants v
                        WHERE v.product_id = p.id
                        ORDER BY COALESCE(v.available, false) DESC,
                                 COALESCE(v.display_order, 2147483647) ASC,
                                 v.id ASC
                        LIMIT 1
                    ) pv ON true
                    WHERE c.active = true""" + categoryFilter + """
                )
                SELECT category_id, category_name, product_id, image_url, category_total
                FROM ranked
                WHERE product_rank <= :perCategory
                  AND category_rank <= :categoryLimit
                ORDER BY category_id, product_rank
                """);
        query.setParameter("perCategory", perCategory);
        query.setParameter("categoryLimit", categoryLimit);
        if (filterByIds) {
            query.setParameter("categoryIds", categoryIds);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        List<BestsellerRow> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new BestsellerRow(
                    ((Number) row[0]).longValue(),
                    (String) row[1],
                    ((Number) row[2]).longValue(),
                    (String) row[3],
                    ((Number) row[4]).longValue()));
        }
        return result;
    }
}
