package com.gpstore.billing;

/**
 * What a ledger row IS.
 *
 * <p>THE SIGN CONVENTION, STATED ONCE: a POSITIVE amount means the merchant
 * owes GP-STORE, a NEGATIVE amount means GP-STORE owes the merchant. Every
 * type below declares which way it points, so no caller has to remember and
 * no reader has to infer it from an example.
 */
public enum LedgerEntryType {

    /** P in §6: the weekly platform fee. Merchant owes it. */
    PLATFORM_FEE(true),

    /** C in §6: commission on successful product sales. Merchant owes it. */
    COMMISSION(true),

    /**
     * The weekly fee acting as a credit against commission (§6).
     *
     * <p>This is the row that makes the ledger show max(P, C) rather than
     * assert it. Fee + commission - min(fee, commission) is max(fee,
     * commission), arrived at by arithmetic anybody can check on the
     * statement instead of a total they have to believe.
     */
    COMMISSION_CREDIT(false),

    /** A completed order was later refunded, so its commission comes back (§8). */
    COMMISSION_REVERSAL(false),

    /** No completed orders in the week, so the fee is returned (§7). */
    FEE_REFUND_NO_ORDERS(false),

    /** GP-STORE refunded a customer on the merchant's behalf and recovers it (§15 of Part 3). */
    INTERVENTION_RECOVERY(true),

    /** A human decision, in either direction, with a reason attached. */
    ADJUSTMENT(true),

    /** Contested and therefore not collected while it is looked at. */
    DISPUTE_HOLD(false),

    /** The hold is lifted. */
    DISPUTE_RELEASE(true);

    private final boolean merchantOwes;

    LedgerEntryType(boolean merchantOwes) {
        this.merchantOwes = merchantOwes;
    }

    /**
     * Which way this type points by nature.
     *
     * <p>ADJUSTMENT is the exception and is declared positive only so it has a
     * default; a human adjustment may legitimately be either, and the caller
     * states the signed amount.
     */
    public boolean merchantOwesByNature() {
        return merchantOwes;
    }
}
