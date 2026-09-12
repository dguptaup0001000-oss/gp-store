package com.gpstore.discovery;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ranking for BEST DEAL mode (Part 2 §5).
 *
 * <p>§5 NAMES SIX INPUTS: final customer cost, distance, delivery estimate,
 * availability, merchant reliability, product relevance. And it names one
 * non-input, in a sentence that is the whole ethical content of this class:
 *
 * <blockquote>Do NOT rank merchants merely because they pay GP-STORE more
 * money.</blockquote>
 *
 * <p>THE STRONGEST FORM OF THAT PROMISE IS AN ABSENCE, NOT A CHECK. This
 * class takes {@link ShopOffer}s. A ShopOffer has no commission field, no
 * tier, no ledger balance, no billing plan — there is no route by which what
 * a merchant pays could reach this method, so there is no branch here that
 * could be quietly changed to use it. {@code BestDealIsNotForSaleTest}
 * asserts that by reflection over the record's components, so adding such a
 * field later fails a test rather than passing review.
 *
 * <p>WHY A LEXICOGRAPHIC SORT AND NOT A WEIGHTED SCORE. A score needs
 * weights, weights are invented numbers, and an invented number that decides
 * whose shop appears first is exactly the kind of thing that gets adjusted
 * later for reasons nobody writes down. The ordering below is a sequence of
 * plain questions a customer would recognise, in the order they would ask
 * them, and every step is explainable in one sentence to the merchant it
 * ranked second.
 */
@Component
public class BestDeal {

    /**
     * Cheapest first, with the things that make "cheapest" real applied first.
     *
     * <ol>
     *   <li><b>Can you actually buy it</b> — listed, in stock, the shop
     *       delivers here and is taking orders. §5's "availability", and it
     *       comes first because a ₹70 offer you cannot accept is not a
     *       better deal than a ₹90 one you can.</li>
     *   <li><b>Is the delivery charge known</b> — an unquotable delivery is
     *       not free delivery, and must not win on a number nobody gave.</li>
     *   <li><b>Final customer cost</b> — §5's first named factor, and the
     *       one the mode is named after.</li>
     *   <li><b>Distance</b> — the tie-break, and the local-first thumb on the
     *       scale: same price, nearer shop.</li>
     *   <li><b>Reliability</b> — a TRUSTED shop, then a better-rated one.
     *       Last, so it can never outweigh a real price difference.</li>
     * </ol>
     *
     * <p>Product relevance is the caller's: this ranks offers for ONE
     * variant, so every offer is equally relevant by construction. Ranking
     * across different products would need relevance and would be a different
     * method, not a sixth clause here.
     */
    public List<ShopOffer> rank(List<ShopOffer> offers) {
        if (offers == null || offers.isEmpty()) {
            return List.of();
        }
        List<ShopOffer> ranked = new ArrayList<>(offers);
        ranked.sort(Comparator
                .comparing(ShopOffer::isBuyableNow, Comparator.reverseOrder())
                .thenComparing(ShopOffer::deliveryChargeKnown, Comparator.reverseOrder())
                .thenComparing(ShopOffer::finalOrMax)
                .thenComparing(o -> o.distanceKm() == null ? Double.MAX_VALUE : o.distanceKm())
                .thenComparing(ShopOffer::trusted, Comparator.reverseOrder())
                .thenComparing(ShopOffer::ratingAverage, Comparator.reverseOrder())
                .thenComparing(o -> o.shopId() == null ? Long.MAX_VALUE : o.shopId()));
        return List.copyOf(ranked);
    }

    /**
     * The nearest offer a customer could accept — the local baseline the 25%
     * rule is measured against (§7).
     *
     * <p>NEAREST BUYABLE, not cheapest. "Local price" in §7 means what the
     * shop round the corner charges, not what the best shop in the search
     * radius charges; measuring against the cheapest would make the rule
     * compare a farther shop with another farther shop.
     */
    public ShopOffer nearestBuyable(List<ShopOffer> offers) {
        if (offers == null) {
            return null;
        }
        return offers.stream()
                .filter(ShopOffer::isBuyableNow)
                .min(Comparator.comparingDouble(
                        o -> o.distanceKm() == null ? Double.MAX_VALUE : o.distanceKm()))
                .orElse(null);
    }

    /** The lowest final cost among offers a customer could actually accept. */
    public BigDecimal cheapestFinal(List<ShopOffer> offers) {
        if (offers == null) {
            return null;
        }
        return offers.stream()
                .filter(ShopOffer::isBuyableNow)
                .map(ShopOffer::finalPayable)
                .filter(java.util.Objects::nonNull)
                .min(BigDecimal::compareTo)
                .orElse(null);
    }
}
