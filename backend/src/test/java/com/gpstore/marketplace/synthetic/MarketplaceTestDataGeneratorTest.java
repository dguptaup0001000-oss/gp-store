package com.gpstore.marketplace.synthetic;

import com.gpstore.catalog.shop.CommerceMode;
import com.gpstore.catalog.shop.ListingPriceMode;
import com.gpstore.catalog.shop.OfflineAvailability;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MarketplaceTestDataGeneratorTest {

    @Test
    void buildsOneHundredUniqueShopsWithTwelveUsefulListingsEach() {
        var first = MarketplaceTestDataGenerator.generate(MarketplaceTestDataGenerator.DEFAULT_SEED);
        var second = MarketplaceTestDataGenerator.generate(MarketplaceTestDataGenerator.DEFAULT_SEED);

        assertEquals(100, first.shops().size());
        assertEquals(1_200, first.listings().size());
        assertEquals(100, first.shops().stream().map(MarketplaceTestDataGenerator.ShopSpec::shopName)
                .collect(Collectors.toSet()).size());
        assertEquals(100, first.shops().stream().map(MarketplaceTestDataGenerator.ShopSpec::shopCode)
                .collect(Collectors.toSet()).size());
        assertEquals(95, first.shops().stream().map(MarketplaceTestDataGenerator.ShopSpec::merchantName)
                .collect(Collectors.toSet()).size());
        assertTrue(first.listings().stream().collect(Collectors.groupingBy(
                MarketplaceTestDataGenerator.ListingSpec::shopCode, Collectors.counting()))
                .values().stream().allMatch(count -> count == 12));
        Map<String, Long> categoryCounts = first.shops().stream().collect(Collectors.groupingBy(
                MarketplaceTestDataGenerator.ShopSpec::category, Collectors.counting()));
        assertEquals(12L, categoryCounts.get("Grocery"));
        assertEquals(6L, categoryCounts.get("Fruits and Vegetables"));
        assertEquals(6L, categoryCounts.get("Mobile and Electronics"));
        assertEquals(7L, categoryCounts.get("Clothing and Fashion"));
        assertEquals(6L, categoryCounts.get("Hardware and Electrical"));
        assertEquals(4L, categoryCounts.get("Tractor and Agricultural Parts"));
        assertEquals(3L, categoryCounts.get("Home Services and Repair"));
        assertEquals(3L, categoryCounts.get("Mobile and Electronics Repair"));
        assertEquals(first, second, "the fixed seed must produce repeatable specs");
    }

    @Test
    void aGeneratedPrefixKeepsTheSameDeterministicIdentities() {
        var full = MarketplaceTestDataGenerator.generate(MarketplaceTestDataGenerator.DEFAULT_SEED);
        var missing = MarketplaceTestDataGenerator.generate(
                MarketplaceTestDataGenerator.DEFAULT_SEED, 97);

        assertEquals(97, missing.shops().size());
        assertEquals(97 * MarketplaceTestDataGenerator.LISTINGS_PER_SHOP,
                missing.listings().size());
        assertEquals(full.shops().subList(0, 97), missing.shops());
        assertEquals(full.listings().subList(0, 97 * MarketplaceTestDataGenerator.LISTINGS_PER_SHOP),
                missing.listings());
        assertThrows(IllegalArgumentException.class,
                () -> MarketplaceTestDataGenerator.generate(
                        MarketplaceTestDataGenerator.DEFAULT_SEED, 101));
        assertTrue(MarketplaceTestDataGenerator.categories().contains("Jewellery and Accessories"));
        assertTrue(MarketplaceTestDataGenerator.categories().contains("Tractor and Agricultural Parts"));
        assertTrue(MarketplaceTestDataGenerator.categories()
                .contains("Health and Medical Test Supplies"));
    }

    @Test
    void includesEveryAuthoritativeCommerceModeAndKeepsPriceRulesCoherent() {
        var dataset = MarketplaceTestDataGenerator.generate(MarketplaceTestDataGenerator.DEFAULT_SEED);
        Map<CommerceMode, Long> counts = dataset.listings().stream().collect(Collectors.groupingBy(
                MarketplaceTestDataGenerator.ListingSpec::commerceMode, Collectors.counting()));

        for (CommerceMode mode : CommerceMode.values()) assertTrue(counts.getOrDefault(mode, 0L) > 0);
        for (var listing : dataset.listings()) {
            assertTrue(listing.sellingPrice().compareTo(BigDecimal.ZERO) > 0, listing.sku());
            assertTrue(listing.mrp().compareTo(listing.sellingPrice()) >= 0, listing.sku());
            assertTrue(listing.costPrice().compareTo(listing.sellingPrice()) < 0, listing.sku());
            if (listing.commerceMode() == CommerceMode.ONLINE_PURCHASE) {
                assertEquals(ListingPriceMode.EXACT_PRICE, listing.priceMode());
                assertNull(listing.priceMax());
                assertNull(listing.offlineAvailability());
            } else if (listing.commerceMode() == CommerceMode.VISIT_TO_BUY) {
                if (listing.priceMode() == ListingPriceMode.PRICE_RANGE) assertNotNull(listing.priceMax());
                assertNotNull(listing.offlineAvailability());
            } else {
                assertEquals(ListingPriceMode.STARTING_FROM, listing.priceMode());
                assertNotNull(listing.offlineAvailability());
                assertTrue(listing.serviceDurationMinutes() > 0);
            }
        }
        assertTrue(counts.get(CommerceMode.ONLINE_PURCHASE) > 600);
        assertTrue(counts.get(CommerceMode.VISIT_TO_BUY) > 300);
        assertTrue(counts.get(CommerceMode.SERVICE_AT_SHOP) > 50);
    }

    @Test
    void descriptionsAndInventoryVariationAreMeaningfulAndNoImagesAreInvented() {
        var dataset = MarketplaceTestDataGenerator.generate(MarketplaceTestDataGenerator.DEFAULT_SEED);
        Set<String> descriptions = new HashSet<>();
        long outOfStock = 0;
        long discounts = 0;
        Set<String> types = new HashSet<>();
        for (var listing : dataset.listings()) {
            assertFalse(listing.description().isBlank());
            descriptions.add(listing.description());
            if (listing.stock() == 0
                    || listing.offlineAvailability() == OfflineAvailability.OUT_OF_STOCK) outOfStock++;
            if (listing.sellingPrice().compareTo(listing.mrp()) < 0) discounts++;
            types.add(listing.category());
        }
        assertEquals(dataset.listings().size(), descriptions.size());
        assertTrue(outOfStock >= dataset.listings().size() * 10 / 100
                        && outOfStock <= dataset.listings().size() * 20 / 100,
                "expected approximately 10-20% unavailable listings");
        assertTrue(discounts > 0 && discounts < dataset.listings().size());
        assertTrue(types.size() >= 20);
        assertTrue(dataset.shops().stream().noneMatch(shop -> shop.shopName().matches("(?i)test shop \\d+")));
        assertTrue(dataset.listings().stream().allMatch(listing -> listing.sku().startsWith("MKT100V1-")));
    }

    @Test
    void shopsExerciseEveryTownRadiusBandAndRemainInsideTheirOwnRadius() {
        var shops = MarketplaceTestDataGenerator.generate(MarketplaceTestDataGenerator.DEFAULT_SEED).shops();
        assertEquals(60, shops.stream().filter(shop -> shop.distanceKm() <= 8.0).count());
        assertEquals(85, shops.stream().filter(shop -> shop.distanceKm() <= 20.0).count());
        assertEquals(95, shops.stream().filter(shop -> shop.distanceKm() <= 50.0).count());
        assertEquals(100, shops.stream().filter(shop -> shop.distanceKm() <= 100.0).count());
        assertTrue(shops.stream().allMatch(shop -> shop.deliveryRadiusKm().doubleValue() >= shop.distanceKm()),
                "each shop's own delivery promise reaches its generated test location");
        assertTrue(shops.stream().map(MarketplaceTestDataGenerator.ShopSpec::distanceKm)
                        .distinct().count() > 50,
                "locations still exercise different distance bands");
        assertTrue(shops.stream().map(MarketplaceTestDataGenerator.ShopSpec::deliveryRadiusKm)
                        .distinct().count() > 3,
                "shop delivery radii remain varied");
    }
}
