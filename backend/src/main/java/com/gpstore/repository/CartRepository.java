package com.gpstore.repository;

import com.gpstore.entity.Cart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {

    Optional<Cart> findByCustomerId(Long customerId);

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

    /** One basket with its lines, variants and products already loaded. */
    @Query("SELECT DISTINCT c FROM Cart c "
            + "LEFT JOIN FETCH c.items i "
            + "LEFT JOIN FETCH i.productVariant pv "
            + "LEFT JOIN FETCH pv.product "
            + "WHERE c.id = :cartId")
    Optional<Cart> findByIdWithItemsFetched(@Param("cartId") Long cartId);

    /**
     * Baskets holding at least one line off THIS shop's shelf.
     *
     * <p>WAS findAll(pageable), behind GET /api/carts - the cart-abandonment
     * listing, gated by the role every shop owner holds. Cart is deliberately
     * not a {@code ShopOwned} entity (a basket belongs to a shopper and spans
     * shops on purpose), so nothing narrowed it: a merchant read every live
     * basket on GP-STORE, with each line's product, price and shop.
     *
     * <p>NARROWED ON THE LINE'S SHOP, which is the only thing that ties a
     * basket to a shop at all. {@code cart_items.shop_id} is stamped by the
     * server when the line is added and never accepted from a client, so it is
     * a fact rather than a claim. The lines themselves are filtered again where
     * the response is built - a merchant sees that a basket of theirs is
     * abandoned, not what else is in it.
     */
    @Query("SELECT c FROM Cart c WHERE EXISTS ("
            + "  SELECT 1 FROM CartItem i WHERE i.cart = c AND i.shopId = :shopId)")
    org.springframework.data.domain.Page<Cart> findAllWithALineFromShop(
            @Param("shopId") Long shopId, org.springframework.data.domain.Pageable pageable);
}