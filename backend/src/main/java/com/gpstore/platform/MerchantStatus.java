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

    /** No coming back from these. A re-application is a new merchant record. */
    public boolean isTerminal() {
        return this == REJECTED || this == REMOVED;
    }

    /**
     * Which statuses this one may move to.
     *
     * WRITTEN OUT RATHER THAN "ANY TO ANY". A lifecycle where every status can
     * become every other is not a lifecycle, it is a free-text field with an
     * enum's clothes on: nothing stops a REMOVED merchant being quietly set
     * back to ACTIVE, and nothing records that APPROVED was ever reached.
     *
     * SUSPENDED GOES BACK TO ACTIVE on purpose (see the note on SUSPENDED). A
     * suspension that could only be undone by re-onboarding would cost a
     * shopkeeper their entire history over a fixable problem.
     */
    public java.util.Set<MerchantStatus> allowedNext() {
        return switch (this) {
            case APPLICATION -> java.util.EnumSet.of(PENDING_REVIEW, REJECTED, REMOVED);
            case PENDING_REVIEW -> java.util.EnumSet.of(
                    VERIFICATION_REQUIRED, APPROVED, REJECTED, REMOVED);
            case VERIFICATION_REQUIRED -> java.util.EnumSet.of(
                    PENDING_REVIEW, APPROVED, REJECTED, REMOVED);
            case APPROVED -> java.util.EnumSet.of(ACTIVE, SUSPENDED, REMOVED);
            case ACTIVE -> java.util.EnumSet.of(SUSPENDED, REMOVED);
            case SUSPENDED -> java.util.EnumSet.of(ACTIVE, REMOVED);
            // Terminal. A rejected applicant who re-applies gets a new record,
            // so the first decision and its reason stay readable.
            case REJECTED, REMOVED -> java.util.EnumSet.noneOf(MerchantStatus.class);
        };
    }

    public boolean canMoveTo(MerchantStatus next) {
        return next != null && allowedNext().contains(next);
    }
}
