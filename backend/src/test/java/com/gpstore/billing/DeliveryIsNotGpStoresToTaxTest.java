package com.gpstore.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GP-STORE TAKES NO COMMISSION ON THE DELIVERY CHARGE.
 *
 * <p>THE BUSINESS RULE. Delivery belongs to the merchant: they decide who
 * drives, how far, how often, and what it costs. The money a customer pays for
 * delivery is money the shop spends getting the packet to the door - it is not
 * a sale, and GP-STORE does not take a share of it. A commission levied on it
 * would charge a shopkeeper for the diesel.
 *
 * <p>HOW IT IS ENFORCED, and why it was worth writing this down. The rule
 * lives in two lines of SQL inside {@link MerchantSales} - a subtraction in
 * the weekly query and the same subtraction for a single order - and until
 * now nothing in the test suite mentioned either. A change that dropped the
 * subtraction would start charging every merchant commission on money they
 * never kept, would be a single-token diff, and would pass every existing
 * test.
 *
 * <p>WHY A SOURCE ASSERTION RATHER THAN A SCENARIO. What is being protected is
 * the SHAPE of the base: gross minus delivery. A scenario test would need a
 * commission rate to multiply it by, and there is no approved rate - the
 * amounts are a founder decision and this suite is forbidden from inventing
 * one (see {@code NoInventedCommercialAmountTest}). The subtraction can be
 * asserted without ever naming a percentage.
 */
class DeliveryIsNotGpStoresToTaxTest {

    private static final Path SALES =
            Path.of("src/main/java/com/gpstore/billing/MerchantSales.java");

    private static String source() throws IOException {
        return Files.readString(SALES);
    }

    @Test
    @DisplayName("one order's commissionable base is its total minus its delivery fee")
    void aSingleOrderExcludesDelivery() throws IOException {
        String sql = source();

        assertTrue(
                sql.contains("COALESCE(o.total_amount, 0) - COALESCE(o.delivery_fee, 0)"),
                "commissionableForOrder must subtract the delivery fee from the order total. "
                        + "Without it GP-STORE would take a commission on the merchant's own "
                        + "delivery cost, which is the one thing the delivery model says it "
                        + "never does.");
    }

    @Test
    @DisplayName("the weekly base subtracts delivery before anything is charged")
    void theWeeklyRollupExcludesDelivery() throws IOException {
        String java = source();

        assertTrue(
                java.contains("orderGross.subtract(orderDelivery)"),
                "The weekly commissionable total must subtract each order's delivery fee. "
                        + "The single-order path and the weekly path have to agree, or a "
                        + "merchant's invoice and their per-order refund adjustment would "
                        + "disagree about the same order.");
    }

    @Test
    @DisplayName("and the delivery fee is still reported, so the merchant can see it")
    void deliveryIsStillVisible() throws IOException {
        String java = source();

        // EXCLUDED FROM THE BASE IS NOT THE SAME AS HIDDEN. A shopkeeper
        // reading their week needs to see what the deliveries came to, or the
        // gross and the commissionable figure look like an unexplained gap.
        assertTrue(java.contains("delivery = delivery.add(orderDelivery)"),
                "The weekly roll-up must still total the delivery fees it excluded, so the "
                        + "merchant can see why gross and commissionable differ.");
    }

    @Test
    @DisplayName("no commission rate is named anywhere in the sales query")
    void thereIsStillNoRateHere() throws IOException {
        String java = source();

        // The companion half of the rule: this file computes a BASE and never
        // a charge. The rate is configuration a founder has not set, and a
        // literal appearing here would be one invented in passing.
        assertFalse(java.matches("(?s).*new BigDecimal\\(\"(?!0|100)[0-9.]+\"\\).*"),
                "MerchantSales must contain no commercial amount. It computes what is "
                        + "chargeable, not what is charged.");
    }
}
