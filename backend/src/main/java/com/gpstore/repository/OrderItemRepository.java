package com.gpstore.repository;

import com.gpstore.entity.OrderItem;
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
     */
    @Query("select oi2.productVariant.product.id as productId, count(oi2) as cnt " +
            "from OrderItem oi1, OrderItem oi2 " +
            "where oi1.order = oi2.order " +
            "and oi1.productVariant.product.id = :productId " +
            "and oi2.productVariant.product.id <> :productId " +
            "group by oi2.productVariant.product.id " +
            "order by cnt desc")
    List<Object[]> findFrequentlyBoughtWithProductId(@Param("productId") Long productId);

    /**
     * Trending: most-ordered products since a given cutoff. Deliberately
     * time-boxed - "trending" should reflect recent behavior, not all-time
     * totals dominated by whatever's been on sale the longest.
     */
    @Query("select oi.productVariant.product.id as productId, count(oi) as cnt " +
            "from OrderItem oi " +
            "where oi.order.orderDate >= :since " +
            "group by oi.productVariant.product.id " +
            "order by cnt desc")
    List<Object[]> findTrendingProductIds(@Param("since") LocalDateTime since);

    /** Full purchase history for a customer, most recent order first - used for reorder suggestions. */
    List<OrderItem> findByOrder_Customer_IdOrderByOrder_OrderDateDesc(Long customerId);

    /**
     * Same as above, but eager-fetches productVariant and its product in the
     * same query - RecommendationService.forCustomer() needs every item's
     * product ID just to walk the list and dedupe, which previously meant
     * two extra lazy-load queries (productVariant, then productVariant's
     * own product) PER ORDER ITEM in that customer's entire history.
     */
    @Query("SELECT oi FROM OrderItem oi " +
            "JOIN FETCH oi.productVariant pv " +
            "JOIN FETCH pv.product " +
            "WHERE oi.order.customer.id = :customerId " +
            "ORDER BY oi.order.orderDate DESC")
    List<OrderItem> findByCustomerIdWithProductFetched(@Param("customerId") Long customerId);

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