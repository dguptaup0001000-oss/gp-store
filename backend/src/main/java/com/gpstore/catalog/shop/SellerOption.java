package com.gpstore.catalog.shop;

import java.math.BigDecimal;

/**
 * One shop that can supply a product, and what it would actually cost.
 *
 * <p>TOTAL, NOT JUST THE PRICE TAG. A shop 200 metres away charging ₹42 with
 * free delivery beats one across town charging ₹40 plus ₹30 to bring it, and
 * a chooser that showed only the two product prices would recommend the wrong
 * one while looking like it was helping. Whatever is shown to a customer as
 * "cheaper" has to be the number they will actually pay.
 *
 * @param preferred this customer named this shop for this category. Not a
 *                  ranking signal among others - it wins outright when the
 *                  shop is eligible, because the customer already decided.
 */
public record SellerOption(
        Long shopId,
        String shopName,
        Double distanceKm,
        BigDecimal productPrice,
        BigDecimal deliveryFee,
        BigDecimal totalCost,
        boolean preferred,
        boolean trusted,
        Long productVariantId) {
}
