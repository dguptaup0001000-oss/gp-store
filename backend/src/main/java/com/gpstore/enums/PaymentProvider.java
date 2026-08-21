package com.gpstore.enums;

/**
 * Which gateway a payment went through, or NONE for the two settlement
 * routes that never touch one.
 *
 * An enum rather than a free string because it is stored on every payment
 * row and read by reconciliation: "cashfree" versus "Cashfree" versus
 * "CASHFREE" in the same column is how a reconciliation report quietly
 * under-counts.
 *
 * NONE is not a placeholder for "not integrated yet" - COD and direct UPI
 * are real settlement methods this shop uses and will keep using, and their
 * payments genuinely have no provider. Recording NULL/NONE for them is
 * accurate, not incomplete.
 */
public enum PaymentProvider {

    NONE,
    CASHFREE
}
