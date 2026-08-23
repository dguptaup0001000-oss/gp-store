package com.gpstore.repository;

import com.gpstore.entity.Inventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByProductVariantId(Long productVariantId);

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