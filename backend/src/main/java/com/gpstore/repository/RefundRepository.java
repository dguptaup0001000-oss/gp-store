package com.gpstore.repository;

import com.gpstore.entity.Refund;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * NOT TOUCHED BY ACCOUNT DELETION, deliberately.
 *
 * Every other table keyed to a customer is deleted by name in
 * CustomerService.deleteOwnAccount. These rows are not, because they are the
 * shop's financial record of money it actually moved - a payment id, an
 * amount and a provider reference, with no personal data of their own. A
 * deleted account anonymises the person; it does not erase the books. The
 * customer detail screen never reads this table.
 */
public interface RefundRepository extends JpaRepository<Refund, Long> {

    /**
     * How much of this payment has been sent back, or is on its way.
     *
     * FAILED IS EXCLUDED, and that is the whole point of the WHERE clause: a
     * refund the provider refused did not move money, so it must not reduce
     * what the shop is still able to refund. Counting it would leave a
     * customer permanently short by the amount of a failure that was nobody's
     * fault.
     *
     * PENDING IS INCLUDED. A refund in flight has not landed, but the money
     * is committed - treating it as available would let a second refund be
     * sent for money already promised, and the pair would overdraw the
     * payment the moment both settled.
     *
     * COALESCE because a payment with no refunds has no rows, and a null
     * total would propagate into the subtraction below it as null rather than
     * as "nothing has gone back yet".
     */
    @Query("""
           select coalesce(sum(r.amount), 0) from Refund r
           where r.payment.id = :paymentId
             and r.status <> com.gpstore.entity.Refund$Status.FAILED
           """)
    BigDecimal committedFor(@Param("paymentId") Long paymentId);

    /** Only what actually landed - what the shop can say it has paid back. */
    @Query("""
           select coalesce(sum(r.amount), 0) from Refund r
           where r.payment.id = :paymentId
             and r.status = com.gpstore.entity.Refund$Status.SUCCEEDED
           """)
    BigDecimal settledFor(@Param("paymentId") Long paymentId);

    /** The next sequence number for this payment. 1 when it has never been refunded. */
    @Query("select coalesce(max(r.sequenceNo), 0) from Refund r where r.payment.id = :paymentId")
    int highestSequenceFor(@Param("paymentId") Long paymentId);

    @Query("select r from Refund r where r.payment.id = :paymentId order by r.sequenceNo")
    List<Refund> forPayment(@Param("paymentId") Long paymentId);

    Optional<Refund> findByRefundId(String refundId);

    /**
     * Gateway refunds asked for and not yet landed, oldest first.
     *
     * The reconciliation's own query. Bounded by a Pageable rather than
     * returning everything, because the job processes a batch per run and a
     * provider outage could otherwise make this select the whole backlog into
     * memory at once.
     */
    @Query("""
           select r from Refund r
           where r.status = com.gpstore.entity.Refund$Status.PENDING
             and r.channel = com.gpstore.entity.Refund$Channel.GATEWAY
             and r.requestedAt is not null
             and r.requestedAt < :olderThan
           order by r.requestedAt asc
           """)
    List<Refund> awaitingProvider(@Param("olderThan") LocalDateTime olderThan, Pageable pageable);

    long countByPaymentId(Long paymentId);
}
