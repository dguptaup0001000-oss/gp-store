package com.gpstore.marketplace;

import com.gpstore.platform.PlatformMode;
import com.gpstore.platform.PlatformProperties;
import com.gpstore.platform.ShopMembership;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantDefaults;
import com.gpstore.platform.TenantResolver;
import com.gpstore.entity.Role;
import com.gpstore.security.AuthenticatedUser;
import com.gpstore.security.RolePermissions;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two thousand shops, a hundred trades, and whether any of them can see each
 * other.
 *
 * <h2>Why this is tagged out of ordinary CI</h2>
 *
 * <p>It builds a marketplace of a size that takes minutes and hundreds of
 * thousands of rows. That is a capacity experiment, not a regression test -
 * running it on every push would slow every build and prove nothing that
 * {@link ThirtyShopsInOneMarketplaceTest} does not already prove on a
 * fourteen-trade dataset. The {@code large-marketplace} tag is excluded by the
 * default surefire configuration and selected by its own workflow.
 *
 * <h2>What only this size can show</h2>
 *
 * <p>Thirty shops can be checked exhaustively. Two thousand cannot - the full
 * matrix would be four million membership questions - so the isolation check
 * here is a RANDOMISED SWEEP over many pairs drawn from across the dataset,
 * seeded so a failure is reproducible. That is a different kind of evidence
 * from the small test's exhaustive one, and both are worth having.
 *
 * <p>It is also the only place where twenty shops of the same trade genuinely
 * share central catalogue rows, which is the hardest form of the question: the
 * products are the same rows, so nothing but {@code shop_product_variants} and
 * the tenant filter keeps one shop's price and stock away from another's.
 */
@SpringBootTest
@Tag("large-marketplace")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("two thousand shops in one marketplace")
class LargeMarketplaceTest {

    private static final long SEED = 20260919L;

    /** Shops, customers, orders, and the cap on any one shop's shelf. */
    private static final int SHOPS = 2000;
    private static final int CUSTOMERS = 50_000;
    private static final int ORDERS = 20_000;
    private static final int MAX_LISTINGS_PER_SHOP = 220;

    @Autowired private MarketplaceTestData generator;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopMembership membership;
    @Autowired private TenantResolver resolver;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;

    private MarketplaceTestData.Marketplace market;

    @BeforeAll
    void buildTheMarketplace() {
        System.setProperty("gpstore.test-data.allow", "true");
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
        generator.cleanUp();
        market = generator.create(SEED, MarketplaceTestData.Scale.large(
                SHOPS, CUSTOMERS, ORDERS, MAX_LISTINGS_PER_SHOP));
    }

    @AfterAll
    void tidyUp() {
        try {
            generator.cleanUp();
        } finally {
            System.clearProperty("gpstore.test-data.allow");
            TenantDefaults.install(platform.getMode(),
                    () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
        }
    }

    // ============================================================== dataset

    @Test
    @DisplayName("the marketplace is the size it claims to be")
    void theDatasetIsReal() {
        long shopsInDb = jdbc.queryForObject(
                "SELECT count(*) FROM shops WHERE code LIKE 'gptest-%'", Long.class);
        assertTrue(shopsInDb >= SHOPS,
                "at least " + SHOPS + " shops, read back from the database: " + shopsInDb);

        long listingsInDb = jdbc.queryForObject("""
                SELECT count(*) FROM shop_product_variants spv
                 JOIN shops s ON s.id = spv.shop_id
                 WHERE s.code LIKE 'gptest-%'
                """, Long.class);
        assertTrue(listingsInDb >= 200_000,
                "at least two hundred thousand shop listings: " + listingsInDb);

        assertTrue(market.customerIds().size() >= 50_000,
                "fifty thousand customers: " + market.customerIds().size());
        assertTrue(market.orders() >= 20_000, "twenty thousand orders: " + market.orders());
    }

    @Test
    @DisplayName("all one hundred trades are present, each with its own goods")
    void everyTradeExists() {
        List<String> missing = new ArrayList<>();
        for (Trade trade : Trade.values()) {
            Long products = jdbc.queryForObject(
                    "SELECT count(*) FROM products WHERE name LIKE ?", Long.class,
                    MarketplaceTestData.TAG + "-" + trade.name() + " %");
            if (products == null || products == 0) {
                missing.add(trade.name());
            }
        }
        assertTrue(missing.isEmpty(), "trades with no goods at all: " + missing);
        assertEquals(100, Trade.values().length, "a hundred trades");
    }

    @Test
    @DisplayName("a phone shop sells phones, not rice - checked for every trade")
    void noTradeIsPopulatedWithSomebodyElsesGoods() {
        // THE CHECK THAT STOPS THIS BEING A HUNDRED LABELS ON ONE DATASET. A
        // shop's listings must resolve to products of its OWN trade; a mobile
        // shop carrying detergent would pass every count-based assertion above
        // and still make the whole exercise meaningless.
        List<String> wrong = jdbc.queryForList("""
                SELECT s.code
                  FROM shops s
                  JOIN shop_product_variants spv ON spv.shop_id = s.id
                  JOIN product_variants v        ON v.id = spv.product_variant_id
                  JOIN products p                ON p.id = v.product_id
                 WHERE s.code LIKE 'gptest-%'
                   AND p.name NOT LIKE 'GPTEST-' || split_part(upper(s.code), '-', 2) || ' %'
                 LIMIT 20
                """, String.class);
        assertTrue(wrong.isEmpty(),
                "these shops carry goods belonging to another trade: " + wrong);
    }

    @Test
    @DisplayName("each trade still describes its goods in its own words")
    void theVariantModelIsGenericAcrossAHundredTrades() {
        // Spot-checked across trades whose vocabularies have nothing in common.
        assertTrue(attributeNames(Trade.PHONES).containsAll(List.of("RAM", "Storage", "Brand")));
        assertTrue(attributeNames(Trade.SAREE).containsAll(List.of("Material", "Occasion")));
        assertTrue(attributeNames(Trade.CAR_PARTS).contains("Part number"));
        assertTrue(attributeNames(Trade.PIZZA).contains("Crust"));
        assertTrue(attributeNames(Trade.FERTILIZER).contains("Grade"));
        assertTrue(attributeNames(Trade.KIRANA).contains("Pack size"));

        assertFalse(attributeNames(Trade.PHONES).contains("Pack size"),
                "a phone merchant must never be asked for a pack size");
        assertFalse(attributeNames(Trade.CAR_PARTS).contains("Pack size"),
                "nor a car-parts dealer");
    }

    // ====================================================== isolation sweep

    @Nested
    @DisplayName("the isolation sweep")
    class Sweep {

        @Test
        @DisplayName("no merchant reaches any other merchant's shop - 20,000 random pairs")
        void randomisedCrossTenantSweep() {
            // TWO THOUSAND SHOPS CANNOT BE CHECKED EXHAUSTIVELY - that is four
            // million questions - so this samples widely and reproducibly.
            // Seeded, so a failure names a pair that can be re-run.
            Random random = new Random(SEED);
            List<MarketplaceTestData.Business> all = market.businesses();

            int attempts = 0;
            int denied = 0;
            List<String> leaks = new ArrayList<>();

            for (int i = 0; i < 20_000; i++) {
                MarketplaceTestData.Business mine = all.get(random.nextInt(all.size()));
                MarketplaceTestData.Business theirs = all.get(random.nextInt(all.size()));
                if (mine == theirs) {
                    continue;
                }
                Long theirShop = theirs.shopIds().get(random.nextInt(theirs.shopIds().size()));
                attempts++;
                if (membership.permits(mine.ownerCustomerId(), theirShop)) {
                    leaks.add(mine.trade() + "#" + mine.merchantId()
                            + " reached shop " + theirShop + " of " + theirs.trade());
                } else {
                    denied++;
                }
            }

            System.out.println("SWEEP attempts=" + attempts + " denied=" + denied
                    + " unexpectedlyAllowed=" + leaks.size());
            assertTrue(leaks.isEmpty(), "cross-tenant reach: " + leaks);
            assertEquals(attempts, denied, "every attempt must be refused");
            assertTrue(attempts > 19_000, "the sweep must actually have run: " + attempts);
        }

        @Test
        @DisplayName("every merchant reaches all of their own shops - checked for all of them")
        void nobodyIsLockedOutOfTheirOwn() {
            int checked = 0;
            List<String> lockedOut = new ArrayList<>();
            for (MarketplaceTestData.Business business : market.businesses()) {
                for (Long shopId : business.shopIds()) {
                    checked++;
                    if (!membership.permits(business.ownerCustomerId(), shopId)) {
                        lockedOut.add(business.trade() + " -> " + shopId);
                    }
                }
            }
            assertTrue(lockedOut.isEmpty(), "owners refused their own shops: " + lockedOut);
            assertTrue(checked >= SHOPS, "every shop checked: " + checked);
        }

        @Test
        @DisplayName("shops of the SAME trade share products and share nothing commercial")
        void sameTradeSharesCatalogueNotShelf() {
            // THE HARDEST FORM OF THE QUESTION. Twenty phone shops point at the
            // same products and product_variants rows, so the catalogue really
            // is shared - and the only thing keeping their prices, stock and
            // shelves apart is (shop_id, product_variant_id).
            List<MarketplaceTestData.Business> phones = market.businesses().stream()
                    .filter(b -> b.trade() == Trade.PHONES).toList();
            assertTrue(phones.size() >= 2,
                    "several phone businesses must exist: " + phones.size());

            long shopA = phones.get(0).shopId();
            long shopB = phones.get(1).shopId();

            Long sharedVariants = jdbc.queryForObject("""
                    SELECT count(*) FROM shop_product_variants a
                     JOIN shop_product_variants b ON b.product_variant_id = a.product_variant_id
                     WHERE a.shop_id = ? AND b.shop_id = ?
                    """, Long.class, shopA, shopB);
            assertTrue(sharedVariants > 10,
                    "two phone shops must genuinely carry the same catalogue rows: "
                            + sharedVariants);

            Long differentPrices = jdbc.queryForObject("""
                    SELECT count(*) FROM shop_product_variants a
                     JOIN shop_product_variants b ON b.product_variant_id = a.product_variant_id
                     WHERE a.shop_id = ? AND b.shop_id = ?
                       AND a.selling_price <> b.selling_price
                    """, Long.class, shopA, shopB);
            assertTrue(differentPrices > 0,
                    "and price them independently - one price per (shop, variant)");

            // Neither owner may reach the other's shop, despite the shared rows.
            assertFalse(membership.permits(phones.get(0).ownerCustomerId(), shopB));
            assertFalse(membership.permits(phones.get(1).ownerCustomerId(), shopA));
        }

        @Test
        @DisplayName("naming another shop is refused across a wide sample")
        void tamperingWithTheShopIdIsRefused() {
            Random random = new Random(SEED + 1);
            List<MarketplaceTestData.Business> all = market.businesses();
            int refused = 0;
            List<String> leaks = new ArrayList<>();

            for (int i = 0; i < 300; i++) {
                MarketplaceTestData.Business mine = all.get(random.nextInt(all.size()));
                MarketplaceTestData.Business theirs = all.get(random.nextInt(all.size()));
                if (mine == theirs) {
                    continue;
                }
                Long theirShop = theirs.shopIds().get(0);
                try {
                    asAccount(mine.ownerCustomerId(), () -> resolver.select(theirShop));
                    leaks.add(mine.merchantId() + " resolved " + theirShop);
                } catch (IllegalStateException refusedAsItShouldBe) {
                    refused++;
                }
            }
            assertTrue(leaks.isEmpty(), "shop-id tampering succeeded: " + leaks);
            assertTrue(refused > 250, "the sample must have run: " + refused);
        }
    }

    // ========================================================== integrity

    @Nested
    @DisplayName("the dataset holds together at scale")
    class Integrity {

        @Test
        @DisplayName("no orphan listing, variant, stock row or order")
        void noOrphans() {
            assertEquals(0L, (long) jdbc.queryForObject("""
                    SELECT count(*) FROM shop_product_variants spv
                     WHERE NOT EXISTS (SELECT 1 FROM product_variants v
                                        WHERE v.id = spv.product_variant_id)
                       OR NOT EXISTS (SELECT 1 FROM shops s WHERE s.id = spv.shop_id)
                    """, Long.class), "listings pointing at nothing");
            assertEquals(0L, (long) jdbc.queryForObject("""
                    SELECT count(*) FROM product_variants v
                     WHERE NOT EXISTS (SELECT 1 FROM products p WHERE p.id = v.product_id)
                    """, Long.class), "variants without a product");
            assertEquals(0L, (long) jdbc.queryForObject("""
                    SELECT count(*) FROM orders o
                     WHERE o.order_number LIKE 'GPTEST%'
                       AND (o.shop_id IS NULL
                            OR NOT EXISTS (SELECT 1 FROM shops s WHERE s.id = o.shop_id))
                    """, Long.class), "orders naming no real shop");
        }

        @Test
        @DisplayName("no impossible stock, and no listing priced at nothing")
        void valuesAreSane() {
            assertEquals(0L, (long) jdbc.queryForObject(
                    "SELECT count(*) FROM inventory WHERE stock < 0", Long.class),
                    "negative stock");
            assertEquals(0L, (long) jdbc.queryForObject("""
                    SELECT count(*) FROM shop_product_variants spv
                     JOIN shops s ON s.id = spv.shop_id
                     WHERE s.code LIKE 'gptest-%' AND spv.selling_price <= 0
                    """, Long.class), "listings priced at zero or less");
        }

        @Test
        @DisplayName("every review stands on a delivered order of that same shop")
        void reviewsAreEarned() {
            assertEquals(0L, (long) jdbc.queryForObject("""
                    SELECT count(*) FROM shop_ratings r
                     WHERE r.comment LIKE 'GPTEST%'
                       AND NOT EXISTS (
                             SELECT 1 FROM orders o
                              WHERE o.id = r.order_id
                                AND o.shop_id = r.shop_id
                                AND o.customer_id = r.customer_id
                                AND o.order_status = 'DELIVERED')
                    """, Long.class), "reviews not backed by that shop's delivered order");
        }

        @Test
        @DisplayName("no shop code collides, and no merchant owns another's shop")
        void ownershipIsUnambiguous() {
            assertEquals(0L, (long) jdbc.queryForObject("""
                    SELECT count(*) FROM (
                      SELECT code FROM shops WHERE code LIKE 'gptest-%'
                       GROUP BY code HAVING count(*) > 1) d
                    """, Long.class), "duplicate shop codes");

            Set<Long> seen = new HashSet<>();
            List<String> doubled = new ArrayList<>();
            for (MarketplaceTestData.Business business : market.businesses()) {
                for (Long shopId : business.shopIds()) {
                    if (!seen.add(shopId)) {
                        doubled.add("shop " + shopId + " claimed twice");
                    }
                }
            }
            assertTrue(doubled.isEmpty(), doubled.toString());
        }
    }

    // ============================================================ helpers

    private List<String> attributeNames(Trade trade) {
        return jdbc.queryForList("""
                SELECT DISTINCT a.name FROM product_variant_attributes a
                 JOIN product_variants v ON v.id = a.product_variant_id
                 JOIN products p         ON p.id = v.product_id
                 WHERE p.name LIKE ?
                """, String.class, MarketplaceTestData.TAG + "-" + trade.name() + " %");
    }

    private <T> T asAccount(Long accountId, java.util.function.Supplier<T> work) {
        TenantContext.clear();
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String authority : RolePermissions.authorityNames(Role.ADMIN)) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedUser(accountId, "gptest@example.test", Role.ADMIN.name()),
                        null, authorities));
        try {
            return work.get();
        } finally {
            SecurityContextHolder.clearContext();
            TenantContext.clear();
        }
    }
}
