package com.gpstore.repository;

import java.util.Optional;

import com.gpstore.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    List<CartItem> findByCartId(Long cartId);

    /**
     * Cart items with everything checkout actually reads, in one query:
     * the variant, its product, and that product's CATEGORY.
     *
     * The category matters and is easy to miss. TaxService.resolveGstRate
     * walks variant -> product -> category for every line item, and category
     * is a lazy association two levels down - so a 10-item checkout issued 10
     * extra SELECTs against `categories` on top of the per-variant and
     * per-product loads. Measured: place-order over a 10-item cart cost 63
     * queries, of which 10 were categories, ~20 were product_variants and 10
     * were products.
     *
     * Used by both previewCheckout and placeOrder so the two agree on what a
     * cart line is and neither pays for lazy walking.
     */
    @Query("SELECT ci FROM CartItem ci "
            + "JOIN FETCH ci.productVariant pv "
            + "JOIN FETCH pv.product p "
            + "LEFT JOIN FETCH p.category "
            + "WHERE ci.cart.id = :cartId")
    List<CartItem> findByCartIdForCheckout(@Param("cartId") Long cartId);

    Optional<CartItem> findByCartIdAndProductVariantId(
        Long cartId,
        Long productVariantId
);

    /**
     * Deletes a cart's items in ONE statement.
     *
     * Checkout previously loaded every CartItem into the persistence context
     * purely to hand them back to deleteAll(), which then issued one DELETE
     * per row - a select plus N deletes to empty a cart, inside the checkout
     * transaction while inventory row locks were held. Nothing read those
     * entities; they were loaded only to be deleted.
     *
     * clearAutomatically so the persistence context does not keep serving
     * cart items this statement has already removed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from CartItem ci where ci.cart.id = :cartId")
    int deleteByCartId(@Param("cartId") Long cartId);
}
