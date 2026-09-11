package com.gpstore.discovery;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One shop's answer to "what would this item cost me, from you?" (§8).
 *
 * <p>FINAL PAYABLE IS THE FIELD THAT MATTERS, and §7 spells out why with a
 * worked example: ₹100 + ₹20 delivery is dearer than ₹80 + ₹10, and a
 * comparison on the product price alone gets it backwards. §7 also says the
 * delivery charge must not be hidden until checkout — so it travels with the
 * price, in the same record, on the same screen.
 *
 * <p>{@code deliveryChargeKnown} EXISTS BECAUSE ZERO IS A LIE. A shop whose
 * charge cannot be worked out — no pin, an address outside its circle, a
 * pricing table it has not filled in — must not be shown as free delivery and
 * win the comparison on a number nobody quoted. The flag lets the screen say
 * "delivery quoted at checkout" and lets the ranking refuse to put it first.
 *
 * @param deliversHere    this shop's own radius, never the search radius
 * @param finalPayable    price − discount + delivery, or null when unknowable
 */
public record ShopOffer(
        Long shopId,
        String shopName,
        String logoUrl,
        Double distanceKm,
        boolean deliversHere,
        boolean openNow,
        boolean acceptingOrders,
        com.gpstore.platform.ShopVerificationLevel verificationLevel,
        String verificationBadge,
        boolean trusted,
        double ratingAverage,
        long ratingCount,
        Long productId,
        Long variantId,
        boolean listed,
        boolean inStock,
        BigDecimal sellingPrice,
        BigDecimal mrp,
        BigDecimal discount,
        BigDecimal deliveryCharge,
        boolean deliveryChargeKnown,
        BigDecimal finalPayable,
        LocalDate estimatedDeliveryDate) {

    /** Whether a customer could actually put this in a basket right now. */
    public boolean isBuyableNow() {
        return listed && inStock && deliversHere && acceptingOrders && finalPayable != null;
    }

    /** For ordering when a price is missing: an unpriceable offer sorts last. */
    public BigDecimal finalOrMax() {
        return finalPayable == null ? new BigDecimal("999999999") : finalPayable;
    }
}
