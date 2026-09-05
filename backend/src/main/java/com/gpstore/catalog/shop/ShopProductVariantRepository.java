package com.gpstore.catalog.shop;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Every method here is already shop-scoped, and none of them say so.
 *
 * That is the point of the Slice 1 filter: these are ordinary queries, and
 * Hibernate adds "and shop_id = ?" to each one while a shop scope is active.
 * A new finder added below inherits the same treatment without anybody
 * remembering to ask for it - which is the only way scoping survives contact
 * with a codebase somebody else is also working in.
 */
public interface ShopProductVariantRepository extends JpaRepository<ShopProductVariant, Long> {

    Optional<ShopProductVariant> findByProductVariantId(Long productVariantId);

    List<ShopProductVariant> findByProductVariantIdIn(Collection<Long> productVariantIds);

    /**
     * One named shop's listings.
     *
     * The shop is a predicate here rather than a filter, for checkout - which
     * visits several shops inside one transaction and therefore inside one
     * persistence session, where the filter was fixed when the session opened.
     * Naming it is what makes each half of a split basket read its own prices.
     */
    List<ShopProductVariant> findByShopIdAndProductVariantIdIn(
            Long shopId, Collection<Long> productVariantIds);

    Page<ShopProductVariant> findAllByOrderByIdAsc(Pageable pageable);

    /** What this shop actually offers - listed, active and priced. */
    @Query("select s from ShopProductVariant s "
            + "where s.available = true and s.active = true and s.sellingPrice > 0 "
            + "and s.productVariantId in :variantIds")
    List<ShopProductVariant> findOrderableByVariantIds(@Param("variantIds") Collection<Long> variantIds);

    long countByAvailableTrueAndActiveTrue();

    /**
     * Stock and this shop's price for a basket, in ONE query.
     *
     * WHY THE TWO ARE FETCHED TOGETHER. The cart read is the most-called
     * authenticated endpoint, and CheckoutPerformanceTest holds it to a fixed
     * number of queries regardless of basket size. Asking for stock and then
     * asking for prices would be a second round trip on that path for every
     * cart read in the shop, on a connection held open across both.
     *
     * INVENTORY IS THE ROOT, not the listing, because stock is what gates
     * availability: an item with a stock row and no listing must still report
     * its real stock, and its price then falls back exactly as it did before
     * listings existed. Both entities are shop-owned, so both halves of this
     * join are filtered to the shop in scope - proved by
     * CrossTenantShopCatalogTest rather than assumed.
     */
    @Query("select i.productVariant.id as variantId, i.stock as stock, s.sellingPrice as price "
            + "from Inventory i "
            + "left join ShopProductVariant s on s.productVariantId = i.productVariant.id "
            + "where i.productVariant.id in :variantIds")
    List<ShelfLine> findShelfLines(@Param("variantIds") Collection<Long> variantIds);

    /** One basket line's stock and price. A null price means this shop does not list it. */
    interface ShelfLine {
        Long getVariantId();
        Integer getStock();
        java.math.BigDecimal getPrice();
    }
}
