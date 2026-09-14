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
    @Query("select i.productVariant.id as variantId, i.stock as stock, s.sellingPrice as price, "
            + "s.available as listed, s.active as activeListing "
            + "from Inventory i "
            + "left join ShopProductVariant s on s.productVariantId = i.productVariant.id "
            + "where i.productVariant.id in :variantIds")
    List<ShelfLine> findShelfLines(@Param("variantIds") Collection<Long> variantIds);

    /**
     * Which categories each of these shops actually has on its shelf.
     *
     * WHAT MAKES A SHOP A "KIRANA SHOP" HERE. Nothing declares it. A shop
     * belongs to a category when it is actually listing something orderable in
     * that category, which is the only definition that stays true on its own:
     * a merchant who stops stocking medicine stops appearing under Medicine
     * the moment their last listing goes, without anybody remembering to edit
     * a tag. A declared category would be a second, staler truth beside this
     * one, and the customer would be shown shops that sell nothing they came
     * for.
     *
     * READS TWO COLUMNS AND NOTHING ELSE, and that is deliberate rather than
     * incidental. This is the ONE query in the customer-facing marketplace
     * that spans shops (ShopCategoryPresence runs it in platform scope, with
     * the shop filter off), so what it selects is what a public caller can
     * learn. "Shop 6 sells groceries" is already public - it is the answer the
     * discovery screen exists to give. A price, a stock level or a cost here
     * would not be, so nothing else is selected, and
     * CategoryDiscoveryTest pins the projection.
     *
     * GROUPED RATHER THAN DISTINCT so the count comes free for a caller that
     * wants to rank by how deep a shop's range in a category is.
     */
    @Query("select s.shopId as shopId, p.category.id as categoryId, count(s) as listings "
            + "from ShopProductVariant s, ProductVariant v, Product p "
            + "where v.id = s.productVariantId and p.id = v.product.id "
            + "and s.shopId in :shopIds "
            + "and s.available = true and s.active = true "
            + "and v.available = true and v.active = true "
            + "and p.active = true and p.category.id is not null "
            + "group by s.shopId, p.category.id")
    List<ShelfCategory> findShelfCategories(@Param("shopIds") Collection<Long> shopIds);

    /** One shop, one category it stocks, and how many listings it has there. */
    interface ShelfCategory {
        Long getShopId();
        Long getCategoryId();
        long getListings();
    }

    /**
     * One basket line's stock and price at one shop.
     *
     * THE FLAGS MATTER AS MUCH AS THE PRICE. A shop can delist an item it
     * still has a row and units for - the row stays, so that a re-listing
     * keeps the shop's own price rather than resetting to the catalogue's -
     * and a basket that read only the price would go on offering something
     * checkout will refuse.
     */
    interface ShelfLine {
        Long getVariantId();
        Integer getStock();
        java.math.BigDecimal getPrice();
        Boolean getListed();
        Boolean getActiveListing();

        /** Whether this shop will actually sell it right now. */
        default boolean isOrderableHere() {
            return Boolean.TRUE.equals(getListed())
                    && Boolean.TRUE.equals(getActiveListing())
                    && getPrice() != null
                    && getPrice().signum() > 0;
        }
    }
}
