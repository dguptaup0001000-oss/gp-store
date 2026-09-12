package com.gpstore.enums;

/**
 * Who moved an order, or ended it.
 *
 * <p>WHY THIS IS NOT "the authenticated role". A role says what somebody is
 * allowed to do; this says who actually did a particular thing, and the two
 * come apart exactly where it matters. A platform administrator cancelling an
 * order on a merchant's behalf during a dispute is PLATFORM, not MERCHANT -
 * and §12's fault question, §10's charge question and the audit trail all
 * read differently depending on which it was.
 *
 * <p>SYSTEM is for the paths with nobody behind them: the stale-payment sweep
 * that fails an unconfirmed order, and any future automatic completion.
 * Recording those as the customer or the shop would put a person's name on a
 * decision a scheduler made.
 */
public enum OrderActor {

    /** The person who placed it. */
    CUSTOMER,

    /** The shop, through the merchant app. */
    MERCHANT,

    /** A delivery worker of that shop, on the round. */
    WORKER,

    /** GP-STORE itself - a dispute, an intervention, an investigation. */
    PLATFORM,

    /** A scheduled job. Nobody was there. */
    SYSTEM
}
