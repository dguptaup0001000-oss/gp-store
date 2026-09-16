package com.gpstore.repository;

import com.gpstore.catalog.shop.ShopProductVariant;
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

    /**
     * What a customer standing in THIS shop may buy.
     *
     * "SELLABLE" MEANT "IN THE CATALOGUE" AND THAT IS NOT THE SAME QUESTION.
     * Products and variants are central by design (§10) - one row per item for
     * the whole marketplace - so a query that asks only "is this variant
     * priced" answers for the catalogue rather than for a shelf. With two
     * shops running, a customer who opened Shop A's storefront was shown Shop
     * B's goods: not a data leak (nothing shop-owned is returned, and an
     * unlisted item is refused at add-to-cart because ShopCatalog's catalogue
     * fallback is off under a marketplace) but a shop advertising a
     * neighbour's stock, which no shopkeeper would accept.
     *
     * THE SUBQUERY IS SHOP-FILTERED, not shop-parameterised. ShopProductVariant
     * carries the Slice 1 @Filter, so as a subquery root it is already narrowed
     * to the shop in scope - which is why no shop id is passed and there is
     * none for a caller to change.
     *
     * INERT UNDER SINGLE_SHOP, deliberately. requireListing is false there, so
     * the query is character-for-character the one Shop #1 has always run - a
     * variant created before listings existed, or one an admin priced without
     * listing, keeps selling exactly as it did (§12).
     */
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
                    AND (:requireListing = false OR EXISTS (
                        SELECT 1 FROM ShopProductVariant l
                        WHERE l.productVariantId = v.id
                          AND l.available = true
                          AND (l.active = true OR l.active IS NULL)
                          AND l.sellingPrice IS NOT NULL
                          AND l.sellingPrice > 0
                    ))
              )
            """)
    Page<Product> findSellable(@Param("requireListing") boolean requireListing, Pageable pageable);

    /** The same question, inside one category. See findSellable. */
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
                    AND (:requireListing = false OR EXISTS (
                        SELECT 1 FROM ShopProductVariant l
                        WHERE l.productVariantId = v.id
                          AND l.available = true
                          AND (l.active = true OR l.active IS NULL)
                          AND l.sellingPrice IS NOT NULL
                          AND l.sellingPrice > 0
                    ))
              )
            """)
    Page<Product> findSellableByCategoryId(@Param("categoryId") Long categoryId,
                                           @Param("requireListing") boolean requireListing,
                                           Pageable pageable);

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
     * Products represented on the current shop's shelf, including inactive
     * listings so a merchant can inspect and reactivate something it delisted.
     *
     * <p>{@link ShopProductVariant} is shop-filtered by the tenant scope. No
     * shop id is accepted here, so a caller cannot turn this into another
     * merchant's catalogue by changing a request value. Variants are fetched
     * separately in one batch by {@code ProductService}; joining that
     * collection in a paged query would make Hibernate paginate multiplied
     * rows instead of products.
     */
    @EntityGraph(attributePaths = {"category"})
    @Query("""
            SELECT p FROM Product p
            WHERE EXISTS (
                SELECT 1 FROM ProductVariant v, ShopProductVariant l
                WHERE v.product = p AND l.productVariantId = v.id
            )
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    Page<Product> findAllListedForCurrentShop(Pageable pageable);

    /**
     * Only brands that actually have at least one active product - the
     * GROUP BY naturally guarantees this (a brand with zero products
     * simply never produces a row), no separate filter needed.
     *
     * "Shop by Brand" IS A BROWSE SURFACE, and a count is a leak like any
     * other: showing a storefront "Aashirvaad (14)" for a brand it has never
     * stocked hands the customer a tile that opens on an empty grid, and
     * hands the merchant a count of what the shop down the road carries.
     * requireListing narrows the count to this shop's shelf exactly the way
     * findSellable does, and relies on the same two things - the EXISTS for
     * "listed at all", and the shop filter on ShopProductVariant for "listed
     * HERE", which Hibernate applies to this JPQL automatically.
     *
     * INERT UNDER SINGLE_SHOP: requireListing is false and the extra clause
     * short-circuits, leaving the count this query has always returned.
     *
     * The vocabulary that powers spell correction deliberately passes false
     * even under a marketplace - see BrandVocabulary.
     */
    @Query("select p.brand as brand, count(p) as productCount from Product p " +
            "where p.active = true and p.brand is not null and p.brand <> '' " +
            "and (:requireListing = false or exists (" +
            "  select 1 from ProductVariant v" +
            "  where v.product = p and v.available = true" +
            "    and (v.active = true or v.active is null)" +
            "    and v.sellingPrice is not null and v.sellingPrice > 0" +
            "    and exists (select 1 from ShopProductVariant l" +
            "                where l.productVariantId = v.id and l.available = true" +
            "                  and (l.active = true or l.active is null)" +
            "                  and l.sellingPrice is not null and l.sellingPrice > 0))) " +
            "group by p.brand order by p.brand asc")
    List<Object[]> findBrandsWithProductCounts(@Param("requireListing") boolean requireListing);
}
