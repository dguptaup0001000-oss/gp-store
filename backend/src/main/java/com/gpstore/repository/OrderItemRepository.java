package com.gpstore.repository;

import com.gpstore.entity.OrderItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /**
     * "Frequently bought together": for every other order that also contained
     * productId, count how often each OTHER product appeared alongside it.
     * Ranked by co-occurrence count, most frequent pairing first.
     *
     * PAGEABLE IS THE POINT, not a convenience. The caller shows five
     * products and used to receive every product ever co-purchased with this
     * one, then throw all but five away in Java. The database was already
     * doing the ranking - it just was not being allowed to stop.
     */
    @Query("select oi2.productVariant.product.id as productId, count(oi2) as cnt " +
            "from OrderItem oi1, OrderItem oi2 " +
            "where oi1.order = oi2.order " +
            "and oi1.productVariant.product.id = :productId " +
            "and oi2.productVariant.product.id <> :productId " +
            // PRIVATE PRODUCTS ARE EXCLUDED IN THE DATABASE, not afterwards.
            // A recommendation is the shop volunteering a product back to
            // someone who did not ask for it, on a screen anyone nearby can
            // see. Filtering in Java would still have carried the row - and
            // its real name - out of the database and into a response
            // somebody could forget to filter.
            "and oi2.productVariant.product.isPrivateProduct = false " +
            "group by oi2.productVariant.product.id " +
            "order by cnt desc")
    List<Object[]> findFrequentlyBoughtWithProductId(@Param("productId") Long productId, Pageable pageable);

    /**
     * Trending: most-ordered products since a given cutoff. Deliberately
     * time-boxed - "trending" should reflect recent behavior, not all-time
     * totals dominated by whatever's been on sale the longest.
     *
     * SAME MISSING LIMIT, on a hotter path. This runs a GROUP BY across
     * every order item in the window and returned one row per DISTINCT
     * PRODUCT ORDERED - the whole ranked leaderboard - so the service could
     * take the top ten. The result set grew with the catalogue and with
     * trading volume, on a query the home screen calls on every open.
     * orders.order_date is indexed (V2), so the scan itself is bounded by
     * the window; what was unbounded was how much came back.
     */
    @Query("select oi.productVariant.product.id as productId, count(oi) as cnt " +
            "from OrderItem oi " +
            "where oi.order.orderDate >= :since " +
            // Same rule as frequently-bought-together above: a private
            // product never appears in a list the customer did not ask for.
            "and oi.productVariant.product.isPrivateProduct = false " +
            "group by oi.productVariant.product.id " +
            "order by cnt desc")
    List<Object[]> findTrendingProductIds(@Param("since") LocalDateTime since, Pageable pageable);

    /**
     * Top products for the ADMIN dashboard: real units sold and real revenue.
     *
     * SEPARATE FROM findTrendingProductIds ABOVE, deliberately. That query
     * powers customer recommendations and counts ORDER LINES - a fine
     * popularity signal, but it is not units and it is not money. Reusing it
     * here is what made the admin dashboard report "1,245 sold" when 1,245
     * was the number of orders the product appeared in. Changing it in place
     * would have silently altered what customers get recommended.
     *
     * sum(oi.quantity) is units. sum(oi.totalPrice) is revenue, and CANCELLED
     * orders are excluded so this agrees with sumRevenueBetween instead of
     * quietly counting sales that were called off.
     *
     * Private products stay excluded, same rule as the recommendation query.
     */
    @Query("select oi.productVariant.product.id as productId, "
            + "coalesce(sum(oi.quantity), 0) as units, "
            + "coalesce(sum(oi.totalPrice), 0) as revenue "
            + "from OrderItem oi "
            + "where oi.order.orderDate >= :since "
            + "and oi.order.orderStatus <> 'CANCELLED' "
            + "and oi.productVariant.product.isPrivateProduct = false "
            + "group by oi.productVariant.product.id "
            + "order by units desc")
    List<Object[]> findTopProductsByUnits(@Param("since") LocalDateTime since, Pageable pageable);

    /**
     * A customer's recent purchase history, most recent order first,
     * eager-fetching productVariant and its product in the same query -
     * RecommendationService needs every item's product id just to dedupe,
     * which without the fetch joins meant two extra lazy-load queries
     * (productVariant, then its product) PER ORDER ITEM.
     *
     * Pageable is required, not optional: this used to return a customer's
     * ENTIRE lifetime order history to pick a handful of recommendations,
     * so its cost grew with how long someone had been a customer - the
     * loyal customers whose pages you least want to be slow. Recommendations
     * only need recent behaviour anyway, so the caller passes a hard cap.
     */
    @Query("SELECT oi FROM OrderItem oi " +
            "JOIN FETCH oi.productVariant pv " +
            "JOIN FETCH pv.product " +
            "WHERE oi.order.customer.id = :customerId " +
            // Buy Again / reorder suggestions are built from this. A private
            // product must never be suggested back to the customer who bought
            // it - that is precisely the leak this feature exists to close -
            // so it is excluded here rather than downstream.
            "AND pv.product.isPrivateProduct = false " +
            "ORDER BY oi.order.orderDate DESC")
    List<OrderItem> findByCustomerIdWithProductFetched(@Param("customerId") Long customerId,
                                                       org.springframework.data.domain.Pageable pageable);

    /** Used to enforce "verified purchase only" reviews - has this customer ever actually ordered this product? */
    boolean existsByOrder_Customer_IdAndProductVariant_Product_Id(Long customerId, Long productId);

    /**
     * Every line item on one order - used to restore inventory when an order
     * is cancelled or its payment expires unconfirmed (see OrderService.cancelOrder
     * and PaymentService.expireStalePendingUpiPayments). Without this, stock
     * decremented at checkout time was never given back, permanently
     * understating real available inventory.
     */
    List<OrderItem> findByOrderId(Long orderId);
}