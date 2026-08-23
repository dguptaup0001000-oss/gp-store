package com.gpstore.pricing;

import com.gpstore.entity.DeliveryPricingSettings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The delivery price, as arithmetic and nothing else.
 *
 * NO SPRING, NO DATABASE, NO CLOCK. Everything it needs arrives as arguments,
 * which is what makes every rule in the brief - and every edge case in §12 -
 * a plain unit test rather than an integration test with fixtures. The parts
 * that need a database (what the order weighs, what it cost) live in
 * DeliveryPricingService and hand their answers here.
 *
 * THE SHAPE OF THE RULE, in one place so it can be checked against the brief:
 *
 *   distance:  0 to tier1Max      -> tier1Charge
 *              tier1Max to tier2Max -> tier2Charge
 *              beyond              -> tier2Charge + perKm x ceil(km - tier2Max)
 *
 *   weight:    min(max(0, kg - freeKg) x perKg, cap)
 *
 *   normal:    distance + weight
 *
 *   free when  profit >= multiplier x normal
 *   otherwise  charge = min(normal, max(0, multiplier x normal - profit))
 *
 * WHY THE "OTHERWISE" BRANCH CANNOT MISBEHAVE, since it is the one piece of
 * this that looks like it might. With zero profit it yields
 * min(normal, 3 x normal) = normal - the customer pays the full charge and no
 * more. With negative profit the subtraction grows the number, and the min
 * still pins it at normal. So the two guards the brief asks for (never above
 * normal, never below zero) are not patches on the formula; they are what
 * makes it total.
 */
public final class DeliveryPricingCalculator {

    /** Money. Two decimals, half-up, because that is how a rupee is written. */
    private static final int MONEY_SCALE = 2;

    private DeliveryPricingCalculator() {
    }

    /**
     * @param distanceKm         road kilometres, already adjusted; null when unknown
     * @param distanceEstimated  true when distanceKm is a straight line
     * @param totalWeightKg      the whole order's weight; null treated as zero
     * @param orderProfit        margin available to subsidise delivery; null treated as zero
     * @param warnings           anything already known about the inputs, carried through
     */
    public static DeliveryQuote quote(DeliveryPricingSettings rawSettings,
                                      BigDecimal distanceKm,
                                      boolean distanceEstimated,
                                      BigDecimal totalWeightKg,
                                      BigDecimal orderProfit,
                                      List<String> warnings) {

        DeliveryPricingSettings s = rawSettings == null ? new DeliveryPricingSettings() : rawSettings;
        s.normalise();

        List<String> notes = new ArrayList<>(warnings == null ? List.of() : warnings);

        BigDecimal km = distanceKm;
        if (km == null || km.signum() < 0) {
            // A distance we could not compute must not become a free delivery.
            // The furthest tier is the honest fallback: it is what the shop
            // would charge for the longest trip it accepts, and it is the only
            // choice that cannot silently under-price a delivery nobody
            // measured. Loudly noted, because the real fix is coordinates.
            notes.add("Delivery distance could not be determined for this address. "
                    + "The maximum distance charge was applied. Check the address coordinates.");
            km = null;
        }

        BigDecimal weight = totalWeightKg == null || totalWeightKg.signum() < 0
                ? BigDecimal.ZERO : totalWeightKg;

        BigDecimal profit = orderProfit == null ? BigDecimal.ZERO : orderProfit;

        BigDecimal distanceCharge = km == null
                ? unknownDistanceCharge(s)
                : distanceCharge(s, km);
        BigDecimal weightCharge = weightCharge(s, weight);

        BigDecimal normal = money(distanceCharge.add(weightCharge));
        BigDecimal required = money(normal.multiply(s.getFreeDeliveryMultiplier()));

        boolean free = profit.compareTo(required) >= 0;

        BigDecimal finalCharge;
        if (free) {
            finalCharge = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        } else {
            BigDecimal reduced = required.subtract(profit);
            // max(0, ...) then min(normal, ...). Both guards, in that order.
            if (reduced.signum() < 0) {
                reduced = BigDecimal.ZERO;
            }
            finalCharge = money(reduced.min(normal));
        }

        BigDecimal subsidy = money(normal.subtract(finalCharge));

        return new DeliveryQuote(
                km == null ? null : money3(km),
                distanceEstimated,
                money3(weight),
                money(distanceCharge),
                money(weightCharge),
                normal,
                money(profit),
                required,
                free,
                subsidy,
                finalCharge,
                List.copyOf(notes));
    }

    /**
     * 0 to tier1Max flat, tier1Max to tier2Max flat, then per whole extra km.
     *
     * THE BOUNDARIES ARE INCLUSIVE at the top of each tier, which is what the
     * brief's examples say: 1.0 km is ₹5 and 1.2 km is ₹10; 2.0 km is ₹10 and
     * 2.1 km is ₹15. Getting that backwards would mis-price every order that
     * lands exactly on a kilometre, which in a village laid out along one road
     * is not the rare case it sounds like.
     */
    static BigDecimal distanceCharge(DeliveryPricingSettings s, BigDecimal km) {
        if (km.compareTo(s.getDistanceTier1MaxKm()) <= 0) {
            return s.getDistanceTier1Charge();
        }
        if (km.compareTo(s.getDistanceTier2MaxKm()) <= 0) {
            return s.getDistanceTier2Charge();
        }

        // Rounded UP, per the brief: 2.1 km and 2.8 km both cost one extra
        // kilometre, 3.1 km costs two. Simple to explain at a counter, which
        // is worth more here than proportionality.
        BigDecimal beyond = km.subtract(s.getDistanceTier2MaxKm());
        BigDecimal wholeKm = beyond.setScale(0, RoundingMode.CEILING);

        return s.getDistanceTier2Charge().add(wholeKm.multiply(s.getAdditionalKmCharge()));
    }

    /**
     * What to charge when the distance is unknown.
     *
     * Deliberately NOT zero and NOT tier 1. An address whose coordinates are
     * missing is not a nearby address; treating it as one hands out the
     * cheapest delivery in the shop to precisely the orders nobody can verify.
     * This charges what the furthest serviceable address would cost, which is
     * the only choice that cannot lose money, and the quote says loudly that
     * it happened.
     */
    private static BigDecimal unknownDistanceCharge(DeliveryPricingSettings s) {
        return distanceCharge(s, s.getDistanceTier2MaxKm().add(new BigDecimal("1000")));
    }

    /** min(max(0, kg - free) x perKg, cap). */
    static BigDecimal weightCharge(DeliveryPricingSettings s, BigDecimal kg) {
        BigDecimal chargeable = kg.subtract(s.getFreeWeightKg());
        if (chargeable.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal charge = chargeable.multiply(s.getAdditionalWeightPerKg());
        return charge.min(s.getMaximumWeightSurcharge());
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal money3(BigDecimal value) {
        return value.setScale(3, RoundingMode.HALF_UP);
    }
}
