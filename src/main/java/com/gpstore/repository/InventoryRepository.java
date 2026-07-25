package com.gpstore.repository;

import com.gpstore.entity.Inventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByProductVariantId(Long productVariantId);

    /**
     * Locks the inventory row for the duration of the transaction so two
     * concurrent orders can't both pass the stock check and both decrement
     * (prevents overselling).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inventory i where i.productVariant.id = :productVariantId")
    Optional<Inventory> findByProductVariantIdForUpdate(Long productVariantId);

    /** Items at or below their configured reorder point - the actual "restock this" list. */
    @Query("select i from Inventory i where i.minimumStock is not null and i.stock <= i.minimumStock")
    List<Inventory> findLowStock();

}