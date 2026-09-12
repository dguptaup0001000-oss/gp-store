package com.gpstore.order.cancellation;

import com.gpstore.entity.StoreOperationsSettings;

import java.math.BigDecimal;

/**
 * One shop's answer to "what does it cost to change your mind here".
 *
 * <p>A VALUE OBJECT, read off the settings row and then left alone. The row
 * is editable by the shopkeeper at any moment, and a quote shown to a
 * customer has to be the terms as they stood when it was quoted - not as
 * they stand by the time the customer presses confirm.
 *
 * @param freeSeconds     how long after placing an order it costs nothing to
 *                        cancel. Five by default, because §9 says the
 *                        five-second countdown must remain - made a setting
 *                        rather than a constant so a generous shop can widen
 *                        it, and so the server, not a screen's timer, is what
 *                        enforces it
 * @param feePercent      what the shop charges after that window, or null for
 *                        nothing. Null and zero mean the same thing to a
 *                        customer and are both the default
 * @param chargesDelivery whether the fee is also taken on what the customer
 *                        paid for delivery. Off unless a shop turns it on
 */
public record CancellationTerms(int freeSeconds, BigDecimal feePercent, boolean chargesDelivery) {

    /**
     * §9's countdown, as a number the rest of the system can read.
     *
     * <p>This is the ONE place the five lives. It is a default for a column,
     * not a rule: a shop that has set its own window has already overwritten
     * it, and nothing downstream compares against this constant.
     */
    public static final int DEFAULT_FREE_SECONDS = 5;

    /** What a shop that has configured nothing gets: a free window, no fee. */
    public static CancellationTerms unset() {
        return new CancellationTerms(DEFAULT_FREE_SECONDS, null, false);
    }

    /**
     * Reads a settings row, tolerating one that predates the columns.
     *
     * <p>A null anywhere here reads as "not configured", which is always the
     * harmless answer: no fee, and the free window still there.
     */
    public static CancellationTerms of(StoreOperationsSettings settings) {
        if (settings == null) {
            return unset();
        }
        Integer free = settings.getFreeCancellationSeconds();
        return new CancellationTerms(
                free == null ? DEFAULT_FREE_SECONDS : free,
                settings.getCancellationFeePercent(),
                Boolean.TRUE.equals(settings.getCancellationChargesDelivery()));
    }

    /** Whether this shop charges anything at all once the free window closes. */
    public boolean chargesAFee() {
        return feePercent != null && feePercent.signum() > 0;
    }
}
