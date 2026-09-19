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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fills a load-test database with a marketplace and LEAVES IT THERE.
 *
 * <p>NOT A TEST OF ANYTHING - it is the one entry point that seeds without
 * tearing down, because a load test needs a populated catalogue to browse and
 * k6 runs in a different process from JUnit.
 *
 * <p>SIZED FROM THE COMMAND LINE, so that the same seeder serves the
 * fourteen-trade rehearsal and the hundred-trade, two-thousand-shop run. That
 * is the whole reason {@link MarketplaceTestData.Scale} exists: there is one
 * generator, and the size is an argument to it rather than a second copy of it.
 * Omit the properties and you get {@link MarketplaceTestData.Scale#light()},
 * which is what the smaller rehearsal has always used.
 *
 * <p>DISABLED UNLESS ASKED FOR, BY NAME. {@code @EnabledIfSystemProperty} keeps
 * it out of every ordinary suite run: a seeding step that left a quarter of a
 * million listings behind would quietly corrupt whatever ran next, and CI runs
 * this module on every push. It only executes when somebody types the property,
 * and even then {@link MarketplaceTestData}'s four guards still have to pass.
 *
 * <pre>
 *   mvn test -Dtest=SeedLoadTestMarketplace \
 *            -Dgpstore.seed.loadtest=true \
 *            -Dgpstore.test-data.allow=true \
 *            -Dgpstore.seed.shops=2000 \
 *            -Dgpstore.seed.customers=50000 \
 *            -Dgpstore.seed.orders=20000 \
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
    @DisplayName("a marketplace of the requested size, left in place for k6")
    void seed() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        // Idempotent: seeding twice must not double the dataset, because a
        // load-test box gets re-seeded when a run is repeated.
        generator.cleanUp();

        int shopsWanted = intProperty("gpstore.seed.shops", 0);
        MarketplaceTestData.Scale scale = shopsWanted > 0
                ? MarketplaceTestData.Scale.large(shopsWanted,
                        intProperty("gpstore.seed.customers", 50_000),
                        intProperty("gpstore.seed.orders", 20_000),
                        intProperty("gpstore.seed.listings-per-shop", 220))
                : MarketplaceTestData.Scale.light();

        MarketplaceTestData.Marketplace market = generator.create(
                Long.getLong("gpstore.seed.seed", 20260919L), scale);

        assertTrue(market.listings() > 3000, "listings: " + market.listings());
        System.out.println("SEEDED " + market.businesses().size() + " businesses, "
                + market.shopIds().size() + " shops, " + scale.trades().size() + " trades, "
                + market.products() + " products, " + market.listings() + " listings, "
                + market.customerIds().size() + " customers, " + market.orders() + " orders");
    }

    private static int intProperty(String name, int fallback) {
        String raw = System.getProperty(name);
        return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.trim());
    }
}
