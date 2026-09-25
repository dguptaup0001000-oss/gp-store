package com.gpstore.marketplace.synthetic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpstore.catalog.shop.CommerceMode;
import com.gpstore.platform.Shop;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.ShopDiscovery;
import com.gpstore.platform.api.MarketplaceFeedService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the real seeder and paged feed against the disposable CI database.
 * The surrounding test transaction is rolled back, so none of this dataset is
 * retained by the test suite.
 */
@SpringBootTest(properties = {
        "app.production=false",
        "platform.mode=MULTI_SHOP_PRODUCTION",
        "store.latitude=27.162310",
        "store.longitude=83.940468",
        "outbox.initial-delay-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000",
        "r2.staging-sweep-initial-delay-ms=3600000"
})
class MarketplaceTestDataSeederIntegrationTest {

    @Autowired private MarketplaceTestDataSeeder seeder;
    @Autowired private MarketplaceFeedService feed;
    @Autowired private ShopRepository shops;
    @Autowired private ShopDiscovery discovery;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    @AfterEach
    void clearLocalSeedOptIn() {
        System.clearProperty("gpstore.test-data.allow");
    }

    @Test
    @Transactional
    void seedIsIdempotentVisibleThroughMarketplaceJsonAndSafelyCleanable() throws Exception {
        System.setProperty("gpstore.test-data.allow", "true");
        Shop anchor = shops.findByCode("SHOP-1").orElseThrow();
        Double anchorLat = anchor.getLatitude();
        Double anchorLng = anchor.getLongitude();
        long customerCountBefore = jdbc.queryForObject("SELECT count(*) FROM customers", Long.class);

        var inspectionBefore = seeder.execute(new MarketplaceTestDataSeeder.Request(
                MarketplaceTestDataSeeder.Operation.INSPECT,
                MarketplaceTestDataGenerator.BATCH_ID, null));
        assertEquals(0, inspectionBefore.shops());
        assertEquals(0, inspectionBefore.listings());
        assertEquals(customerCountBefore, jdbc.queryForObject("SELECT count(*) FROM customers", Long.class),
                "INSPECT must be read-only");

        MarketplaceTestDataSeeder.Request seed = new MarketplaceTestDataSeeder.Request(
                MarketplaceTestDataSeeder.Operation.SEED,
                MarketplaceTestDataGenerator.BATCH_ID, null);
        var first = seeder.execute(seed);
        assertEquals(100, first.shops());
        assertEquals(100, first.merchants());
        assertEquals(6_000, first.products());
        assertEquals(6_000, first.variants());
        assertEquals(6_000, first.listings());
        assertEquals(0, first.shopsBelowFiftyListings());
        assertTrue(first.buyOnline() > 0);
        assertTrue(first.visitToBuy() > 0);
        assertTrue(first.serviceAtShop() > 0);
        assertEquals(0, first.withImages());
        assertEquals(6_000, first.withoutImages());
        assertEquals(100, discovery.shopsServing(anchorLat, anchorLng).stream()
                        .filter(nearby -> nearby.shop().getCode().startsWith("MKT100V1-SHOP-"))
                        .count(),
                "all synthetic shops must be returned by the real nearby-discovery service at the seed anchor");
        var inspectionAfter = seeder.execute(new MarketplaceTestDataSeeder.Request(
                MarketplaceTestDataSeeder.Operation.INSPECT,
                MarketplaceTestDataGenerator.BATCH_ID, null));
        assertEquals(first.shops(), inspectionAfter.shops());
        assertEquals(first.listings(), inspectionAfter.listings());
        assertEquals(customerCountBefore, jdbc.queryForObject("SELECT count(*) FROM customers", Long.class));

        var repeated = seeder.execute(seed);
        assertTrue(repeated.alreadyPresent());
        assertEquals(first.shops(), repeated.shops());
        assertEquals(first.listings(), repeated.listings());

        Shop firstTestShop = shops.findByCode("MKT100V1-SHOP-001").orElseThrow();
        assertEquals(anchor.getId(), shops.findByCode("SHOP-1").orElseThrow().getId());
        assertEquals(anchorLat, shops.findByCode("SHOP-1").orElseThrow().getLatitude());
        assertEquals(anchorLng, shops.findByCode("SHOP-1").orElseThrow().getLongitude());
        assertEquals("PAUSED", firstTestShop.getStatus().name());

        var firstPage = feed.page(anchorLat, anchorLng, Set.of(CommerceMode.values()), null,
                null, 0, 50);
        assertFalse(firstPage.isEmpty());
        var wireJson = objectMapper.valueToTree(firstPage);
        assertTrue(wireJson.get(0).has("commerceMode"));
        assertTrue(wireJson.get(0).has("addable"));
        assertFalse(wireJson.get(0).hasNonNull("imageUrl"));

        var nextPage = feed.page(anchorLat, anchorLng, Set.of(CommerceMode.ONLINE_PURCHASE),
                null, null, 1, 50);
        assertTrue(nextPage.stream().map(row -> row.shopId()).distinct().count() > 1,
                "the real paged marketplace feed should move from one nearby shop to another");

        var onlineAtSelectedShop = feed.page(firstTestShop.getLatitude(), firstTestShop.getLongitude(),
                Set.of(CommerceMode.ONLINE_PURCHASE), null, firstTestShop.getId(), 0, 50);
        assertEquals(50, onlineAtSelectedShop.size());
        assertTrue(onlineAtSelectedShop.stream().allMatch(row -> row.shopId().equals(firstTestShop.getId())));

        Shop visitShop = shops.findByCode("MKT100V1-SHOP-003").orElseThrow();
        var visitRows = feed.page(visitShop.getLatitude(), visitShop.getLongitude(),
                Set.of(CommerceMode.VISIT_TO_BUY), null, visitShop.getId(), 0, 50);
        assertEquals(32, visitRows.size());
        assertTrue(visitRows.stream().allMatch(row -> row.commerceMode() == CommerceMode.VISIT_TO_BUY));
        var visitJson = objectMapper.valueToTree(visitRows);
        for (var row : visitJson) assertFalse(row.get("addable").asBoolean());

        Shop serviceShop = shops.findByCode("MKT100V1-SHOP-025").orElseThrow();
        var serviceRows = feed.page(serviceShop.getLatitude(), serviceShop.getLongitude(),
                Set.of(CommerceMode.SERVICE_AT_SHOP), null, serviceShop.getId(), 0, 50);
        assertEquals(50, serviceRows.size());
        assertTrue(serviceRows.stream().allMatch(row -> row.commerceMode() == CommerceMode.SERVICE_AT_SHOP));
        var serviceJson = objectMapper.valueToTree(serviceRows);
        for (var row : serviceJson) assertFalse(row.get("addable").asBoolean());

        var cleanup = seeder.execute(new MarketplaceTestDataSeeder.Request(
                MarketplaceTestDataSeeder.Operation.CLEANUP,
                MarketplaceTestDataGenerator.BATCH_ID, null));
        assertEquals(0, cleanup.shops());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM products "
                + "WHERE is_test_data = TRUE AND data_source = ?", Integer.class,
                MarketplaceTestDataGenerator.BATCH_ID));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM shops WHERE code ~ ?",
                Integer.class, "^MKT100V1-SHOP-[0-9]{3}$"));
        assertEquals(customerCountBefore, jdbc.queryForObject("SELECT count(*) FROM customers", Long.class));
    }
}
