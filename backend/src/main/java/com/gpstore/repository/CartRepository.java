package com.gpstore.repository;

import com.gpstore.entity.Cart;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {

    Optional<Cart> findByCustomerId(Long customerId);

    /**
     * Locks the customer's cart row for the duration of the transaction -
     * same reasoning as InventoryRepository.findByProductVariantIdForUpdate.
     * CartService.addToCart/updateItemQuantity/removeItem all do a
     * read-then-write on cart items (increment an existing item's quantity,
     * or recompute the cart's denormalized totalItems/totalAmount from the
     * current items list) with no unique constraint backing it - two
     * concurrent requests against the same cart (a double-tap that slips
     * past the frontend's own guard, two logged-in devices, a client retry)
     * could otherwise both read the same pre-mutation state and one
     * increment/removal would be silently lost.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cart c where c.customer.id = :customerId")
    Optional<Cart> findByCustomerIdForUpdate(@Param("customerId") Long customerId);

    /**
     * Eager-fetches items -> productVariant -> product in one query - every
     * cart-returning CartService method ultimately gets serialized through
     * CartResponse.from(), which needs each item's variant AND that
     * variant's product (for name/brand/image). Without this, that was two
     * extra lazy-load queries PER CART ITEM on every add/update/remove/view
     * - for a cart with several items, enough sequential round trips
     * (worse across providers - this app's DB and backend are on different
     * hosts) to make "add to cart" visibly slow instead of near-instant.
     * DISTINCT avoids duplicate Cart rows from the items join multiplying
     * the result set.
     */
    @Query("SELECT DISTINCT c FROM Cart c " +
            "LEFT JOIN FETCH c.items i " +
            "LEFT JOIN FETCH i.productVariant pv " +
            "LEFT JOIN FETCH pv.product " +
            "WHERE c.customer.id = :customerId")
    Optional<Cart> findByCustomerIdWithItemsFetched(@Param("customerId") Long customerId);
}