package com.gpstore.order.cancellation;

import com.gpstore.entity.Order;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.service.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The debts a COD cancellation leaves behind, and how they are cleared (§11).
 *
 * <p>WHY A DEBT AT ALL. A prepaid cancellation can have the fee held back out
 * of the refund - the money is already in the system. A COD cancellation has
 * no money anywhere: nothing was collected, and there is nothing to deduct
 * from. §11 answers this by carrying the amount forward to a future order,
 * which is the only honest option left; the alternatives are to forget the
 * charge (and let anyone cancel COD orders all day for free) or to invent a
 * collection mechanism that does not exist.
 *
 * <p>SHOWN, NEVER SPRUNG. A debt the customer discovers at the checkout of an
 * unrelated order weeks later is indistinguishable from a bug. The list is
 * readable by the customer at any time, and the amount is quoted at the
 * cancellation that creates it.
 *
 * <p>RECORDING IS IDEMPOTENT, and by the database rather than by a check: a
 * unique index on order_id is what actually stops a retried cancellation
 * billing somebody twice for changing their mind once.
 */
@Service
public class CancellationDues {

    private final CustomerCancellationDuesRepository dues;
    private final AuditLogService auditLog;

    public CancellationDues(CustomerCancellationDuesRepository dues, AuditLogService auditLog) {
        this.dues = dues;
        this.auditLog = auditLog;
    }

    /**
     * Writes down what a cancellation could not collect.
     *
     * <p>Called from inside the cancellation's own transaction, so the debt
     * commits with the cancellation or not at all - a charge that survived a
     * rolled-back cancellation would be money owed for an order that was
     * never cancelled.
     *
     * @return the debt, or empty when there was nothing to record
     */
    @Transactional
    public Optional<CustomerCancellationDue> record(Order order, BigDecimal amount, String reason) {
        if (order == null || amount == null || amount.signum() <= 0) {
            return Optional.empty();
        }
        if (order.getCustomer() == null || order.getCustomer().getId() == null) {
            return Optional.empty();
        }

        // The index below is the real guard; this read only spares the common
        // retry an exception it would otherwise have to recover from.
        Optional<CustomerCancellationDue> already = dues.findByOrderId(order.getId());
        if (already.isPresent()) {
            return already;
        }

        CustomerCancellationDue due = CustomerCancellationDue.of(
                order.getCustomer().getId(), order.getId(), amount, reason);
        // Explicitly the ORDER's shop, not the scope's. They are the same on
        // the ordinary path; saying so here means a cancellation made from a
        // wider scope still books the debt to the shop that is owed it.
        due.setShopId(order.getShopId());

        CustomerCancellationDue saved = dues.save(due);

        auditLog.log("CANCELLATION_DUE_RECORDED", "Order", order.getId(),
                "customer owes " + amount.toPlainString()
                        + (reason == null ? "" : " (" + reason + ")"));
        return Optional.of(saved);
    }

    /** What this customer still owes the shop in scope. */
    @Transactional(readOnly = true)
    public List<CustomerCancellationDue> outstandingFor(Long customerId) {
        if (customerId == null) {
            return List.of();
        }
        return dues.findByCustomerIdAndStatusOrderByCreatedAtAsc(customerId, DueStatus.OUTSTANDING);
    }

    /** Everything, settled and waived included - the customer's own record. */
    @Transactional(readOnly = true)
    public List<CustomerCancellationDue> historyFor(Long customerId) {
        if (customerId == null) {
            return List.of();
        }
        return dues.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }

    /** The sum of what is outstanding, for adding to a later order (§11). */
    @Transactional(readOnly = true)
    public BigDecimal totalOutstandingFor(Long customerId) {
        return outstandingFor(customerId).stream()
                .map(CustomerCancellationDue::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Marks what was collected on a later order.
     *
     * <p>Takes the whole outstanding set rather than one row: the customer
     * pays a total, and leaving one of three debts open because the caller
     * passed one id is the kind of half-applied payment that is very hard to
     * explain afterwards.
     */
    @Transactional
    public List<CustomerCancellationDue> settleOn(Long customerId, Long settlingOrderId) {
        List<CustomerCancellationDue> outstanding = outstandingFor(customerId);
        if (outstanding.isEmpty()) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        for (CustomerCancellationDue due : outstanding) {
            due.setStatus(DueStatus.SETTLED);
            due.setSettledOrderId(settlingOrderId);
            due.setSettledAt(now);
        }
        List<CustomerCancellationDue> saved = dues.saveAll(outstanding);
        auditLog.log("CANCELLATION_DUES_SETTLED", "Order", settlingOrderId,
                saved.size() + " outstanding cancellation charge(s) collected");
        return saved;
    }

    /**
     * The shop writes one off.
     *
     * <p>Somebody's name goes on it. A debt that can vanish without an actor
     * is a debt that can vanish for the wrong customer.
     */
    @Transactional
    public CustomerCancellationDue waive(Long dueId, String actor) {
        CustomerCancellationDue due = dues.findById(dueId)
                .orElseThrow(() -> new ResourceNotFoundException("No such cancellation charge"));
        if (due.getStatus() != DueStatus.OUTSTANDING) {
            return due;
        }
        due.setStatus(DueStatus.WAIVED);
        due.setWaivedBy(actor);
        due.setSettledAt(LocalDateTime.now());
        CustomerCancellationDue saved = dues.save(due);
        auditLog.log("CANCELLATION_DUE_WAIVED", "Order", due.getOrderId(),
                "waived " + due.getAmount().toPlainString() + " by " + actor);
        return saved;
    }
}
