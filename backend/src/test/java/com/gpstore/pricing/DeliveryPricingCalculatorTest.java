package com.gpstore.pricing;

import com.gpstore.entity.DeliveryPricingSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pricing rule, checked against the brief example by example.
 *
 * NO SPRING AND NO DATABASE, which is the point of having split the
 * arithmetic out: every rule and every edge case is a plain function call, so
 * this file runs in milliseconds and can afford to be exhaustive about the
 * boundaries - which is where a tiered price actually goes wrong.
 */
class DeliveryPricingCalculatorTest {

    private DeliveryPricingSettings v1() {
        DeliveryPricingSettings s = new DeliveryPricingSettings();
        s.normalise();
        return s;
    }

    private DeliveryQuote quote(BigDecimal km, BigDecimal kg, BigDecimal profit) {
        return DeliveryPricingCalculator.quote(v1(), km, true, kg, profit, List.of());
    }

    private static BigDecimal rs(String v) {
        return new BigDecimal(v);
    }

    // ---------------------------------------------------------- distance

    @Nested
    @DisplayName("Distance charge")
    class Distance {

        /**
         * Every worked example from the brief, in order.
         *
         * The boundaries matter more than they look: in a village strung along
         * one road, "exactly 1 km" and "exactly 2 km" are not rare cases. 1.0
         * is the cheap tier and 1.2 is not; 2.0 is the middle tier and 2.1 is
         * not.
         */
        @ParameterizedTest(name = "{0} km costs Rs {1}")
        @CsvSource({
                "0.0,  5",
                "0.5,  5",
                "1.0,  5",
                "1.2, 10",
                "1.9, 10",
                "2.0, 10",
                "2.1, 15",
                "2.8, 15",
                "3.0, 15",
                "3.1, 20",
                "5.5, 30",
                "8.0, 40",
        })
        void matchesTheBrief(String km, String expected) {
            assertEquals(0, rs(expected).compareTo(
                            DeliveryPricingCalculator.distanceCharge(v1(), rs(km))),
                    km + " km should cost Rs " + expected);
        }

        @Test
        @DisplayName("a fraction of a kilometre past a tier still costs a whole one")
        void roundsUpBeyondTierTwo() {
            // 2.01 km is one metre past the tier and costs a full extra
            // kilometre. That is what the brief says, and it is the right
            // trade: a rule a shopkeeper can explain at the counter beats one
            // that is proportional to the metre.
            assertEquals(0, rs("15").compareTo(
                    DeliveryPricingCalculator.distanceCharge(v1(), rs("2.01"))));
        }

        @Test
        @DisplayName("zero distance is the first tier, not free")
        void zeroDistanceStillCosts() {
            // A customer standing in the shop still has an order carried to
            // them. Free would also be what a broken coordinate looks like.
            assertEquals(0, rs("5.00").compareTo(quote(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO).distanceCharge()));
        }

        @Test
        @DisplayName("an unknown distance is charged as the furthest, not the nearest")
        void unknownDistanceDoesNotBecomeCheap() {
            // THE FAILURE THIS PREVENTS: an address with no coordinates is not
            // a nearby address. Defaulting to tier 1 would hand the cheapest
            // delivery in the shop to precisely the orders nobody can verify.
            DeliveryQuote q = quote(null, BigDecimal.ZERO, BigDecimal.ZERO);

            assertTrue(q.distanceCharge().compareTo(rs("40")) > 0,
                    "an unmeasurable distance must not be priced like a short one; was "
                            + q.distanceCharge());
            assertNull(q.distanceKm());
            assertTrue(q.hasWarnings());
            assertTrue(String.join(" ", q.warnings()).toLowerCase().contains("distance"),
                    "the shop has to be told which order this happened on: " + q.warnings());
        }

        @Test
        @DisplayName("a negative distance is treated as unknown rather than as a discount")
        void negativeDistanceIsNotADiscount() {
            DeliveryQuote q = quote(rs("-5"), BigDecimal.ZERO, BigDecimal.ZERO);
            assertTrue(q.distanceCharge().signum() > 0);
            assertTrue(q.hasWarnings());
        }
    }

    // ------------------------------------------------------------ weight

    @Nested
    @DisplayName("Weight surcharge")
    class Weight {

        @ParameterizedTest(name = "{0} kg costs Rs {1}")
        @CsvSource({
                "0,   0",
                "5,   0",
                "10,  0",
                "10.5, 1",
                "11,  2",
                "15, 10",
                "18, 16",
                "20, 20",
                "30, 20",
                "50, 20",
                "500, 20",
        })
        void matchesTheBrief(String kg, String expected) {
            assertEquals(0, rs(expected).compareTo(
                            DeliveryPricingCalculator.weightCharge(v1(), rs(kg))),
                    kg + " kg should cost Rs " + expected);
        }

        @Test
        @DisplayName("exactly the free allowance is still free")
        void tenKilosIsFree() {
            // The boundary the formula gets wrong if > becomes >=.
            assertEquals(0, BigDecimal.ZERO.compareTo(
                    DeliveryPricingCalculator.weightCharge(v1(), rs("10.000"))));
        }

        @Test
        @DisplayName("the cap holds however heavy the order gets")
        void surchargeIsCapped() {
            // Without the cap a 200 kg sack order would carry a Rs 380
            // surcharge, which is not a delivery charge, it is a refusal.
            assertEquals(0, rs("20").compareTo(
                    DeliveryPricingCalculator.weightCharge(v1(), rs("1000"))));
        }

        @Test
        @DisplayName("a negative weight cannot become a credit")
        void negativeWeightIsZero() {
            assertEquals(0, BigDecimal.ZERO.compareTo(quote(rs("1"), rs("-50"), BigDecimal.ZERO).weightCharge()));
        }
    }

    // ------------------------------------------------------------ margin

    @Nested
    @DisplayName("Free delivery and the margin subsidy")
    class Margin {

        @Test
        @DisplayName("the brief's worked example: 6 km, 18 kg, Rs 46 normal")
        void normalChargeIsDistancePlusWeight() {
            DeliveryQuote q = quote(rs("6"), rs("18"), BigDecimal.ZERO);

            assertEquals(0, rs("30.00").compareTo(q.distanceCharge()));
            assertEquals(0, rs("16.00").compareTo(q.weightCharge()));
            assertEquals(0, rs("46.00").compareTo(q.normalCharge()));
        }

        @Test
        @DisplayName("enough margin makes it free")
        void freeWhenProfitCoversThreeTimes() {
            // Normal Rs 30 needs Rs 90 of margin.
            DeliveryQuote q = quote(rs("5.5"), BigDecimal.ZERO, rs("90"));

            assertEquals(0, rs("30.00").compareTo(q.normalCharge()));
            assertEquals(0, rs("90.00").compareTo(q.freeDeliveryRequiredProfit()));
            assertTrue(q.freeDelivery());
            assertEquals(0, BigDecimal.ZERO.compareTo(q.finalCharge()));
            assertEquals("FREE DELIVERY", q.customerLabel());
        }

        @Test
        @DisplayName("one rupee short of the threshold is not free")
        void justBelowTheThresholdIsNotFree() {
            // The boundary a >= becomes a > on.
            DeliveryQuote q = quote(rs("5.5"), BigDecimal.ZERO, rs("89.99"));
            assertFalse(q.freeDelivery());
        }

        @Test
        @DisplayName("the brief's reduced-charge example: Rs 40 normal, Rs 90 profit, Rs 30 charged")
        void partialMarginReducesTheCharge() {
            DeliveryQuote q = quote(rs("8"), BigDecimal.ZERO, rs("90"));

            assertEquals(0, rs("40.00").compareTo(q.normalCharge()));
            assertEquals(0, rs("120.00").compareTo(q.freeDeliveryRequiredProfit()));
            assertFalse(q.freeDelivery());
            assertEquals(0, rs("30.00").compareTo(q.finalCharge()));
            assertEquals(0, rs("10.00").compareTo(q.subsidy()));
        }

        @Test
        @DisplayName("the brief's admin example: Rs 46 normal, Rs 100 profit, Rs 38 charged")
        void adminExampleReproduces() {
            // The whole §10 worked example, end to end: 5.5 km, 18 kg, Rs 100
            // of margin.
            DeliveryQuote q = quote(rs("5.5"), rs("18"), rs("100"));

            assertEquals(0, rs("30.00").compareTo(q.distanceCharge()));
            assertEquals(0, rs("16.00").compareTo(q.weightCharge()));
            assertEquals(0, rs("46.00").compareTo(q.normalCharge()));
            assertEquals(0, rs("138.00").compareTo(q.freeDeliveryRequiredProfit()));
            assertFalse(q.freeDelivery());
            assertEquals(0, rs("38.00").compareTo(q.finalCharge()));
            assertEquals("Delivery ₹38", q.customerLabel());
        }

        @Test
        @DisplayName("no margin at all means the full normal charge and never more")
        void zeroProfitPaysNormalAndNoMore() {
            // The formula's own arithmetic gives 3 x normal here. The cap is
            // what turns that into the normal charge, and it is the reason
            // the rule is safe rather than something bolted on afterwards.
            DeliveryQuote q = quote(rs("8"), rs("20"), BigDecimal.ZERO);

            assertEquals(0, q.normalCharge().compareTo(q.finalCharge()),
                    "a zero-margin order pays the normal charge, not three times it");
            assertEquals(0, BigDecimal.ZERO.compareTo(q.subsidy()));
        }

        @Test
        @DisplayName("a loss-making order still never pays more than the normal charge")
        void negativeProfitIsCapped() {
            // Selling below cost is a real thing shops do. The customer must
            // not be billed for it.
            DeliveryQuote q = quote(rs("8"), rs("20"), rs("-500"));

            assertEquals(0, q.normalCharge().compareTo(q.finalCharge()));
            assertTrue(q.finalCharge().signum() >= 0);
        }

        @Test
        @DisplayName("the charge is never negative, whatever the margin")
        void neverNegative() {
            for (String profit : new String[]{"0", "1", "45", "137", "138", "10000"}) {
                DeliveryQuote q = quote(rs("5.5"), rs("18"), rs(profit));
                assertTrue(q.finalCharge().signum() >= 0, "profit " + profit);
                assertTrue(q.finalCharge().compareTo(q.normalCharge()) <= 0, "profit " + profit);
            }
        }

        @Test
        @DisplayName("a big order with a thin margin does NOT get free delivery")
        void orderValueIsIrrelevant() {
            // §8 of the brief, stated as an assertion. The calculator is never
            // told the order's value at all - only its margin - so this is
            // true by construction, and this test is what stops somebody
            // "helpfully" adding a value threshold later.
            DeliveryQuote thinMargin = quote(rs("5.5"), rs("18"), rs("10"));
            DeliveryQuote fatMargin = quote(rs("5.5"), rs("18"), rs("200"));

            assertFalse(thinMargin.freeDelivery(),
                    "a high-value, low-margin order must not get free delivery");
            assertTrue(fatMargin.freeDelivery(),
                    "a low-value, high-margin order should");
        }
    }

    // --------------------------------------------------------- settings

    @Nested
    @DisplayName("Configuration")
    class Configurable {

        @Test
        @DisplayName("changing the numbers changes the price, with no code change")
        void everythingIsTunable() {
            // The whole point of §11. If this test ever needs a code change to
            // pass, the values stopped being configuration.
            DeliveryPricingSettings s = new DeliveryPricingSettings();
            s.setDistanceTier1Charge(rs("8"));
            s.setDistanceTier1MaxKm(rs("2"));
            s.setDistanceTier2Charge(rs("15"));
            s.setDistanceTier2MaxKm(rs("5"));
            s.setAdditionalKmCharge(rs("7"));
            s.setFreeWeightKg(rs("5"));
            s.setAdditionalWeightPerKg(rs("3"));
            s.setMaximumWeightSurcharge(rs("30"));
            s.setFreeDeliveryMultiplier(rs("2"));
            s.normalise();

            DeliveryQuote q = DeliveryPricingCalculator.quote(
                    s, rs("6"), true, rs("10"), BigDecimal.ZERO, List.of());

            assertEquals(0, rs("22.00").compareTo(q.distanceCharge()), "15 + 7 x ceil(6-5)");
            assertEquals(0, rs("15.00").compareTo(q.weightCharge()), "(10-5) x 3");
            assertEquals(0, rs("37.00").compareTo(q.normalCharge()));
            assertEquals(0, rs("74.00").compareTo(q.freeDeliveryRequiredProfit()), "2x, not 3x");
        }

        @Test
        @DisplayName("a half-filled settings form cannot produce a negative price")
        void nullsAndNegativesFallBackToV1() {
            // An admin blanks a field, or pastes a minus sign. This runs on
            // the checkout path, where there is nowhere sensible to throw - so
            // every value is bounded instead of trusted.
            DeliveryPricingSettings broken = new DeliveryPricingSettings();
            broken.setDistanceTier1Charge(null);
            broken.setAdditionalKmCharge(rs("-100"));
            broken.setFreeWeightKg(null);
            broken.setMaximumWeightSurcharge(rs("-5"));
            broken.setFreeDeliveryMultiplier(null);

            DeliveryQuote q = DeliveryPricingCalculator.quote(
                    broken, rs("8"), true, rs("30"), BigDecimal.ZERO, List.of());

            assertTrue(q.finalCharge().signum() >= 0);
            assertTrue(q.normalCharge().signum() >= 0);
            assertEquals(0, rs("5.00").compareTo(
                    DeliveryPricingCalculator.distanceCharge(broken, rs("0.5"))));
        }

        @Test
        @DisplayName("tiers that overlap are repaired rather than allowed to invert")
        void tierTwoMustEndAfterTierOne() {
            // Otherwise "above tier 1 and up to tier 2" is an empty band, and
            // everything past tier 1 falls into per-km arithmetic with a
            // negative remainder.
            DeliveryPricingSettings s = new DeliveryPricingSettings();
            s.setDistanceTier1MaxKm(rs("5"));
            s.setDistanceTier2MaxKm(rs("2"));
            s.normalise();

            assertTrue(s.getDistanceTier2MaxKm().compareTo(s.getDistanceTier1MaxKm()) > 0);
            assertTrue(DeliveryPricingCalculator.distanceCharge(s, rs("10")).signum() > 0);
        }

        @Test
        @DisplayName("null settings do not take checkout down")
        void nullSettingsUseDefaults() {
            DeliveryQuote q = DeliveryPricingCalculator.quote(
                    null, rs("5.5"), true, rs("18"), rs("100"), List.of());
            assertEquals(0, rs("38.00").compareTo(q.finalCharge()));
        }
    }

    // ------------------------------------------------------------ output

    @Test
    @DisplayName("the customer label says one of exactly two things")
    void customerLabelIsSimple() {
        // §9: no profit arithmetic reaches the app.
        assertEquals("FREE DELIVERY", quote(rs("1"), BigDecimal.ZERO, rs("1000")).customerLabel());
        assertEquals("Delivery ₹5", quote(rs("1"), BigDecimal.ZERO, BigDecimal.ZERO).customerLabel());
    }

    @Test
    @DisplayName("warnings handed in are carried through to the shop")
    void warningsAreNotSwallowed() {
        DeliveryQuote q = DeliveryPricingCalculator.quote(
                v1(), rs("3"), true, rs("5"), rs("10"),
                List.of("No cost price on SKU-123"));

        assertTrue(q.hasWarnings());
        assertTrue(q.warnings().contains("No cost price on SKU-123"));
    }

    @Test
    @DisplayName("subsidy and final charge always add back up to the normal charge")
    void subsidyPlusChargeReconciles() {
        // An accounting identity rather than a rule: if these two ever stop
        // summing to the normal charge, the admin breakdown is lying about
        // where the money went.
        for (String profit : new String[]{"0", "20", "45", "137", "138", "500"}) {
            DeliveryQuote q = quote(rs("5.5"), rs("18"), rs(profit));
            assertEquals(0, q.normalCharge().compareTo(q.subsidy().add(q.finalCharge())),
                    "profit " + profit + ": subsidy " + q.subsidy() + " + charge "
                            + q.finalCharge() + " != normal " + q.normalCharge());
        }
    }
}
