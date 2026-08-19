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
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(Long orderId);

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

}
