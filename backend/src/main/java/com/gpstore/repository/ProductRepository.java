package com.gpstore.repository;

import com.gpstore.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Batched, eager-fetched lookup for recommendation-style endpoints
     * (RecommendationService) that need to resolve a ranked list of product
     * IDs into full Product objects for JSON serialization - one round trip
     * with category/variants joined in, instead of the previous pattern of
     * calling findById() once per product (an extra query each) and then
     * relying on lazy-loading to fetch each product's category and variants
     * individually during serialization (another 1-2 queries per product).
     * For a 10-50 item recommendation list, that was 20-150+ sequential
     * round trips instead of 1.
     */
    @EntityGraph(attributePaths = {"category", "variants"})
    List<Product> findByIdIn(Collection<Long> ids);

    // Kept for backward compatibility - existing callers still work unchanged.
    List<Product> findByNameContainingIgnoreCase(String keyword);

    // Same query, capped - see ProductService.search()'s doc comment for why
    // the uncapped version above is dangerous as a public, unauthenticated
    // endpoint and must never be called directly from a controller again.
    List<Product> findByNameContainingIgnoreCase(String keyword, Pageable pageable);

    List<Product> findByCategoryId(Long categoryId);

    boolean existsByCategoryId(Long categoryId);

    // Instant search lives on ProductBrowseRepository.searchInstant: a
    // Spring Data native Page<Product> wrapping SELECT p.* plus
    // ORDER BY similarity() returned HTTP 500 in production while the JPQL
    // /search path on the same table returned 200.

    // Eager-fetch category only (not variants, unlike findByIdIn above) -
    // ProductService maps every result through ProductResponse.from(), which
    // needs both, and without this each page of results was N+1: one extra
    // lazy-load query per product just for its category. variants is
    // deliberately left lazy here specifically because it's a @OneToMany
    // collection combined with Pageable (LIMIT/OFFSET) - eager-fetching a
    // collection alongside DB-level pagination is a well-known Hibernate
    // trap (the JOIN multiplies rows before the LIMIT applies), so this
    // takes the safe half of the fix rather than risk paginated results
    // silently coming back wrong.
    @EntityGraph(attributePaths = {"category"})
    Page<Product> findByCategoryIdAndActiveTrue(Long categoryId, Pageable pageable);

    @EntityGraph(attributePaths = {"category"})
    @Query("""
            SELECT p FROM Product p
            WHERE p.active = true
              AND EXISTS (
                  SELECT 1 FROM ProductVariant v
                  WHERE v.product = p
                    AND v.available = true
                    AND (v.active = true OR v.active IS NULL)
                    AND v.sellingPrice IS NOT NULL
                    AND v.sellingPrice > 0
              )
            """)
    Page<Product> findSellable(Pageable pageable);

    @EntityGraph(attributePaths = {"category"})
    @Query("""
            SELECT p FROM Product p
            WHERE p.category.id = :categoryId
              AND p.active = true
              AND EXISTS (
                  SELECT 1 FROM ProductVariant v
                  WHERE v.product = p
                    AND v.available = true
                    AND (v.active = true OR v.active IS NULL)
                    AND v.sellingPrice IS NOT NULL
                    AND v.sellingPrice > 0
              )
            """)
    Page<Product> findSellableByCategoryId(@Param("categoryId") Long categoryId, Pageable pageable);

    @EntityGraph(attributePaths = {"category"})
    Page<Product> findByActiveTrueOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Active products with the sort supplied by the CALLER, for the endless
     * home feed.
     *
     * Deliberately not ...OrderByCreatedAtDesc like the method above.
     * Infinite scroll needs a STABLE sort, and createdAt DESC is not one: a
     * product added while a customer is scrolling shifts every subsequent
     * page boundary by one, so they see an item twice or miss one entirely.
     * The feed sorts by id ascending instead, where new products land at the
     * end and every page already fetched keeps meaning the same thing.
     *
     * Category is eager-fetched here; variants are batched separately by
     * ProductService.batchFetchWithVariants rather than joined, because
     * fetching a collection alongside pagination makes Hibernate paginate in
     * memory over the whole result set.
     */
    @EntityGraph(attributePaths = {"category"})
    Page<Product> findByActiveTrue(Pageable pageable);

    // Admin management view's equivalent of the query above - includes
    // inactive/deactivated products too, still capped via Pageable.
    @EntityGraph(attributePaths = {"category"})
    Page<Product> findAllByOrderByCreatedAtDesc(Pageable pageable);

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
