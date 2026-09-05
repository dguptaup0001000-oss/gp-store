package com.gpstore.repository;

import com.gpstore.entity.Inventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByProductVariantId(Long productVariantId);

    /**
     * One round trip for a customer's cart GET. Selecting the variant id and
     * stock only so this does not lazy-load every Inventory.productVariant.
     */
    @Query("select i.productVariant.id, i.stock from Inventory i where i.productVariant.id in :variantIds")
    List<Object[]> findStockByProductVariantIds(@Param("variantIds") Collection<Long> variantIds);

    // Real pagination (see admin_inventory_screen.dart's infinite scroll) -
    // was a plain unbounded findAll() before, loading every inventory row
    // ever created into memory on every admin screen visit.
    //
    // THE FETCH JOINS ARE NOT DECORATION. Every row rendered on that screen
    // shows the product's name and brand, which live two associations away
    // (Inventory -> ProductVariant -> Product), and both are lazy. Without
    // them a page of twenty rows was one query for the page and then two more
    // per row - forty-one queries to draw one screen - and each of those was
    // issued during response serialisation, which is a place a query has no
    // business being.
    //
    // countQuery is given explicitly because Spring Data cannot derive a
    // count query from a fetch join and would otherwise refuse to start.
    @Query(value = "select i from Inventory i "
            + "left join fetch i.productVariant v "
            + "left join fetch v.product "
            + "order by i.id asc",
            countQuery = "select count(i) from Inventory i")
    Page<Inventory> findAllByOrderByIdAsc(Pageable pageable);

    /**
     * Locks the inventory row for the duration of the transaction so two
     * concurrent orders can't both pass the stock check and both decrement
     * (prevents overselling).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inventory i where i.productVariant.id = :productVariantId")
    Optional<Inventory> findByProductVariantIdForUpdate(Long productVariantId);

    /**
     * The same lock, for work that is not inside a shop's request.
     *
     * The inventory restore runs from the payment-expiry sweep, which spans
     * shops and therefore has no filter enabled. Naming the shop explicitly -
     * read off the ORDER being restored, not off anything a caller sent - is
     * what stops it locking and crediting another merchant's stock row.
     */
    @org.springframework.data.jpa.repository.Query(
            "select i from Inventory i where i.productVariant.id = :productVariantId "
                    + "and i.shopId = :shopId")
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<Inventory> findByProductVariantIdAndShopIdForUpdate(
            @org.springframework.data.repository.query.Param("productVariantId") Long productVariantId,
            @org.springframework.data.repository.query.Param("shopId") Long shopId);

    /**
     * One-statement decrement. Two concurrent checkouts cannot both subtract
     * from the same unit: PostgreSQL applies the WHERE atomically, so the
     * second update matches zero rows.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    /**
     * THE SHOP IS AN EXPLICIT PREDICATE, because a bulk update is not filtered.
     *
     * Hibernate applies filters to selects, not to "update ... where ..." - so
     * without the shop clause this statement reaches every shop's row for the
     * variant and decrements all of them. One customer buying one packet would
     * take a unit off every merchant who stocks it.
     *
     * The value comes from the tenant scope, not from a caller (see
     * InventoryService.decrementForPurchase). The atomicity that makes this
     * safe under concurrency is unchanged: the WHERE still either matches one
     * row or none.
     */
    @Query("update Inventory i set i.stock = i.stock - :quantity "
            + "where i.productVariant.id = :productVariantId "
            + "and i.shopId = :shopId and i.stock >= :quantity")
    int decrementIfAvailable(Long productVariantId, int quantity, Long shopId);

    /**
     * Items at or below their configured reorder point - the actual
     * "restock this" list. Fetch-joined for the same reason as the page
     * above: the response names the product on every row.
     */
    @Query("select i from Inventory i "
            + "left join fetch i.productVariant v "
            + "left join fetch v.product "
            + "where i.minimumStock is not null and i.stock <= i.minimumStock")
    List<Inventory> findLowStock();

    /**
     * Same filter as findLowStock, but for AnalyticsService.getLowStockCount -
     * that call only ever needed the count, not every matching Inventory row
     * (with its ProductVariant join) loaded into memory just to call
     * .size() on the list.
     */
    @Query("select count(i) from Inventory i where i.minimumStock is not null and i.stock <= i.minimumStock")
    long countLowStock();

}