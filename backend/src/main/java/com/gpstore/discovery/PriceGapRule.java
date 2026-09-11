package com.gpstore.discovery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * When a farther seller is worth the distance (Part 2 §7).
 *
 * <p>THE RULE: a farther shop qualifies when the local price is at least
 * 1.25× the farther one. Below that, the saving is not worth sending a
 * customer out of their neighbourhood for, and a marketplace that routes
 * every order to whoever is a rupee cheaper stops being local-first.
 *
 * <p>ON THE FINAL COST, NOT THE PRODUCT PRICE, and §7 is explicit about it
 * with a worked example:
 *
 * <pre>
 *   local:   ₹100 product + ₹20 delivery = ₹120
 *   farther: ₹ 80 product + ₹10 delivery = ₹ 90
 * </pre>
 *
 * <p>Compared on product price, 100 ÷ 80 = 1.25 and the farther shop scrapes
 * in. Compared on final cost — which is the number the customer actually
 * pays — 120 ÷ 90 = 1.33 and it wins comfortably. The two can also disagree
 * in the other direction, which is the dangerous case: a cheap product with
 * an expensive delivery looks like a bargain on the product line and is not
 * one. So this method takes finals, and the parameter names say so.
 *
 * <p>THE MULTIPLIER IS CONFIGURATION. 1.25 is the rule as it stands, and a
 * marketplace in a denser or sparser place may want a different one. Nothing
 * in the code compares against a literal 1.25 — it reads this bean.
 */
@Component
public class PriceGapRule {

    /** The rule as §7 states it today. A default, not a constant. */
    static final BigDecimal DEFAULT_MULTIPLIER = new BigDecimal("1.25");

    private final BigDecimal multiplier;

    public PriceGapRule(
            @Value("${marketplace.price-gap.multiplier:}") String configured) {
        this.multiplier = parse(configured);
    }

    private static BigDecimal parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MULTIPLIER;
        }
        try {
            BigDecimal parsed = new BigDecimal(raw.trim());
            // Below 1 the rule would qualify a DEARER farther shop, which is
            // not a relaxed rule - it is the opposite rule. A typo must not
            // be able to invert it.
            return parsed.compareTo(BigDecimal.ONE) < 0 ? DEFAULT_MULTIPLIER : parsed;
        } catch (NumberFormatException notANumber) {
            return DEFAULT_MULTIPLIER;
        }
    }

    public BigDecimal multiplier() {
        return multiplier;
    }

    /**
     * Whether the saving justifies the distance.
     *
     * @param localFinal   what the nearby shop would cost in total
     * @param fartherFinal what the farther shop would cost in total
     */
    public boolean qualifies(BigDecimal localFinal, BigDecimal fartherFinal) {
        if (localFinal == null || fartherFinal == null || fartherFinal.signum() <= 0) {
            return false;
        }
        return localFinal.compareTo(fartherFinal.multiply(multiplier)) >= 0;
    }

    /** How much cheaper the farther shop is, for showing beside the badge. */
    public BigDecimal saving(BigDecimal localFinal, BigDecimal fartherFinal) {
        if (localFinal == null || fartherFinal == null) {
            return BigDecimal.ZERO;
        }
        return localFinal.subtract(fartherFinal).max(BigDecimal.ZERO);
    }
}
