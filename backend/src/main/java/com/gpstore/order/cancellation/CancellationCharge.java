package com.gpstore.order.cancellation;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * What cancelling this order right now would cost, and why.
 *
 * <p>THE "WHY" IS NOT DECORATION. §10 requires that the customer SEE the
 * charge before confirming, and a bare number on a confirm dialog is not
 * something anybody can agree to - "₹12" answers nothing, "2% of ₹600
 * because the shop has already started packing" is a thing a person can
 * accept or decline. So the working travels with the amount, and the same
 * record is what the quote endpoint returns and what the cancellation
 * itself applies.
 *
 * @param amount           what will be taken; never null, ZERO means free
 * @param withinFreeWindow whether §9's window is still open
 * @param freeUntil        when that window closes, or null once it has
 * @param percentApplied   the rate used, or null when nothing was charged
 * @param chargeableBase   the amount the rate was applied to
 * @param explanation      in plain words, for the customer to read
 */
public record CancellationCharge(
        BigDecimal amount,
        boolean withinFreeWindow,
        LocalDateTime freeUntil,
        BigDecimal percentApplied,
        BigDecimal chargeableBase,
        String explanation) {

    public boolean isFree() {
        return amount == null || amount.signum() == 0;
    }

    static CancellationCharge free(boolean withinWindow, LocalDateTime freeUntil, String why) {
        return new CancellationCharge(
                BigDecimal.ZERO, withinWindow, freeUntil, null, BigDecimal.ZERO, why);
    }
}
