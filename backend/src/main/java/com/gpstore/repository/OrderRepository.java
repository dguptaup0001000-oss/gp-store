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

    /** Uniqueness check when a new pack code is minted. */
    java.util.Optional<com.gpstore.entity.Order> findByPackCode(String packCode);

    /**
     * Row-locking lookup for the typed half of the label.
     *
     * Locks for the same reason findByQrTokenForUpdate does: two workers can
     * claim one carton in the same second, and an unlocked read lets both see
     * an unused label. One of them has to lose, in the database rather than in
     * the app.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.packCode = :packCode")
    Optional<Order> findByPackCodeForUpdate(@Param("packCode") String packCode);

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
     * Every shop's order under one checkout.
     *
     * Ordered by shop so a customer's screen does not reshuffle between
     * refreshes. Shop-filtered like every other query here - which means a
     * shopkeeper opening a group sees only their own half, and the customer
     * who placed it reads it through CustomerOwnedRead.
     */
    List<Order> findByOrderGroupIdOrderByShopIdAsc(Long orderGroupId);

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
     * The morning preparation list: orders due on one day that still need work.
     *
     * <p>PAGED, AND FILTERED IN SQL. The obvious implementation - load every
     * order and filter in Java - is a full table scan that grows with the
     * shop's whole history to answer a question about one day, and it is the
     * exact failure the brief names. This narrows on an indexed column
     * (idx_orders_scheduled_delivery_date, V33) and returns a page.
     *
     * <p>DELIVERED and CANCELLED are excluded because they need no packing;
     * PENDING_CONFIRMATION is excluded because an unpaid UPI order must not be
     * packed - see OrderService, which is where that rule already lives.
     */
    @Query("select o from Order o "
            + "where o.scheduledDeliveryDate = :date "
            + "and o.orderStatus in (com.gpstore.enums.OrderStatus.CONFIRMED, "
            + "                      com.gpstore.enums.OrderStatus.PACKING) "
            + "order by o.orderDate asc")
    Page<Order> findForPreparation(@Param("date") java.time.LocalDate date, Pageable pageable);

    /**
     * How many orders are due that day and still unpacked - the badge number.
     *
     * <p>A COUNT, not {@code findForPreparation(...).size()}. The count runs in
     * the database and returns one long; materialising the orders to count
     * them is the same "load everything into memory" mistake wearing a hat.
     */
    @Query("select count(o) from Order o "
            + "where o.scheduledDeliveryDate = :date "
            + "and o.orderStatus in (com.gpstore.enums.OrderStatus.CONFIRMED, "
            + "                      com.gpstore.enums.OrderStatus.PACKING)")
    long countForPreparation(@Param("date") java.time.LocalDate date);

    /**
     * Orders per delivery type over a period, aggregated in SQL.
     *
     * <p>Returns [deliveryType, orderCount, revenue]. CANCELLED is excluded to
     * match sumRevenueBetween and the dashboard KPI - a split that counts
     * cancellations while the headline revenue does not is a split whose
     * columns never add up to the total shown above them.
     */
    @Query("select o.deliveryType, count(o), coalesce(sum(o.totalAmount), 0) from Order o "
            + "where o.orderDate >= :from and o.orderDate <= :to "
            + "and o.orderStatus <> com.gpstore.enums.OrderStatus.CANCELLED "
            + "group by o.deliveryType")
    java.util.List<Object[]> countByDeliveryTypeBetween(
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to);

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

    /**
     * Revenue and order count per calendar day, for the dashboard sales chart.
     *
     * MATCHES sumRevenueBetween EXACTLY - same CANCELLED exclusion, same
     * orderDate column, same inclusive bounds. If the chart summed to a
     * different number than the KPI card sitting above it, the dashboard
     * would be contradicting itself and an operator would be right to
     * trust neither figure.
     *
     * Native because grouping on a truncated date is not portable JPQL and
     * date_trunc is what Postgres actually runs. Days with no orders are
     * absent here; the service fills them with zero so the chart has no
     * holes.
     */
    /**
     * THE SHOP PREDICATE IS WRITTEN BY HAND, because this query is native.
     *
     * Hibernate's filter rewrites JPQL and HQL; it does not touch a native
     * statement, so without the clause below this would total EVERY shop's
     * takings into one line on one shop's dashboard. The value is not a
     * parameter a caller chooses: AnalyticsService reads it from the tenant
     * scope on the thread, which came from the credential.
     *
     * A NULL shopId means platform-wide, which is a real answer for a
     * marketplace operator looking at the whole market - and is what a
     * single-shop deployment's scheduled reporting already does.
     */
    @Query(value = "select date_trunc('day', o.order_date) as day, "
            + "coalesce(sum(o.total_amount), 0) as revenue, "
            + "count(*) as orders "
            + "from orders o "
            + "where o.order_date >= :from and o.order_date <= :to "
            + "and o.order_status <> 'CANCELLED' "
            + "and (cast(:shopId as bigint) is null or o.shop_id = :shopId) "
            + "group by date_trunc('day', o.order_date) "
            + "order by 1",
            nativeQuery = true)
    List<Object[]> revenueByDayBetween(@Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to,
                                       @Param("shopId") Long shopId);

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
