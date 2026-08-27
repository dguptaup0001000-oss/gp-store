package com.gpstore.repository;

import com.gpstore.entity.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * The scan's ONLY way in.
     *
     * A worker's app sends a token, never an order id - an endpoint that
     * accepts an order id is an endpoint whose ids can be walked, and the
     * whole point of the QR token is that it is random and unguessable.
     */
    java.util.Optional<com.gpstore.entity.Order> findByQrToken(String qrToken);

    /**
     * Pack-scan lock. Two workers scanning the same label at the same
     * instant must serialize here so the second one sees {@code qrTokenUsedAt}
     * rather than both writing {@code packedByPartner}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.qrToken = :qrToken")
    Optional<Order> findByQrTokenForUpdate(@Param("qrToken") String qrToken);


    List<Order> findByCustomerId(Long customerId);

    /**
     * Locks the order row for the duration of the transaction. cancelOrder
     * and updateOrderStatus both used to be a plain read-check-write: read
     * the current status, validate the transition, write the new status and
     * (for cancellation) restore inventory - with no lock between the read
     * and the write. Two concurrent requests for the same order (a
     * double-tap on "Cancel order", or two admins/delivery staff advancing
     * the same order at once) could both read the same pre-change status,
     * both pass validation, and both apply their change - for cancellation
     * that means inventory gets restored twice for one order. Locking here
     * means the second request blocks until the first commits, then
     * re-reads the now-already-changed status under its own lock and
     * correctly rejects as a conflict instead of racing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    /**
     * One order with everything OrderDetailResponse renders, in a single
     * query: its line items, each item's variant and product, and the
     * delivery address.
     *
     * Without this, building the detail DTO lazily loaded the items
     * collection, then the variant and product PER ITEM, then the address -
     * an N+1 paid by three separate endpoints (order detail, status update
     * and cancellation), on top of whatever those operations had already
     * done. Measured on a 3-item cancellation it was a meaningful share of
     * the total query count.
     *
     * Fetch-joining a collection is safe HERE specifically because this
     * returns a single order by id - there is no Pageable, so the
     * in-memory-pagination problem that rule normally warns about cannot
     * arise.
     */
    @Query("select distinct o from Order o "
            + "left join fetch o.orderItems oi "
            + "left join fetch oi.productVariant pv "
            + "left join fetch pv.product "
            + "left join fetch o.address "
            + "where o.id = :id")
    Optional<Order> findByIdWithDetails(@Param("id") Long id);

    List<Order> findByCustomerIdOrderByOrderDateDesc(Long customerId);

    Page<Order> findByCustomerIdOrderByOrderDateDesc(Long customerId, Pageable pageable);

    /**
     * Admin order list, newest first, with the customer fetched in the same
     * query.
     *
     * The fetch join is the point: the admin list shows a customer name per
     * row, and Order.customer is a LAZY @ManyToOne, so rendering a page of
     * 50 orders issued 1 query for the page plus 50 more for the customers.
     * That N+1 scales with page size and is invisible in testing with a
     * handful of orders.
     *
     * Fetch-joining a to-ONE relation is safe with Pageable - the LIMIT is
     * still applied in the database. (The in-memory-pagination problem only
     * arises when fetch-joining a COLLECTION, which this deliberately does
     * not do.)
     */
    @Query(value = "select o from Order o left join fetch o.customer order by o.orderDate desc",
            countQuery = "select count(o) from Order o")
    Page<Order> findAllByOrderByOrderDateDesc(Pageable pageable);

    /**
     * Real revenue only - excludes CANCELLED orders so a cancelled order
     * doesn't inflate reported sales. Returns null (not zero) via COALESCE
     * guard in the service if there are no matching orders.
     */
    @Query("select coalesce(sum(o.totalAmount), 0) from Order o " +
            "where o.orderDate >= :from and o.orderDate <= :to and o.orderStatus <> 'CANCELLED'")
    BigDecimal sumRevenueBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select count(o) from Order o " +
            "where o.orderDate >= :from and o.orderDate <= :to and o.orderStatus <> 'CANCELLED'")
    long countOrdersBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select count(o) from Order o where o.orderDate >= :from and o.orderDate <= :to and o.orderStatus = 'CANCELLED'")
    long countCancelledBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Order count grouped by current status - the "what's in the pipeline right now" view. */
    @Query("select o.orderStatus as status, count(o) as cnt from Order o group by o.orderStatus")
    List<Object[]> countByStatus();

    /**
     * Backs order number generation (see OrderService.placeOrder) with a
     * real Postgres sequence (V6 migration) instead of JVM memory - a
     * database sequence survives process restarts, unlike the in-memory
     * AtomicInteger this replaced, which reset to 1 on every deploy and
     * could then collide with an order number already used earlier that
     * day, crashing checkout with an unhandled 500.
     */
    @Query(value = "SELECT nextval('order_number_seq')", nativeQuery = true)
    long nextOrderNumberSequenceValue();

    @Query("select coalesce(max(o.id), 0) from Order o")
    long findMaxId();

    /**
     * Shop-counter soundbox poll: orders newer than {@code afterId}, oldest
     * first, customer fetched in the same query so the spoken name does not
     * N+1. Pageable supplies the LIMIT. Fetch-joining a to-ONE is safe with
     * Pageable.
     */
    @Query("select o from Order o left join fetch o.customer where o.id > :afterId order by o.id asc")
    List<Order> findNewSince(@Param("afterId") Long afterId, Pageable pageable);

}
