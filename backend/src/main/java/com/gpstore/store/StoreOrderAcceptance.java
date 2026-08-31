package com.gpstore.store;

/**
 * The owner's manual override on order acceptance.
 *
 * <p>THREE STATES, NOT A BOOLEAN, and the difference matters. A boolean
 * cannot express "follow the schedule": once someone flips it off and on
 * again, the shop is pinned to whatever they last chose and the schedule
 * silently stops being consulted. AUTO means "the schedule decides", and it
 * is the default a shop returns to.
 */
public enum StoreOrderAcceptance {

    /** The schedule decides. The normal state. */
    AUTO,

    /**
     * Forced open, whatever the schedule says. Lets the shop take orders
     * during a period the schedule would refuse, without editing hours.
     */
    ON,

    /**
     * Forced closed to NEW ORDERS. Browsing, search, and existing orders are
     * untouched - see StoreStatus.
     */
    OFF
}
