package com.gpstore.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpstore.catalog.shop.CommerceMode;
import com.gpstore.catalog.shop.ListingPriceMode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JSON the app actually receives, not the Java the backend actually has.
 *
 * <h2>The bug this exists because of</h2>
 *
 * <p>{@code addable} is a derived accessor on a record rather than a record
 * component, and Jackson serialises a record FROM ITS COMPONENTS ONLY. So the
 * field was simply absent from every marketplace response - while the backend
 * was perfectly correct and every backend test passed, because they all call
 * {@code view.addable()} in Java and never cross JSON.
 *
 * <p>A MISSING FIELD IS NOT A VISIBLE FAILURE, which is what made it costly.
 * The Flutter model reads it as {@code (json['addable'] as bool?) ?? false} -
 * a deliberate safe default - so the app did not crash or warn. It drew VIEW
 * instead of ADD on every card in the marketplace, and looked like a UI bug in
 * a layer that had nothing wrong with it.
 *
 * <p>So these assertions are about the WIRE. Anything a client reads and the
 * server derives rather than stores belongs here.
 */
@DisplayName("What the app actually receives")
class WhatTheAppActuallyReceivesTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("a feed card carries addable, because the app draws its button from it")
    void theFeedCardSaysWhetherACartCanHoldIt() throws Exception {
        JsonNode online = json.valueToTree(card(CommerceMode.ONLINE_PURCHASE));
        assertTrue(online.has("addable"),
                "absent, the Flutter model's `?? false` turns every card in the "
                        + "marketplace into VIEW - silently, with nothing to see in a log");
        assertEquals(true, online.get("addable").asBoolean());

        JsonNode visit = json.valueToTree(card(CommerceMode.VISIT_TO_BUY));
        assertEquals(false, visit.get("addable").asBoolean());

        JsonNode service = json.valueToTree(card(CommerceMode.SERVICE_AT_SHOP));
        assertEquals(false, service.get("addable").asBoolean());
    }

    @Test
    @DisplayName("an offer carries both the things the server works out for the app")
    void theOfferSaysWhatItDerives() throws Exception {
        JsonNode node = json.valueToTree(offer(CommerceMode.VISIT_TO_BUY));

        assertTrue(node.has("addable"));
        assertEquals(false, node.get("addable").asBoolean());

        assertTrue(node.has("whereToGo"),
                "the shops-near-you screen draws the address from this, and a listing "
                        + "the customer is told to visit without one is a poster with no "
                        + "shop behind it");
        assertEquals("12 Market Road, Bajaj Nagar, Nagpur, 440010",
                node.get("whereToGo").asText());
    }

    @Test
    @DisplayName("the stored fields the app reads are all on the wire too")
    void nothingTheAppReadsIsMissing() throws Exception {
        JsonNode node = json.valueToTree(card(CommerceMode.SERVICE_AT_SHOP));

        // Every key MarketplaceCard.fromJson looks for. A rename on either
        // side is a silently blank screen rather than a compile error, which
        // is exactly the class of bug this file is here to catch.
        for (String field : new String[]{
                "productId", "name", "brand", "categoryId", "categoryName",
                "productVariantId", "variantQuantity", "variantUnit", "inStock",
                "imageUrl",
                "sellingPrice", "mrp", "priceMax", "priceMode",
                "commerceMode", "commerceLabel", "offlineAvailability",
                "serviceDurationMinutes", "shopId", "shopName", "distanceKm",
                "sellerCount", "addable"}) {
            assertTrue(node.has(field), "the app reads " + field + " and it is not on the wire");
        }
    }

    // ------------------------------------------------------------- fixture

    private static MarketplaceFeedView card(CommerceMode mode) {
        return new MarketplaceFeedView(
                1L, "Thing", "Brand", 2L, "Category", "https://images.example/item.jpg", true,
                3L, 1.0, "each",
                new BigDecimal("100"), new BigDecimal("120"), null,
                ListingPriceMode.EXACT_PRICE, mode, mode.customerLabel(),
                null, null, 4L, "Shop", 1.2, 3);
    }

    private static MarketplaceOfferView offer(CommerceMode mode) {
        return new MarketplaceOfferView(
                1L, "Thing", "Brand", 3L, 1.0, "each",
                new BigDecimal("100"), new BigDecimal("120"), null,
                ListingPriceMode.EXACT_PRICE, mode, mode.customerLabel(),
                null, null,
                4L, "Shop", "12 Market Road", "Bajaj Nagar", "Nagpur", "440010",
                21.1, 79.2, null, 1.2);
    }
}
