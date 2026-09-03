package com.gpstore.repository;

import com.gpstore.entity.OrderReturn;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderReturnRepository extends JpaRepository<OrderReturn, Long> {

    /** "My returns", newest first. The customer app's only query here. */
    @Query("""
           select r from OrderReturn r
           where r.customer.id = :customerId
           order by r.requestedAt desc
           """)
    Page<OrderReturn> forCustomer(@Param("customerId") Long customerId, Pageable pageable);

    /**
     * The admin queue: what is waiting for a decision, oldest first.
     *
     * OLDEST FIRST on purpose. A returns queue worked newest-first leaves the
     * customer who asked on Monday still waiting on Friday, and they are the
     * one most likely to ring.
     */
    @Query("""
           select r from OrderReturn r
           where r.status = com.gpstore.entity.OrderReturn$Status.REQUESTED
           order by r.requestedAt asc
           """)
    Page<OrderReturn> awaitingDecision(Pageable pageable);

    @Query("select r from OrderReturn r where r.order.id = :orderId order by r.requestedAt")
    List<OrderReturn> forOrder(@Param("orderId") Long orderId);

    /**
     * How many units of one order line are already spoken for.
     *
     * REQUESTED AND APPROVED BOTH COUNT. An approved return has gone back; a
     * requested one has not been decided yet, and letting a second request
     * claim the same units would put two returns in the queue for one item -
     * approve both and the shop refunds twice for goods it received once.
     * Rejected and cancelled returns release their units, which is the point
     * of rejecting them.
     */
    @Query("""
           select coalesce(sum(i.quantity), 0)
           from OrderReturnItem i
           where i.orderItem.id = :orderItemId
             and i.orderReturn.status in (
                 com.gpstore.entity.OrderReturn$Status.REQUESTED,
                 com.gpstore.entity.OrderReturn$Status.APPROVED)
           """)
    int unitsAlreadyClaimedFor(@Param("orderItemId") Long orderItemId);

    /**
     * Locks the return row so two admins pressing Approve at the same moment
     * cannot both refund it. The second waits, then sees it is no longer
     * REQUESTED and is refused.
     */
    @Query("select r from OrderReturn r where r.id = :id")
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<OrderReturn> findByIdForUpdate(@Param("id") Long id);

    long countByStatus(OrderReturn.Status status);
}
