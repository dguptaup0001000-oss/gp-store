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
    CANCELLED,

    /**
     * The shop refused the order before taking it on.
     *
     * REACHABLE ONLY FROM PENDING_CONFIRMATION, and that restriction is the
     * whole reason it is a separate state from CANCELLED. §2 says a merchant
     * who has ACCEPTED an order owes it; a "reject" available after
     * acceptance would be a back door out of that promise with a friendlier
     * word on it. Before acceptance, refusing is legitimate and the customer
     * should be told plainly which of the two happened.
     */
    REJECTED,

    /**
     * The round came back with the packet still in it.
     *
     * NOT TERMINAL, deliberately. Nobody was home, the address was wrong, the
     * road was shut - and the usual outcome is that the shop sends it out
     * again tomorrow rather than that the order is over. Collapsing this into
     * CANCELLED would refund and restock an order the shop is still holding.
     */
    DELIVERY_FAILED,

    /**
     * Delivered, settled, and past returning: §1's last step.
     *
     * DISTINCT FROM DELIVERED because the two answer different questions. A
     * DELIVERED order has reached the customer and may still become a return,
     * a replacement or a refund; a COMPLETED one is finished. Nothing moves an
     * order here automatically yet - the window after which an order stops
     * being returnable is a policy nobody has chosen, and inventing one would
     * be closing customers' claims on a number this code made up.
     */
    COMPLETED

}
