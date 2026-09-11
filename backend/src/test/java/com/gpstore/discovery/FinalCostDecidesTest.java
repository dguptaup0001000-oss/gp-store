package com.gpstore.discovery;

import com.gpstore.dto.response.VariantResponse;
import com.gpstore.entity.ProductVariant;
import com.gpstore.platform.SearchRadiusLadder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE NUMBER THE CUSTOMER PAYS IS THE NUMBER THAT DECIDES (Part 2 §5-§7, §10, §12).
 *
 * <p>NO SPRING CONTEXT HERE ON PURPOSE. Every rule in this file is
 * arithmetic or a list, and a rule that needs a database to be checked is a
 * rule that gets checked less often. The parts that genuinely need two real
 * shops are in {@code TwoWaysToFindAShopTest}.
 */
@DisplayName("Final cost decides, not the price tag")
class FinalCostDecidesTest {

    // ----------------------------------------------------------------- §7

    @Nested
    @DisplayName("§7 the 25% rule, measured on what the customer actually pays")
    class ThePriceGap {

        private final PriceGapRule rule = new PriceGapRule(null);

        @Test
        @DisplayName("the brief's own worked example qualifies")
        void theWorkedExample() {
            // §7: local ₹100 + ₹20 = ₹120; farther ₹80 + ₹10 = ₹90.
            assertTrue(rule.qualifies(new BigDecimal("120"), new BigDecimal("90")),
                    "120 / 90 is 1.33, comfortably past the 1.25 the rule asks for");
            assertEquals(new BigDecimal("30"),
                    rule.saving(new BigDecimal("120"), new BigDecimal("90")));
        }

        @Test
        @DisplayName("a cheap product with an expensive delivery is not a bargain")
        void deliveryCanReverseTheAnswer() {
            // On the PRODUCT price this looks like a clear win: 100 vs 60.
            assertTrue(rule.qualifies(new BigDecimal("100"), new BigDecimal("60")),
                    "on product price alone, 100 / 60 = 1.67 and the farther shop wins");

            // On the FINAL cost it is not: 100 + 10 = 110 against 60 + 45 = 105.
            assertFalse(rule.qualifies(new BigDecimal("110"), new BigDecimal("105")),
                    "THE DANGEROUS CASE. A cheap product behind an expensive delivery looks "
                            + "like a bargain on the product line and is not one - which is "
                            + "why §7 says the comparison uses the final customer cost, and "
                            + "why this method's parameters are named for finals.");
        }

        @Test
        @DisplayName("exactly 1.25 qualifies; a rupee under does not")
        void theBoundaryIsInclusive() {
            assertTrue(rule.qualifies(new BigDecimal("125"), new BigDecimal("100")));
            assertFalse(rule.qualifies(new BigDecimal("124.99"), new BigDecimal("100")));
        }

        @Test
        @DisplayName("the multiplier is configuration, and a typo cannot invert it")
        void theMultiplierIsConfigurable() {
            assertEquals(0, new BigDecimal("1.5")
                    .compareTo(new PriceGapRule("1.5").multiplier()));
            assertEquals(0, PriceGapRule.DEFAULT_MULTIPLIER
                    .compareTo(new PriceGapRule("0.5").multiplier()),
                    "BELOW 1 IS NOT A RELAXED RULE, IT IS THE OPPOSITE RULE - it would "
                            + "qualify a farther shop that is DEARER. A typo must not be "
                            + "able to do that.");
            assertEquals(0, PriceGapRule.DEFAULT_MULTIPLIER
                    .compareTo(new PriceGapRule("not a number").multiplier()));
        }
    }

    // ----------------------------------------------------------------- §6

    @Nested
    @DisplayName("§6 the radius ladder is configuration, not a constant")
    class TheLadder {

        @Test
        @DisplayName("a deployment that says nothing keeps the ladder it had")
        void theDefaultIsTodaysBehaviour() {
            SearchRadiusLadder ladder = new SearchRadiusLadder(null);
            assertEquals(List.of(new BigDecimal("3"), new BigDecimal("5"), new BigDecimal("10"),
                            new BigDecimal("15"), new BigDecimal("25")),
                    ladder.rungs(),
                    "MAKING THIS CONFIGURABLE MUST CHANGE NO RUNNING DEPLOYMENT. The default "
                            + "is exactly the list that used to be hard-coded.");
        }

        @Test
        @DisplayName("a small town and a dense city can have different ladders")
        void bothOfTheBriefsExamplesWork() {
            SearchRadiusLadder town = new SearchRadiusLadder("8,20,50,100,500");
            assertEquals(new BigDecimal("8"), town.first());
            assertEquals(new BigDecimal("500"), town.max());
            assertEquals(new BigDecimal("50"), town.next(new BigDecimal("20")).orElseThrow());

            SearchRadiusLadder city = new SearchRadiusLadder("3,8,15,30,100");
            assertEquals(new BigDecimal("3"), city.first());
            assertEquals(new BigDecimal("100"), city.max(),
                    "A DISTRICT WHERE THE NEXT HARDWARE SHOP IS FORTY KILOMETRES AWAY and a "
                            + "city where four kiranas share a street cannot use the same "
                            + "ladder, and neither of them is wrong.");
        }

        @Test
        @DisplayName("a malformed ladder falls back whole rather than stopping the marketplace")
        void aTypoDoesNotTakeTheShopOffline() {
            for (String broken : new String[]{"8,not-a-number,50", "8,20,10", "-5,10", "0,10", ","}) {
                assertEquals(new BigDecimal("3"), new SearchRadiusLadder(broken).first(),
                        "'" + broken + "' should fall back to the default ladder. A TYPO IN "
                                + "ONE ENVIRONMENT VARIABLE TAKING THE WHOLE MARKETPLACE "
                                + "OFFLINE is a far worse failure than running on the "
                                + "default rungs.");
            }
        }

        @Test
        @DisplayName("a client radius can narrow, never widen")
        void clampingIsOneWay() {
            SearchRadiusLadder ladder = new SearchRadiusLadder("3,8,15");
            assertEquals(new BigDecimal("8"), ladder.clamp(new BigDecimal("8")));
            assertEquals(new BigDecimal("15"), ladder.clamp(new BigDecimal("500")),
                    "A CLIENT ASKING FOR 500 KM would turn a local marketplace into a "
                            + "national one by accident (§78).");
        }

        @Test
        @DisplayName("the top of the ladder has nowhere further to go")
        void theLadderEnds() {
            SearchRadiusLadder ladder = new SearchRadiusLadder("3,8,15");
            assertTrue(ladder.next(new BigDecimal("15")).isEmpty(),
                    "which is how the \"search farther\" button knows to stop offering");
        }
    }

    // ----------------------------------------------------------------- §5

    @Nested
    @DisplayName("§5 Best Deal ranks on cost, and cannot be bought")
    class Ranking {

        private final BestDeal bestDeal = new BestDeal();

        @Test
        @DisplayName("cheapest final payable wins, not cheapest price tag")
        void finalCostDecides() {
            ShopOffer dearProductCheapDelivery = offer(1L, "80", "10", 2.0, true, true);
            ShopOffer cheapProductDearDelivery = offer(2L, "70", "45", 1.0, true, true);

            List<ShopOffer> ranked = bestDeal.rank(
                    List.of(cheapProductDearDelivery, dearProductCheapDelivery));

            assertEquals(1L, ranked.get(0).shopId(),
                    "₹90 beats ₹115 even though ₹70 looks cheaper than ₹80 - which is the "
                            + "whole of §7's worked example, applied to ranking");
        }

        @Test
        @DisplayName("an offer you cannot accept is not a better deal")
        void availabilityComesFirst() {
            ShopOffer cheapButOutOfStock = offer(1L, "50", "0", 1.0, true, false);
            ShopOffer dearerButBuyable = offer(2L, "90", "10", 4.0, true, true);

            assertEquals(2L, bestDeal.rank(List.of(cheapButOutOfStock, dearerButBuyable))
                            .get(0).shopId(),
                    "A ₹50 OFFER YOU CANNOT ACCEPT is not a better deal than a ₹100 one you "
                            + "can, and putting it first is how a customer taps three times "
                            + "and gives up.");
        }

        @Test
        @DisplayName("a shop that cannot quote delivery does not win on free delivery")
        void unknownDeliveryIsNotFreeDelivery() {
            ShopOffer cannotQuote = new ShopOffer(1L, "A", null, 1.0, true, true, true,
                    null, null, false, 0, 0, 9L, 9L, true, true,
                    new BigDecimal("60"), null, BigDecimal.ZERO,
                    null, false, null, null);
            ShopOffer quotes = offer(2L, "70", "10", 5.0, true, true);

            assertEquals(2L, bestDeal.rank(List.of(cannotQuote, quotes)).get(0).shopId(),
                    "AN UNQUOTABLE DELIVERY IS NOT A FREE ONE. Treating it as zero would put "
                            + "it top of the list on a number nobody gave.");
        }

        @Test
        @DisplayName("the nearest buyable shop is the local baseline, not the cheapest one")
        void theBaselineIsNearestNotCheapest() {
            ShopOffer nearby = offer(1L, "100", "20", 1.0, true, true);
            ShopOffer middle = offer(2L, "95", "10", 6.0, true, true);
            ShopOffer far = offer(3L, "80", "10", 20.0, true, true);

            assertEquals(1L, bestDeal.nearestBuyable(List.of(far, middle, nearby)).shopId(),
                    "\"LOCAL PRICE\" IN §7 MEANS THE SHOP ROUND THE CORNER, not the best "
                            + "shop in the search radius - measuring against the cheapest "
                            + "would make the rule compare a farther shop with another "
                            + "farther shop.");
        }

        @Test
        @DisplayName("there is no way for a merchant to pay for a better position")
        void bestDealIsNotForSale() {
            List<String> suspicious = new ArrayList<>();
            for (var component : ShopOffer.class.getRecordComponents()) {
                String name = component.getName().toLowerCase(java.util.Locale.ROOT);
                for (String forbidden : List.of("commission", "billing", "tier", "fee",
                        "ledger", "revenue", "sponsor", "promot", "boost", "rank", "paid")) {
                    if (name.contains(forbidden)) {
                        suspicious.add(component.getName());
                    }
                }
            }
            assertTrue(suspicious.isEmpty(),
                    "§5: \"Do NOT rank merchants merely because they pay GP-STORE more "
                            + "money.\" THE STRONGEST FORM OF THAT PROMISE IS AN ABSENCE: "
                            + "BestDeal ranks ShopOffers, and a ShopOffer carries no field "
                            + "through which what a merchant pays could reach the ranking. "
                            + "These components would open that route: " + suspicious);
        }

        private ShopOffer offer(long shopId, String price, String delivery,
                                double distanceKm, boolean deliversHere, boolean inStock) {
            BigDecimal p = new BigDecimal(price);
            BigDecimal d = new BigDecimal(delivery);
            return new ShopOffer(shopId, "Shop " + shopId, null, distanceKm, deliversHere,
                    true, true, null, null, false, 0, 0,
                    9L, 9L, true, inStock,
                    inStock ? p : null, null, BigDecimal.ZERO,
                    d, true, inStock ? p.add(d) : null, null);
        }
    }

    // ---------------------------------------------------------------- §10

    @Nested
    @DisplayName("§10 no price on an empty shelf")
    class OutOfStock {

        @Test
        @DisplayName("a listed item with no stock comes back with no price")
        void thePriceIsWithheld() {
            ProductVariant variant = new ProductVariant();
            variant.setId(1L);
            variant.setAvailable(Boolean.TRUE);
            variant.setSellingPrice(new BigDecimal("45"));
            variant.setMrp(new BigDecimal("50"));

            VariantResponse empty = VariantResponse.from(variant, null, 0);

            assertNull(empty.getSellingPrice(),
                    "§10 SAYS HIDE THE PRICE, and hiding it in one Dart widget is not hiding "
                            + "it - there are three clients and a public API.");
            assertNull(empty.getMrp());
            assertEquals(Boolean.FALSE, empty.getInStock());
        }

        @Test
        @DisplayName("a stocked item keeps its price")
        void stockedItemsAreUnchanged() {
            ProductVariant variant = new ProductVariant();
            variant.setId(1L);
            variant.setAvailable(Boolean.TRUE);
            variant.setSellingPrice(new BigDecimal("45"));

            VariantResponse stocked = VariantResponse.from(variant, null, 7);
            assertEquals(new BigDecimal("45"), stocked.getSellingPrice());
            assertEquals(Boolean.TRUE, stocked.getInStock());
        }

        @Test
        @DisplayName("with no shop to count for, the price stands")
        void unknownStockKeepsThePrice() {
            ProductVariant variant = new ProductVariant();
            variant.setId(1L);
            variant.setAvailable(Boolean.TRUE);
            variant.setSellingPrice(new BigDecimal("45"));

            VariantResponse noShop = VariantResponse.from(variant, null, null);
            assertEquals(new BigDecimal("45"), noShop.getSellingPrice(),
                    "AN ADMIN CATALOGUE SCREEN AND A PLATFORM-WIDE REPORT have no shop to "
                            + "count stock for. Reading null as zero would blank the "
                            + "merchant's own product list.");
            assertNull(noShop.getInStock());
        }
    }

    // ---------------------------------------------------------------- §12

    @Nested
    @DisplayName("§12 no platform-wide price floor")
    class NoPriceFloor {

        @Test
        @DisplayName("nothing in the source imposes a minimum selling price")
        void thereIsNoFloor() throws Exception {
            Path main = Path.of("src/main/java/com/gpstore");
            List<String> offenders = new ArrayList<>();
            try (Stream<Path> files = Files.walk(main)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    for (String marker : List.of("PRICE_FLOOR", "MIN_SELLING_PRICE",
                            "MINIMUM_PRICE", "minimumSellingPrice", "priceFloor")) {
                        if (source.contains(marker)) {
                            offenders.add(file.getFileName() + " contains " + marker);
                        }
                    }
                }
            }
            assertTrue(offenders.isEmpty(),
                    "§12: \"No global resale price floor should be hard-coded. No "
                            + "platform-wide ₹160-style minimum price.\" A merchant's price "
                            + "is the merchant's, subject to law and MRP - not to a constant "
                            + "somebody typed once. Found: " + offenders);
        }

        @Test
        @DisplayName("the shop's own listing is the only source of a selling price")
        void theShopSetsIt() {
            ProductVariant variant = new ProductVariant();
            variant.setId(1L);
            variant.setAvailable(Boolean.TRUE);
            variant.setSellingPrice(new BigDecimal("500"));

            com.gpstore.catalog.shop.ShopProductVariant listing =
                    new com.gpstore.catalog.shop.ShopProductVariant();
            listing.setProductVariantId(1L);
            listing.setSellingPrice(new BigDecimal("12"));
            listing.setAvailable(Boolean.TRUE);
            listing.setActive(Boolean.TRUE);

            VariantResponse response = VariantResponse.from(variant, listing, 5);
            assertEquals(new BigDecimal("12"), response.getSellingPrice(),
                    "TWELVE RUPEES IS A PRICE A SHOP MAY CHARGE. Nothing floors it up to "
                            + "the catalogue's 500, and nothing floors it up to a platform "
                            + "minimum - because there is no platform minimum.");
            assertNotNull(response.getSellingPrice());
        }
    }
}
