package com.gpstore.enums;

public enum OrderStatus {

    PENDING_CONFIRMATION,
    CONFIRMED,
    PACKING,

    /**
     * The shop has finished packing and a worker has taken responsibility for
     * the order by scanning its QR code.
     *
     * THIS IS NOT "out for delivery" and must never be described to a customer
     * as such. A GP-STORE worker is a shop employee who also delivers; scanning
     * a packed order records WHO is accountable for it, which happens while the
     * order is still on the counter. The customer is told exactly one thing at
     * this point - that their order is packed - because anything more would be
     * a promise about a journey that has not started.
     *
     * READY_TO_DISPATCH below means the same thing operationally and predates
     * this; it stays valid so existing orders and the admin status dropdown
     * keep working, but new scans write PACKED.
     */
    PACKED,

    READY_TO_DISPATCH,
    OUT_FOR_DELIVERY,
    DELIVERED,
    CANCELLED

}
