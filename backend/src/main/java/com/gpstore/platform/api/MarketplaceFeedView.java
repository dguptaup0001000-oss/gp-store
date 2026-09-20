package com.gpstore.platform.api;

import com.gpstore.catalog.shop.CommerceMode;
import com.gpstore.catalog.shop.ListingPriceMode;
import com.gpstore.catalog.shop.OfflineAvailability;

import java.math.BigDecimal;

/**
 * One card on the marketplace home screen.
 *
 * <h2>One card per PRODUCT, not per listing</h2>
 *
 * <p>Five kiranas stocking the same bottle of Coca-Cola is five listing rows
 * and one thing a customer wants. A feed that drew the rows would show the
 * same bottle five times and push everything else off the screen, which is
 * how a marketplace with a thousand shops manages to look emptier than one
 * with ten. So the row that reaches the customer is the central product, and
 * the shop underneath it is the one this feed would suggest.
 *
 * <p>DIFFERENT VARIANTS ARE DIFFERENT THINGS, though. 500 ml and 1.5 litre
 * are not one card with a hidden choice; they are two products a customer
 * chooses between. The grouping is by central product id precisely because
 * that is the level the catalogue already says two things are the same at -
 * guessing at sameness by name would merge a saree with a saree.
 *
 * @param sellerCount how many nearby shops offer this, so a client can say
 *                    "3 shops nearby" without a second request. It is a count
 *                    of the shops this feed considered, never a claim about
 *                    the whole marketplace.
 */
public record MarketplaceFeedView(
        Long productId,
        String name,
        String brand,
        Long categoryId,
        String categoryName,
        String imageUrl,

        Long productVariantId,
        Double variantQuantity,
        String variantUnit,

        /** The offer this card is showing, from the shop named below. */
        BigDecimal sellingPrice,
        BigDecimal mrp,
        BigDecimal priceMax,
        ListingPriceMode priceMode,

        /**
         * What the customer can do with this, and therefore which card the
         * client draws. The whole point of carrying it here is that a client
         * must never have to guess whether to show ADD.
         */
        CommerceMode commerceMode,
        String commerceLabel,
        OfflineAvailability offlineAvailability,
        Integer serviceDurationMinutes,

        Long shopId,
        String shopName,
        Double distanceKm,
        int sellerCount) {

    /** So a client cannot come to its own conclusion about what is buyable. */
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
}
