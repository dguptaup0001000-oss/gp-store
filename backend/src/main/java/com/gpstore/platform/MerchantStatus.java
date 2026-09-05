package com.gpstore.platform;

/**
 * Where a merchant sits in the onboarding and enforcement lifecycle.
 *
 * NOTHING IS EVER DELETED (§91). A merchant who leaves, is rejected or is
 * removed keeps their row and their history - orders, payments and invoices
 * are financial records that outlive the relationship, and an audit trail
 * with a hole in it is not an audit trail. REMOVED is a status, not a
 * DELETE.
 *
 * Every transition is written to the audit log with an actor and a reason
 * code, because a marketplace that can drop a shopkeeper without a recorded
 * reason is exactly what §21 forbids.
 */
public enum MerchantStatus {

    /** Applied, nothing checked yet. */
    APPLICATION,

    /** With the platform team. */
    PENDING_REVIEW,

    /** Reviewed, and something is missing - documents, a licence, a bank proof. */
    VERIFICATION_REQUIRED,

    /** Checks passed. Not yet trading: shops still have to be set up. */
    APPROVED,

    /** Trading. The only status whose shops may accept orders. */
    ACTIVE,

    /**
     * Temporarily stopped. Reversible on purpose - a suspension that can only
     * be undone by deleting and re-onboarding would cost the merchant their
     * whole history for a fixable problem.
     */
    SUSPENDED,

    /** Application refused. Kept, so a re-application can be seen in context. */
    REJECTED,

    /** Gone, by their choice or ours. Records retained. */
    REMOVED;

    /** Whether shops under this merchant may trade at all. */
    public boolean canTrade() {
        return this == ACTIVE;
    }
}
