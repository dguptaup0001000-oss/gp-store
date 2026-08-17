package com.gpstore.repository;

import com.gpstore.entity.Product;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
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
        conditions.add(brand != null ? "LOWER(p.brand) = LOWER(:brand)" : "p.category_id = :categoryId");

        boolean hasKeyword = keyword != null && !keyword.isBlank();
        if (hasKeyword) {
            conditions.add("p.name ILIKE CONCAT('%', :keyword, '%')");
        }
        if (inStockOnly) {
            conditions.add("COALESCE(v.in_stock, false) = true");
        }

        String fromAndJoins = """
                FROM products p
                LEFT JOIN (
                    SELECT product_id,
                           MIN(selling_price) AS min_price,
                           MAX(CASE WHEN mrp IS NOT NULL AND mrp > 0 AND mrp > selling_price
                                    THEN (mrp - selling_price) / mrp ELSE 0 END) AS max_discount,
                           BOOL_OR(available = true) AS in_stock
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
                WHERE """ + String.join(" AND ", conditions);

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
}
