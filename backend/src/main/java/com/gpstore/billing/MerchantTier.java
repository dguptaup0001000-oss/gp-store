package com.gpstore.billing;

/**
 * Which band of billing plan a merchant is on.
 *
 * <p>THREE NAMES AND NO NUMBERS. Part 4 §5 is explicit that the commercial
 * amounts are not decided, so the amounts live in billing_plan rows that
 * Platform Admin configures and that are versioned by date - not here, and
 * not anywhere else in the code. Searching this repository for a rupee figure
 * attached to a tier should find nothing, and BillingHasNoHardCodedAmountsTest
 * is what keeps that true.
 *
 * <p>A MERCHANT WITH NO TIER IS NOT BILLED. That is every merchant today, and
 * it is the honest default: a tier assigned by a migration is a merchant put
 * on a price list nobody showed them.
 */
public enum MerchantTier {
    SMALL,
    MEDIUM,
    LARGE
}
