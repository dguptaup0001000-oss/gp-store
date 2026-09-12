package com.gpstore.payment.collection;

/**
 * WHOSE ACCOUNT AN ONLINE PAYMENT LANDS IN.
 *
 * <p>THE BUSINESS RULE IS DECIDED AND WRITTEN DOWN. Decision W1
 * ({@code docs/architecture/03-decision-w1-money-model.md}, 2026-09-05):
 * <b>a merchant's product proceeds belong to that merchant.</b> GP-STORE is a
 * technology marketplace, not a payment aggregator - it is not to operate a
 * pooled account holding everybody's money and redistributing it afterwards.
 * That is a requirement, not a preference, and it is not something this
 * codebase gets to renegotiate.
 *
 * <p>WHAT IS NOT DECIDED is how to honour it compliantly: which payment
 * provider, how merchants are onboarded and their KYC verified, by what
 * mechanism funds reach the merchant, how fees are borne, how refunds are
 * funded and reversed (W1 §2 turns a refund into a three-party obligation the
 * platform tracks rather than executes), and the regulatory position that
 * follows. Those are provider, legal and commercial decisions. §17 is explicit
 * that this codebase builds the boundary and does not invent the
 * provider-specific implementation behind it.
 *
 * <p>So the two values below are not two equally valid options to choose
 * between. One is the requirement; the other is where the code is today.
 */
public enum PaymentCollectionModel {

    /**
     * GP-STORE's own gateway account receives the money and owes it onward.
     *
     * <p><b>INTERIM AND NON-CONFORMING.</b> This is what every shop does
     * today, and it is the thing W1 says must not be how GP-STORE operates:
     * one account holds every merchant's product money and redistributes it
     * later. It is a gap against a recorded decision - not an open question,
     * and not a model anybody chose. It is recorded honestly rather than dressed up -
     * {@code ShopEarnings} says "awaiting collection" and not "in your
     * account", {@code payments.collection_model} stamps every new row with
     * who actually collected it, and the application logs a warning at startup
     * naming the gap.
     *
     * <p>It remains the only implemented value because implementing the other
     * one requires decisions nobody has made yet - see the class comment. It
     * is a state to leave, not a model to settle on.
     */
    PLATFORM_COLLECTS,

    /**
     * The merchant's own account receives their product proceeds directly.
     *
     * <p><b>W1'S ANSWER, and deliberately not stubbed.</b> Configuring
     * it is a startup failure rather than a silent fall-back (see
     * {@link PaymentCollection}): a flag that says merchants collect while
     * every rupee still lands in one account is worse than no flag, because
     * the merchant's earnings screen, the settlement report and the tax
     * position would all be quietly wrong.
     *
     * <p>Implementing it is not a code change alone. It needs the provider,
     * the onboarding and KYC flow, the settlement mechanism, the fee and
     * refund treatment, and a compliance position - none of which this file
     * may assume.
     */
    MERCHANT_COLLECTS;

    /** Whether this value satisfies the rule that a merchant's money is theirs. */
    public boolean meetsTheMerchantOwnsTheirMoneyRule() {
        return this == MERCHANT_COLLECTS;
    }
}
