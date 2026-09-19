package com.gpstore.marketplace;

import com.gpstore.platform.PlatformMode;
import com.gpstore.platform.PlatformProperties;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.TenantDefaults;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fills a load-test database with the thirty-business marketplace and LEAVES
 * IT THERE.
 *
 * <p>NOT A TEST OF ANYTHING - it is the one entry point that seeds without
 * tearing down, because a load test needs a populated catalogue to browse and
 * k6 runs in a different process from JUnit.
 *
 * <p>DISABLED UNLESS ASKED FOR, BY NAME. {@code @EnabledIfSystemProperty} keeps
 * it out of every ordinary suite run: a seeding step that left 11,000 listings
 * behind would quietly corrupt whatever ran next, and CI runs this module on
 * every push. It only executes when somebody types the property, and even then
 * {@link MarketplaceTestData}'s four guards still have to pass.
 *
 * <pre>
 *   mvn test -Dtest=SeedLoadTestMarketplace \
 *            -Dgpstore.seed.loadtest=true \
 *            -Dgpstore.test-data.allow=true \
 *            -Dspring.datasource.url=jdbc:postgresql://localhost:5432/gpstore_loadtest
 * </pre>
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "gpstore.seed.loadtest", matches = "true")
@DisplayName("seed a load-test marketplace")
class SeedLoadTestMarketplace {

    @Autowired private MarketplaceTestData generator;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;

    @Test
    @DisplayName("thirty businesses, left in place for k6")
    void seed() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        // Idempotent: seeding twice must not double the dataset, because a
        // load-test box gets re-seeded when a run is repeated.
        generator.cleanUp();
        MarketplaceTestData.Marketplace market = generator.create(20260919L);

        assertEquals(30, market.businesses().size());
        assertTrue(market.listings() > 3000, "listings: " + market.listings());
        System.out.println("SEEDED " + market.businesses().size() + " businesses, "
                + market.shopIds().size() + " shops, " + market.products() + " products, "
                + market.listings() + " listings, " + market.customerIds().size()
                + " customers, " + market.orders() + " orders");
    }
}
