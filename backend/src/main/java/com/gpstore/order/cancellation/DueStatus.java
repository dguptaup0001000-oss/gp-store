package com.gpstore.order.cancellation;

/**
 * What became of a cancellation charge a COD order could not collect.
 *
 * <p>OUTSTANDING is the only state that costs the customer anything. The
 * other two exist so that a debt is closed by a recorded decision rather
 * than by a row quietly disappearing.
 */
public enum DueStatus {

    /** Owed to this shop, and added to the customer's next order here (§11). */
    OUTSTANDING,

    /** Collected on a later order, which is named on the row. */
    SETTLED,

    /** Written off by the shop. Somebody's name is on it. */
    WAIVED
}
