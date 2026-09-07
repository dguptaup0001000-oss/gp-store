package com.gpstore.payment.collection;

/**
 * WHOSE ACCOUNT AN ONLINE PAYMENT LANDS IN.
 *
 * <p>THE OPEN BUSINESS DECISION, named so it stops being invisible. GP-STORE
 * runs one Cashfree account today, so every shop's online money arrives in
 * the platform's account and is owed onward - which makes GP-STORE a payment
 * aggregator, with everything that implies about settlement and about who is
 * answerable when a customer's money is somewhere neither they nor the
 * shopkeeper can see it.
 *
 * <p>The alternative - each merchant collecting into their own account - is a
 * different product, a different Cashfree integration and a different
 * regulatory position, and it is not a decision this codebase gets to make by
 * itself. §17 is explicit about that: build the boundary, do not invent the
 * provider-specific implementation.
 *
 * <p>So this enum exists to make the question askable in code, per shop, with
 * exactly one implemented answer today. Nothing here changes how a rupee
 * moves; it changes what the application can say about where it went.
 */
public enum PaymentCollectionModel {

    /**
     * GP-STORE's gateway account receives the money and owes it onward.
     *
     * <p>What every shop does today, and the reason ShopEarnings reports
     * "awaiting collection" rather than "in your account".
     */
    PLATFORM_COLLECTS,

    /**
     * The merchant's own gateway account receives it directly.
     *
     * <p>NOT IMPLEMENTED, and deliberately not stubbed. Configuring it is a
     * startup failure rather than a silent fall-back to the platform account
     * (see PaymentCollection): a flag that says merchants collect while every
     * rupee still lands in one account is worse than no flag, because
     * everything downstream - the merchant's earnings screen, the settlement
     * report, the tax position - would be quietly wrong.
     */
    MERCHANT_COLLECTS
}
