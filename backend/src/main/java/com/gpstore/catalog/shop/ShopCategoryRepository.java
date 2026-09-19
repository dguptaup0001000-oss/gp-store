package com.gpstore.catalog.shop;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * A shop's own departments, and like every other shop-owned repository here
 * none of these methods mention a shop.
 *
 * <p>The Slice 1 filter adds {@code and shop_id = ?} while a scope is active,
 * so "the departments" always means "the departments of the shop asking". A
 * finder added below inherits that without anyone remembering to ask.
 */
public interface ShopCategoryRepository extends JpaRepository<ShopCategory, Long> {

    List<ShopCategory> findByActiveTrueOrderByDisplayOrderAscNameAsc();

    List<ShopCategory> findAllByOrderByDisplayOrderAscNameAsc();

    /**
     * The same name, however it was capitalised.
     *
     * <p>Used to turn a duplicate into a 409 the merchant can understand
     * rather than a raw constraint violation from the unique index that backs
     * the same rule in the database. Both exist on purpose: this one gives a
     * good message, the index is what actually holds under a double tap.
     */
    @Query("select c from ShopCategory c where lower(c.name) = lower(:name)")
    Optional<ShopCategory> findByNameIgnoringCase(@Param("name") String name);

    /** Departments standing for platform categories, for "what do I stock". */
    @Query("select c.globalCategoryId from ShopCategory c "
            + "where c.active = true and c.globalCategoryId is not null")
    List<Long> globalCategoryIdsOnThisShelf();
}
