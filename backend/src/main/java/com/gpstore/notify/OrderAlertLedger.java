package com.gpstore.notify;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claims the right to announce an order, exactly once.
 *
 * <p>A SEPARATE BEAN WITH A SEPARATE TRANSACTION, for two reasons that both bite
 * in practice. A unique-constraint violation marks the surrounding transaction
 * rollback-only, so claiming inside the caller's transaction would turn "this
 * order was already announced" - a normal, expected outcome - into a failure of
 * whatever else that transaction was doing. And Spring's proxying means a
 * {@code REQUIRES_NEW} method called from within the same class would not get a
 * new transaction at all, which is the classic way this kind of guard silently
 * stops guarding.
 *
 * <p>CHECK-THEN-INSERT WOULD NOT DO. Two deliveries arriving together both read
 * "not yet sent" and both proceed. Here the database decides: the second INSERT
 * loses on {@code ux_order_alert_once} and this returns false.
 */
@Service
public class OrderAlertLedger {

    private final OrderAlertSentRepository sent;

    public OrderAlertLedger(OrderAlertSentRepository sent) {
        this.sent = sent;
    }

    /**
     * @return true if THIS caller won the right to announce; false if somebody
     *         already has.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long orderId, String kind, Long shopId) {
        if (orderId == null) {
            return false;
        }
        // A cheap read first - not for correctness, which the index provides,
        // but so the ordinary repeat case does not churn a failed INSERT.
        if (sent.existsByOrderIdAndKind(orderId, kind)) {
            return false;
        }
        try {
            OrderAlertSent record = new OrderAlertSent();
            record.setOrderId(orderId);
            record.setKind(kind);
            record.setShopId(shopId);
            sent.saveAndFlush(record);
            return true;
        } catch (DataIntegrityViolationException alreadyClaimed) {
            // The other delivery got there between the read above and this
            // insert. That is the race this table exists for, and losing it is
            // success, not an error.
            return false;
        }
    }

    /** Records how many installs the alert actually reached. Never fails the send. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRecipients(Long orderId, String kind, int recipients) {
        sent.findByOrderIdAndKind(orderId, kind).ifPresent(record -> {
            record.setRecipients(recipients);
            sent.save(record);
        });
    }
}
