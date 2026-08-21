package com.gpstore.enums;

public enum PaymentStatus {

    PENDING,
    SUCCESS,
    FAILED,
    COD_PENDING,
    COD_RECEIVED,
    REFUND_PENDING,
    REFUNDED,
    CANCELLED,

    /**
     * The gateway session ran out before the customer completed it.
     *
     * Distinct from CANCELLED, which means somebody decided to stop -
     * the customer backing out, or an admin cancelling the order. EXPIRED
     * means nobody decided anything and the clock ran out, which is the
     * common outcome when an app is killed mid-payment. Collapsing the two
     * would make "how many customers abandon at payment" unanswerable, and
     * that number is the one worth watching after a gateway goes live.
     *
     * Terminal for the session, NOT for the order: the customer can retry,
     * which creates a new gateway order against the same internal one.
     */
    EXPIRED

}