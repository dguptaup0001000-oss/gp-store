package com.gpstore.store;

/**
 * WHEN an order is to be delivered, decided by the server at order creation.
 *
 * <p>Stored as a string on the order (see V33). This says nothing about what
 * the customer PAYS - delivery pricing is calculated by the existing
 * DeliveryPricingCalculator and is deliberately untouched by scheduling.
 */
public enum DeliveryType {

    /** Ordered inside the delivery day; existing same-day rules apply. */
    SAME_DAY,

    /**
     * Ordered after the evening cutoff or before the morning opening, so it
     * goes out in the next 09:00 window. The order is normal in every other
     * respect - the shop is open for business all night, just not for vans.
     */
    NEXT_MORNING,

    /**
     * Scheduled by hand to a specific window, overriding what the clock would
     * have chosen. Nothing writes this yet; it exists so a future
     * "deliver on Thursday" feature has a name that already means something
     * rather than needing a migration to add one.
     */
    MANUAL_SCHEDULED
}
