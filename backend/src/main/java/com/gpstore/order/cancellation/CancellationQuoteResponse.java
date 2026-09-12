package com.gpstore.order.cancellation;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The answer to "what happens if I cancel this?", before anything happens.
 *
 * <p>§10 FORBIDS CHARGING SILENTLY, and a charge is silent if the only way to
 * discover it is to incur it. This is the shape the app puts on the confirm
 * dialog: the amount, the working behind it, and - the part that is easy to
 * leave out - HOW it will be taken. "₹12 will be deducted from your refund"
 * and "₹12 will be added to your next order from this shop" are different
 * promises, and a customer who was shown the first and got the second was
 * misled even though the number matched.
 *
 * @param cancellable whether this order can be cancelled at all right now
 * @param blockedReason why not, when it cannot; null when it can
 * @param collection how the charge is taken - see {@link Collection}
 */
public record CancellationQuoteResponse(
        Long orderId,
        String orderNumber,
        boolean cancellable,
        String blockedReason,
        BigDecimal amount,
        boolean free,
        boolean withinFreeWindow,
        LocalDateTime freeUntil,
        BigDecimal percentApplied,
        BigDecimal chargeableBase,
        String explanation,
        Collection collection) {

    /** Where the money for a cancellation charge actually comes from. */
    public enum Collection {

        /** Nothing is being charged. */
        NONE,

        /** Held back out of the refund of a payment already made. */
        DEDUCTED_FROM_REFUND,

        /** §11: nothing was collected, so it is owed on a later order here. */
        ADDED_TO_A_FUTURE_ORDER
    }

    public static CancellationQuoteResponse of(
            Long orderId, String orderNumber, boolean cancellable, String blockedReason,
            CancellationCharge charge, boolean cashOnDelivery) {

        Collection how = charge.isFree()
                ? Collection.NONE
                : (cashOnDelivery ? Collection.ADDED_TO_A_FUTURE_ORDER
                                  : Collection.DEDUCTED_FROM_REFUND);

        return new CancellationQuoteResponse(
                orderId, orderNumber, cancellable, blockedReason,
                charge.amount(), charge.isFree(), charge.withinFreeWindow(), charge.freeUntil(),
                charge.percentApplied(), charge.chargeableBase(), charge.explanation(), how);
    }
}
