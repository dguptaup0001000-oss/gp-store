package com.gpstore.governance;

/**
 * WHY a merchant was warned or suspended (§2).
 *
 * <p>A CLOSED LIST, for the same reason the moderation list is closed: a
 * free-text justification is one nobody can audit, compare or appeal against.
 * "Suspended for conduct" is not a thing a shopkeeper can answer.
 *
 * <p>SOME THINGS CANNOT WAIT FOR A WARNING. The ladder in §2 exists so that
 * the platform cannot end a livelihood on a whim, not so that a merchant
 * selling counterfeit medicine gets a polite note first.
 * {@link #allowsImmediateSuspension} is where that judgement lives, in one
 * readable list rather than scattered through a service - and every value
 * that carries it is one where letting trade continue would hurt a customer
 * or break the law, never one that merely costs GP-STORE money.
 */
public enum GovernanceReason {

    /** Accepting orders and then cancelling them (§2, §12). */
    REPEATED_CANCELLATIONS(false),

    /** Orders accepted, never delivered, and not refunded. */
    UNDELIVERED_ORDERS(false),

    /** Refunds the merchant owes and has not paid (§15). */
    UNPAID_REFUNDS(false),

    /** Platform fees the merchant owes. */
    UNPAID_PLATFORM_DUES(false),

    /** Ratings or reviews the merchant manufactured (§22). */
    RATING_MANIPULATION(false),

    /** Listing prices that are not the prices charged (§3). */
    MISLEADING_PRICING(false),

    /** Abuse aimed at customers, riders or staff. */
    ABUSIVE_CONDUCT(false),

    /** Counterfeit or unsafe goods. */
    COUNTERFEIT_OR_UNSAFE_GOODS(true),

    /** A licence expired, revoked, or never held. */
    REGULATORY_NON_COMPLIANCE(true),

    /** An order from an authority the platform has to obey. */
    LEGAL_ORDER(true);

    private final boolean severe;

    GovernanceReason(boolean severe) {
        this.severe = severe;
    }

    /**
     * Whether the ladder may be skipped for this.
     *
     * <p>TRUE FOR THREE, and all three are cases where the harm is to
     * somebody other than GP-STORE. Unpaid fees are NOT on the list on
     * purpose: a platform that can suspend a shop for owing it money without
     * a warning first is a platform whose governance ladder is a collection
     * mechanism.
     */
    public boolean allowsImmediateSuspension() {
        return severe;
    }
}
