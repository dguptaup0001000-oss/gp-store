package com.gpstore.store;

/**
 * What the shop is doing right now, derived from the clock - never stored.
 *
 * <p>SEPARATE FROM WHETHER ORDERS ARE ACCEPTED. A shop in NIGHT mode is still
 * taking orders; it is only the van that has stopped. Conflating the two is
 * how a night-time customer gets told the shop is closed when it is not.
 * See StoreStatus for the three concepts held apart.
 */
public enum StoreMode {

    /** Inside 09:00-21:00: the existing same-day rules apply. */
    SAME_DAY,

    /** Outside it: orders are taken for the next 09:00 window. */
    NIGHT
}
