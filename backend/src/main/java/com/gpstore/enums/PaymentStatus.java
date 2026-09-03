package com.gpstore.enums;

public enum PaymentStatus {

    PENDING,
    SUCCESS,
    FAILED,
    COD_PENDING,
    COD_RECEIVED,
    REFUND_PENDING,
    REFUNDED,

    /**
     * Some of the customer's money has gone back, and some has not.
     *
     * WHY THIS HAD TO EXIST. REFUNDED used to mean "a refund happened", which
     * was the same thing as "all of it came back" only while a payment could
     * carry one refund. Once a shop can send back 200 of 500 and then another
     * 100 later, stamping the first one REFUNDED would both misreport the
     * order and lock the remaining 300 away - the refund path refuses to
     * refund a payment it believes is already refunded.
     *
     * Terminal for nothing: more can still go back, up to what is left.
     */
    PARTIALLY_REFUNDED,
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