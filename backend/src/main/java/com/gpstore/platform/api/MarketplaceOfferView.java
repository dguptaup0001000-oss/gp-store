package com.gpstore.platform.api;

import com.gpstore.catalog.shop.CommerceMode;
import com.gpstore.catalog.shop.ListingPriceMode;
import com.gpstore.catalog.shop.OfflineAvailability;

import java.math.BigDecimal;

/**
 * One shop's offer of one product, as the customer who tapped the card sees it.
 *
 * <h2>Why the card collapses and this expands</h2>
 *
 * <p>The feed shows ONE card per product on purpose - five shops selling Coke
 * is one drink, not five results, and a feed that repeated it five times would
 * be a worse answer to "what can I buy near me?". But the moment the customer
 * taps that card, "who has it, where, and for how much" is precisely what they
 * are asking. So every nearby shop offering it appears here. Nothing was
 * hidden by the feed and nothing is invented here; they are two views of the
 * same rows, collapsed and expanded.
 *
 * <h2>Why it carries an address and a phone number</h2>
 *
 * <p>A Visit-to-Buy listing whose screen cannot say WHERE is a poster with no
 * shop behind it. A customer told to visit needs the address, something to put
 * in a maps app, and a number to check the thing is still there before they
 * travel. These are the things a storefront already shows any passer-by, which
 * is the line: the shop's public support number, not the owner's mobile; the
 * shop's address, not its takings.
 *
 * @param addable whether a cart can hold this. Computed from the commerce mode
 *                rather than sent as an independent flag, so the button and
 *                the rule cannot drift apart - and the backend refuses the
 *                cart call anyway, because a hidden button is a courtesy and
 *                not a control.
 */
public record MarketplaceOfferView(
        Long productId,
        String productName,
        String brand,
        Long productVariantId,
        Double variantQuantity,
        String variantUnit,
        BigDecimal price,
        BigDecimal mrp,
        BigDecimal priceMax,
        ListingPriceMode priceMode,
        CommerceMode commerceMode,
        String commerceLabel,
        OfflineAvailability offlineAvailability,
        Integer serviceDurationMinutes,
        Long shopId,
        String shopName,
        String addressLine,
        String locality,
        String city,
        String pincode,
        Double shopLatitude,
        Double shopLongitude,
        String supportPhone,
        Double distanceKm) {

    // JACKSON SERIALISES A RECORD FROM ITS COMPONENTS ONLY, so this derived
    // accessor is invisible on the wire without an explicit @JsonProperty -
    // and a missing field is not a visible failure. The Flutter model reads
    // `addable` with `?? false`, so every card in the app rendered VIEW
    // instead of ADD while the backend and its tests were perfectly correct:
    // they call this method in Java and never cross JSON. Found by curling
    // the endpoint against the seeded marketplace.
    @com.fasterxml.jackson.annotation.JsonProperty("addable")
    public boolean addable() {
        return commerceMode != null && commerceMode.isBuyableOnline();
    }

    /** The address on one line, or null when the shop has not filled one in. */
    @com.fasterxml.jackson.annotation.JsonProperty("whereToGo")
    public String whereToGo() {
        StringBuilder out = new StringBuilder();
        appendPart(out, addressLine);
        appendPart(out, locality);
        appendPart(out, city);
        appendPart(out, pincode);
        return out.length() == 0 ? null : out.toString();
    }

    private static void appendPart(StringBuilder out, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        if (out.length() > 0) {
            out.append(", ");
        }
        out.append(part.trim());
    }
}
