package com.gpstore.marketplace.synthetic;

import com.gpstore.catalog.shop.CommerceMode;
import com.gpstore.catalog.shop.ListingPriceMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MarketplaceTestDataGeneratorTest {

    @Test
    void buildsOneHundredUniqueShopsAndAtLeastFiftyListingsPerShop() {
        var first = MarketplaceTestDataGenerator.generate(MarketplaceTestDataGenerator.DEFAULT_SEED);
        var second = MarketplaceTestDataGenerator.generate(MarketplaceTestDataGenerator.DEFAULT_SEED);

        assertEquals(100, first.shops().size());
        assertEquals(6_000, first.listings().size());
        assertEquals(100, first.shops().stream().map(MarketplaceTestDataGenerator.ShopSpec::shopName)
                .collect(Collectors.toSet()).size());
        assertEquals(100, first.shops().stream().map(MarketplaceTestDataGenerator.ShopSpec::shopCode)
                .collect(Collectors.toSet()).size());
        assertTrue(first.listings().stream().collect(Collectors.groupingBy(
                MarketplaceTestDataGenerator.ListingSpec::shopCode, Collectors.counting()))
                .values().stream().allMatch(count -> count >= 50));
        assertEquals(first, second, "the fixed seed must produce repeatable specs");
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
        assertTrue(counts.get(CommerceMode.ONLINE_PURCHASE) > 2_000);
        assertTrue(counts.get(CommerceMode.VISIT_TO_BUY) > 1_000);
        assertTrue(counts.get(CommerceMode.SERVICE_AT_SHOP) > 600);
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
            if (listing.commerceMode() == CommerceMode.ONLINE_PURCHASE && listing.stock() == 0) outOfStock++;
            if (listing.sellingPrice().compareTo(listing.mrp()) < 0) discounts++;
            types.add(listing.category());
        }
        assertEquals(dataset.listings().size(), descriptions.size());
        assertTrue(outOfStock > 0 && outOfStock < 1_000, "expected bounded out-of-stock variation");
        assertTrue(discounts > 0 && discounts < dataset.listings().size());
        assertTrue(types.size() >= 20);
        assertTrue(dataset.shops().stream().allMatch(shop -> shop.shopName().contains("TEST")));
        assertTrue(dataset.listings().stream().allMatch(listing -> listing.sku().startsWith("MKT100V1-")));
    }
}
