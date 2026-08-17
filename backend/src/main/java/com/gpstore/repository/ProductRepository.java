package com.gpstore.repository;

import com.gpstore.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    // Kept for backward compatibility - existing callers still work unchanged.
    List<Product> findByNameContainingIgnoreCase(String keyword);

    List<Product> findByCategoryId(Long categoryId);

    /**
     * Instant search: matches on name OR brand, tolerates typos via pg_trgm
     * similarity (requires the pg_trgm extension - see
     * db/migration/README-search.md), and ranks results by how close the
     * match is instead of returning them in arbitrary/insertion order.
     * ILIKE is included alongside the trigram operator because very short
     * search terms (2-3 letters) can fall under trigram's similarity
     * threshold even for a legitimate prefix match.
     */
    @Query(
        value = "SELECT p.* FROM products p " +
                "WHERE p.active = true " +
                "AND (p.name % :keyword OR p.brand % :keyword " +
                "     OR p.name ILIKE CONCAT('%', :keyword, '%') " +
                "     OR p.brand ILIKE CONCAT('%', :keyword, '%')) " +
                "ORDER BY GREATEST(similarity(p.name, :keyword), similarity(COALESCE(p.brand, ''), :keyword)) DESC",
        countQuery = "SELECT count(*) FROM products p " +
                "WHERE p.active = true " +
                "AND (p.name % :keyword OR p.brand % :keyword " +
                "     OR p.name ILIKE CONCAT('%', :keyword, '%') " +
                "     OR p.brand ILIKE CONCAT('%', :keyword, '%'))",
        nativeQuery = true)
    Page<Product> searchInstant(@Param("keyword") String keyword, Pageable pageable);

    Page<Product> findByCategoryIdAndActiveTrue(Long categoryId, Pageable pageable);

    Page<Product> findByActiveTrueOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Only brands that actually have at least one active product - the
     * GROUP BY naturally guarantees this (a brand with zero products
     * simply never produces a row), no separate filter needed.
     */
    @Query("select p.brand as brand, count(p) as productCount from Product p " +
            "where p.active = true and p.brand is not null and p.brand <> '' " +
            "group by p.brand order by p.brand asc")
    List<Object[]> findBrandsWithProductCounts();
}
