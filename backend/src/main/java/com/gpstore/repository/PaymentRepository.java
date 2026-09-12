package com.gpstore.repository;

import com.gpstore.entity.Payment;
import com.gpstore.enums.PaymentMethod;
import com.gpstore.enums.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Collection;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(Long orderId);

    /**
     * Every payment for a page of orders, in ONE query.
     *
     * The order list has to report the PAYMENT's status rather than the
     * order's own stale copy of it (see OrderService.toOrderResponse), and
     * asking per row would put a query behind every line of the admin's
     * orders screen. Fifty orders is fifty round trips; this is one.
     */
    List<Payment> findByOrderIdIn(Collection<Long> orderIds);

    /**
     * Row-locking variant, for any path that intends to CHANGE this
     * payment's status rather than just read it. Order/payment/inventory
     * state is reachable from several directions (cancellation, UPI
     * confirmation, COD completion, refunds, the expiry sweep), so the
     * status has to be re-read under the lock rather than trusted from an
     * earlier unlocked read - otherwise two paths both see PENDING and both
     * act on it.
     *
     * Callers must already hold the ORDER row lock before calling this. The
     * project-wide ordering is ORDER -> PAYMENT -> INVENTORY, and taking
     * these in a different order between two paths is what would deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.order.id = :orderId")
    Optional<Payment> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    Optional<Payment> findByTransactionId(String transactionId);

    /**
     * The gateway's order id is how a webhook finds its way back to one of
     * our payments - it is the only identifier both sides agree on before a
     * payment exists.
     *
     * FOR UPDATE, because this is called from the webhook handler, which
     * races the client callback for the same row. Both paths take the lock,
     * so the second one re-reads the state the first one committed rather
     * than acting on what it saw before.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.providerOrderId = :providerOrderId")
    Optional<Payment> findByProviderOrderIdForUpdate(@Param("providerOrderId") String providerOrderId);

    Optional<Payment> findByProviderOrderId(String providerOrderId);

    /**
     * Used to auto-expire UPI payments nobody ever confirmed - see
     * PaymentService.expireStalePendingUpiPayments.
     *
     * Pageable, not an unbounded List: this is a scheduled sweep over
     * "everything abandoned since the last successful run", so its result
     * size grows with traffic and with any gap in the scheduler running at
     * all (a deploy, an outage, a ShedLock hold). Loading every stale
     * payment into one List and mutating them in a single transaction is
     * exactly the shape that works fine in testing and then stalls the
     * whole job - and holds locks across it - on the first bad day.
     */
    @Query("select p from Payment p where p.paymentStatus = :status "
            + "and p.paymentMethod = :method and p.paymentDate < :cutoff order by p.id")
    List<Payment> findStaleForExpiry(@Param("status") PaymentStatus status,
                                     @Param("method") PaymentMethod method,
                                     @Param("cutoff") LocalDateTime cutoff,
                                     Pageable pageable);

    /**
     * Refunds we asked the provider for and never saw land.
     *
     * WHY THIS QUERY HAS TO EXIST. A refund settles through a bank over
     * days, so REFUND_PENDING is a normal state to sit in for a while. The
     * failure it hides is the one that never leaves: Cashfree accepted the
     * refund, the webhook that would have confirmed it was lost or never
     * sent, and the row stays REFUND_PENDING for ever. Nothing else in the
     * system asks about those, so without this the shop's own books say a
     * customer is owed money that may in fact already be back in their
     * account - or, worse, may not be, with nobody looking.
     *
     * refundedAt IS NULL rather than a status check: the status is what a
     * settlement writes, so keying on the timestamp asks the narrower and
     * more honest question of whether anything ever confirmed the landing.
     * refundId IS NOT NULL keeps cash refunds out - they never went to a
     * provider and there is nobody to ask.
     *
     * Oldest first, and Pageable rather than a List, for the same reason as
     * findStaleForExpiry above: after any gap in the scheduler this set is
     * as large as the gap, and one batch per run keeps a bad day from
     * turning into a long transaction holding locks across every refund the
     * shop has outstanding.
     */
    @Query("select p from Payment p "
            + "where p.refundId is not null and p.refundedAt is null "
            + "and (p.refundRequestedAt is null or p.refundRequestedAt < :askedBefore) "
            + "order by p.refundRequestedAt asc nulls first, p.id asc")
    List<Payment> findRefundsAwaitingProvider(@Param("askedBefore") LocalDateTime askedBefore,
                                              Pageable pageable);

    /** How many refunds are in the air right now, for the metric. */
    @Query("select count(p) from Payment p where p.refundId is not null and p.refundedAt is null")
    long countRefundsAwaitingProvider();

    /**
     * The oldest in-flight refund's request time, or null when none is in
     * flight. A count alone cannot tell a busy afternoon from a refund that
     * has been stuck since Tuesday; this is the half that can.
     */
    @Query("select min(p.refundRequestedAt) from Payment p "
            + "where p.refundId is not null and p.refundedAt is null")
    LocalDateTime oldestRefundAwaitingProvider();

    /**
     * How the money actually arrived, for one shop, in a window.
     *
     * ROOTED ON Payment, WHICH IS SHOP-OWNED - so this one genuinely is
     * filtered and needs no shop parameter. The window is taken from the
     * ORDER's date rather than the payment's: a COD payment is created at
     * checkout and settled when the rider hands the money over, so keying on
     * paymentDate would put an order and its cash in different weeks and the
     * statement would not reconcile with the revenue figure beside it.
     *
     * THE COD SPLIT IS SEPARATE FROM THE AMOUNT. A rider can be handed part
     * of a bill in cash and part by UPI on the doorstep, so the payment
     * carries both; summing the amount alone answers "how much was collected"
     * but not "how much of it is cash somebody is now carrying", which is the
     * number a shopkeeper reconciles at the end of the day.
     */
    @Query("""
           select p.paymentMethod as method,
                  p.paymentStatus as status,
                  coalesce(sum(p.amount), 0) as amount,
                  coalesce(sum(p.codCashAmount), 0) as cash,
                  coalesce(sum(p.codUpiAmount), 0) as upi,
                  count(p) as paymentCount
           from Payment p
           where p.order.orderDate >= :from and p.order.orderDate <= :to
             and p.order.orderStatus <> com.gpstore.enums.OrderStatus.CANCELLED
           group by p.paymentMethod, p.paymentStatus
           """)
    List<Object[]> collectionsBetween(@Param("from") java.time.LocalDateTime from,
                                      @Param("to") java.time.LocalDateTime to);

}
