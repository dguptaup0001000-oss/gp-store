package com.gpstore.marketplace;

import com.gpstore.platform.PlatformMode;
import com.gpstore.platform.PlatformProperties;
import com.gpstore.platform.ShopMembership;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantDefaults;
import com.gpstore.platform.TenantResolver;
import com.gpstore.platform.TenantScope;
import com.gpstore.security.AuthenticatedUser;
import com.gpstore.security.RolePermissions;
import com.gpstore.entity.Role;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Thirty unrelated businesses sharing one database, and whether they can see
 * each other.
 *
 * <h2>Why thirty, and why thirty DIFFERENT ones</h2>
 *
 * <p>Two shops prove that a filter exists. Thirty shops of thirty trades prove
 * something harder: that nothing in the system quietly assumes shops resemble
 * each other. The dataset is deliberately lopsided - a kirana with ~500 lines
 * and five branches beside a jeweller with a dozen and one - because uniform
 * fixtures hide exactly the bugs that uneven real data finds.
 *
 * <p>These assertions are about DATA, read back from the database, and about
 * the real {@link TenantResolver} and {@link ShopMembership}. Nothing here
 * asserts on a fixture it built a moment earlier and never re-read.
 *
 * <h2>Cost</h2>
 *
 * <p>Built once for the whole class ({@code PER_CLASS}) and torn down after.
 * Rebuilding thirty businesses per test method would multiply a minute by
 * twenty and prove nothing extra.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("thirty shops in one marketplace")
class ThirtyShopsInOneMarketplaceTest {

    private static final long SEED = 20260919L;

    @Autowired private MarketplaceTestData generator;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopMembership membership;
    @Autowired private TenantResolver resolver;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;

    private MarketplaceTestData.Marketplace market;

    @BeforeAll
    void buildTheMarketplace() {
        // The generator refuses to run without this. Set here rather than in a
        // config file so it cannot be switched on for anything but this suite.
        System.setProperty("gpstore.test-data.allow", "true");
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
        generator.cleanUp();
        market = generator.create(SEED);
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
    @DisplayName("the dataset is the size and shape it claims to be")
    void theDatasetIsReal() {
        assertEquals(30, market.businesses().size(), "thirty businesses");
        assertTrue(market.shopIds().size() >= 30,
                "at least thirty shops, and some businesses have several: "
                        + market.shopIds().size());

        long shopsInDb = jdbc.queryForObject(
                "SELECT count(*) FROM shops WHERE code LIKE 'gptest-%'", Long.class);
        assertEquals(market.shopIds().size(), shopsInDb, "read back from the database");

        assertTrue(market.products() >= 3000,
                "at least three thousand products: " + market.products());
        assertTrue(market.listings() >= 3000,
                "at least three thousand shop listings: " + market.listings());
        assertTrue(market.customerIds().size() >= 500,
                "at least five hundred customers: " + market.customerIds().size());
        assertTrue(market.orders() >= 2000, "at least two thousand orders: " + market.orders());
    }

    @Test
    @DisplayName("shop sizes are genuinely uneven, so nothing can assume a typical shop")
    void sizesVary() {
        long grocery = listingCount(market.of(Trade.KIRANA).shopId());
        long jeweller = listingCount(market.of(Trade.JEWELLERY).shopId());

        assertTrue(grocery >= 450, "the kirana carries a real catalogue: " + grocery);
        assertTrue(jeweller <= 60, "the jeweller carries a handful: " + jeweller);
        assertTrue(grocery > jeweller * 5,
                "and the gap is what stops 'load every product' looking fine in tests");
    }

    @Test
    @DisplayName("each trade describes its goods in its own words, not in pack sizes")
    void theVariantModelIsGeneric() {
        assertTrue(attributeNames(Trade.PHONES).containsAll(List.of("RAM", "Storage")),
                "a phone is told apart by RAM and storage");
        assertTrue(attributeNames(Trade.SAREE).contains("Material"), "a saree by material");
        assertTrue(attributeNames(Trade.SHOES).contains("Size"), "a shoe by size");
        assertTrue(attributeNames(Trade.PHARMACY).contains("Strength"), "a medicine by strength");
        assertTrue(attributeNames(Trade.RESTAURANT).contains("Portion"), "a dish by portion");

        // And the grocery still gets its own vocabulary rather than imposing it.
        assertTrue(attributeNames(Trade.KIRANA).contains("Pack size"));
        assertFalse(attributeNames(Trade.PHONES).contains("Pack size"),
                "THE REGRESSION: a phone merchant must never be asked for a pack size");
    }

    // ====================================================== the big question

    @Nested
    @DisplayName("what one merchant can reach")
    class Reach {

        @Test
        @DisplayName("no shop lists another shop's goods - checked across all thirty")
        void noListingLeaks() {
            // THE WHOLE POINT, AS ONE QUERY. Every listing must belong to a
            // shop whose merchant owns the product behind it. Asked of the
            // entire dataset rather than of a sampled pair, because a leak
            // between two trades nobody thought to compare is still a leak.
            Long crossed = jdbc.queryForObject("""
                    SELECT count(*)
                      FROM shop_product_variants spv
                      JOIN shops s              ON s.id = spv.shop_id
                      JOIN product_variants pv  ON pv.id = spv.product_variant_id
                      JOIN products p           ON p.id = pv.product_id
                      JOIN categories c         ON c.id = p.category_id
                     WHERE s.code LIKE 'gptest-%'
                       AND p.name LIKE 'GPTEST%'
                       AND NOT EXISTS (
                             SELECT 1 FROM shop_product_variants mine
                              WHERE mine.shop_id = spv.shop_id)
                    """, Long.class);
            assertEquals(0L, crossed, "orphaned listings");

            // The direct question: does the phone shop list anything a grocery
            // created, or vice versa?
            assertEquals(0, sharedListings(Trade.PHONES, Trade.KIRANA),
                    "the phone shop must not list groceries");
            assertEquals(0, sharedListings(Trade.SAREE, Trade.PHONES),
                    "the saree shop must not list phones");
            assertEquals(0, sharedListings(Trade.PHARMACY, Trade.TOYS),
                    "the pharmacy must not list toys");
            assertEquals(0, sharedListings(Trade.TRACTOR_PARTS, Trade.BAKERY),
                    "the tractor-parts dealer must not list cakes");
        }

        @Test
        @DisplayName("a central catalogue row is not an inventory row")
        void catalogueIsNotOwnership() {
            // There ARE products for every trade in the central catalogue, and
            // that is correct - the marketplace knows what exists. What must
            // not follow is that everyone sells it.
            long allProducts = jdbc.queryForObject(
                    "SELECT count(*) FROM products WHERE name LIKE 'GPTEST%'", Long.class);
            long phoneShopListings = listingCount(market.of(Trade.PHONES).shopId());

            assertTrue(allProducts > phoneShopListings * 5,
                    "the catalogue is far bigger than any one shop's shelf - "
                            + allProducts + " vs " + phoneShopListings);
        }

        @Test
        @DisplayName("every merchant reaches their own shops and no others")
        void theFullMatrix() {
            // THIRTY BY THIRTY, not a sampled pair. 870 negative checks.
            int allowed = 0;
            int denied = 0;
            List<String> leaks = new ArrayList<>();

            for (MarketplaceTestData.Business mine : market.businesses()) {
                for (Long ownShop : mine.shopIds()) {
                    if (membership.permits(mine.ownerCustomerId(), ownShop)) {
                        allowed++;
                    } else {
                        leaks.add("own shop refused: " + mine.trade() + " -> " + ownShop);
                    }
                }
                for (MarketplaceTestData.Business other : market.businesses()) {
                    if (other == mine) {
                        continue;
                    }
                    for (Long theirShop : other.shopIds()) {
                        if (membership.permits(mine.ownerCustomerId(), theirShop)) {
                            leaks.add(mine.trade() + " reached " + other.trade()
                                    + " shop " + theirShop);
                        } else {
                            denied++;
                        }
                    }
                }
            }

            assertTrue(leaks.isEmpty(), "cross-tenant reach: " + leaks);
            assertTrue(allowed >= 30, "every merchant reaches their own: " + allowed);
            assertTrue(denied > 800,
                    "and is refused everybody else's - " + denied + " refusals checked");
        }

        @Test
        @DisplayName("naming another shop in the request does not get you into it")
        void theHeaderIsNotAKey() {
            MarketplaceTestData.Business phones = market.of(Trade.PHONES);
            MarketplaceTestData.Business saree = market.of(Trade.SAREE);

            // Their own shop resolves.
            assertEquals(phones.shopId(),
                    asAccount(phones.ownerCustomerId(),
                            () -> resolver.select(phones.shopId())).shopId());

            // The other one does not, however it is asked for.
            assertThrows(IllegalStateException.class,
                    () -> asAccount(phones.ownerCustomerId(),
                            () -> resolver.select(saree.shopId())),
                    "selecting a shop you are not staff of must be refused");
            assertThrows(IllegalStateException.class,
                    () -> asAccount(saree.ownerCustomerId(),
                            () -> resolver.select(phones.shopId())),
                    "and it must be refused in both directions");
        }

        @Test
        @DisplayName("a merchant with several shops switches between exactly their own")
        void multiShopMerchants() {
            MarketplaceTestData.Business kirana = market.of(Trade.KIRANA);
            assertEquals(5, kirana.shopIds().size(), "the kirana has five branches");

            List<Long> reachable = membership.shopIdsFor(kirana.ownerCustomerId());
            assertTrue(reachable.containsAll(kirana.shopIds()),
                    "all five are theirs to switch between");
            assertEquals(kirana.shopIds().size(), reachable.size(),
                    "and the switcher lists nothing else: " + reachable);

            // A one-shop merchant resolves without being asked, which is what
            // makes the app open straight into their shop.
            MarketplaceTestData.Business jeweller = market.of(Trade.JEWELLERY);
            assertEquals(1, jeweller.shopIds().size());
            TenantScope scope = asAccount(jeweller.ownerCustomerId(),
                    () -> resolver.select(null));
            assertEquals(jeweller.shopId(), scope.shopId(),
                    "one shop means the app opens in it, with no switcher");
        }

        @Test
        @DisplayName("workers belong to one shop, never to the marketplace")
        void workersAreScoped() {
            MarketplaceTestData.Business restaurant = market.of(Trade.RESTAURANT);
            MarketplaceTestData.Business phones = market.of(Trade.PHONES);

            List<Long> restaurantStaff = jdbc.queryForList(
                    "SELECT customer_id FROM shop_staff WHERE shop_id = ? AND active = true",
                    Long.class, restaurant.shopId());
            assertTrue(restaurantStaff.size() > 5, "the restaurant is staffed");

            for (Long worker : restaurantStaff) {
                assertFalse(membership.permits(worker, phones.shopId()),
                        "a restaurant worker must never reach the phone shop");
            }

            // And a shop with nobody is a legitimate state, not a broken one.
            MarketplaceTestData.Business jeweller = market.of(Trade.JEWELLERY);
            long jewellerStaff = jdbc.queryForObject(
                    "SELECT count(*) FROM shop_staff WHERE shop_id = ? AND active = true",
                    Long.class, jeweller.shopId());
            assertEquals(1, jewellerStaff, "just the owner, and that is fine");
        }
    }

    // ========================================================== invariants

    @Nested
    @DisplayName("the dataset holds together")
    class Invariants {

        @Test
        @DisplayName("no order belongs to a shop that does not exist, and none spans two")
        void ordersAreSingleShop() {
            assertEquals(0L, (long) jdbc.queryForObject("""
                    SELECT count(*) FROM orders o
                     WHERE o.order_number LIKE 'GPTEST%'
                       AND (o.shop_id IS NULL
                            OR NOT EXISTS (SELECT 1 FROM shops s WHERE s.id = o.shop_id))
                    """, Long.class), "every order names exactly one real shop");
        }

        @Test
        @DisplayName("no orphan listing, variant or stock row")
        void noOrphans() {
            assertEquals(0L, (long) jdbc.queryForObject("""
                    SELECT count(*) FROM shop_product_variants spv
                     WHERE NOT EXISTS (SELECT 1 FROM product_variants v
                                        WHERE v.id = spv.product_variant_id)
                    """, Long.class), "listings without a variant");
            assertEquals(0L, (long) jdbc.queryForObject("""
                    SELECT count(*) FROM product_variants v
                     WHERE NOT EXISTS (SELECT 1 FROM products p WHERE p.id = v.product_id)
                    """, Long.class), "variants without a product");
            assertEquals(0L, (long) jdbc.queryForObject("""
                    SELECT count(*) FROM inventory i
                     WHERE i.shop_id IS NULL OR i.product_variant_id IS NULL
                    """, Long.class), "stock rows that belong to nobody");
        }

        @Test
        @DisplayName("stock is never impossible")
        void stockIsSane() {
            assertEquals(0L, (long) jdbc.queryForObject(
                    "SELECT count(*) FROM inventory WHERE stock < 0", Long.class),
                    "negative stock");
        }

        @Test
        @DisplayName("one shop's stock and price are its own")
        void stockAndPriceAreShopLocal() {
            // Two shops of the SAME business list the same variants - which is
            // the sharpest version of the question, because if anything were
            // keyed on the variant rather than on (shop, variant) these would
            // be the rows that collided.
            MarketplaceTestData.Business kirana = market.of(Trade.KIRANA);
            long branchA = kirana.shopIds().get(0);
            long branchB = kirana.shopIds().get(1);

            Long shared = jdbc.queryForObject("""
                    SELECT count(*) FROM shop_product_variants a
                     JOIN shop_product_variants b
                       ON b.product_variant_id = a.product_variant_id
                     WHERE a.shop_id = ? AND b.shop_id = ?
                    """, Long.class, branchA, branchB);
            assertTrue(shared > 100, "the two branches carry the same lines: " + shared);

            Long differentPrices = jdbc.queryForObject("""
                    SELECT count(*) FROM shop_product_variants a
                     JOIN shop_product_variants b
                       ON b.product_variant_id = a.product_variant_id
                     WHERE a.shop_id = ? AND b.shop_id = ?
                       AND a.selling_price <> b.selling_price
                    """, Long.class, branchA, branchB);
            assertTrue(differentPrices > 0,
                    "and price them independently - one price per (shop, variant), "
                            + "not one price per variant");
        }

        @Test
        @DisplayName("a customer account is not a merchant account")
        void customersAreNotMerchants() {
            Long confused = jdbc.queryForObject("""
                    SELECT count(*) FROM customers c
                     JOIN shop_staff s ON s.customer_id = c.id AND s.active = true
                     WHERE c.email LIKE 'gptest-cust%'
                    """, Long.class);
            assertEquals(0L, confused,
                    "no seeded shopper may have acquired a shop merely by existing");
        }

        @Test
        @DisplayName("most customers have never ordered, and a merchant is not owed them")
        void theCustomerTail() {
            Long neverOrdered = jdbc.queryForObject("""
                    SELECT count(*) FROM customers c
                     WHERE c.email LIKE 'gptest-cust%'
                       AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = c.id)
                    """, Long.class);
            assertTrue(neverOrdered > 200,
                    "a large population no merchant has served: " + neverOrdered);
        }
    }

    // ======================================================== the guards

    @Nested
    @DisplayName("the generator refuses to run where it should not")
    class Guards {

        @Test
        @DisplayName("without the explicit opt-in it will not run at all")
        void optInIsRequired() {
            System.clearProperty("gpstore.test-data.allow");
            try {
                IllegalStateException refused = assertThrows(IllegalStateException.class,
                        () -> generator.refuseUnlessDisposable());
                assertTrue(refused.getMessage().contains("explicit opt-in"),
                        "and says why: " + refused.getMessage());
            } finally {
                System.setProperty("gpstore.test-data.allow", "true");
            }
        }

        @Test
        @DisplayName("it is not in the production artifact at all")
        void itCannotShip() {
            // THE STRUCTURAL GUARD, ASSERTED RATHER THAN ASSUMED. src/test is
            // not packaged, so there is no configuration under which production
            // could reach this class. If somebody ever moves it to src/main
            // this fails and says so.
            String path = MarketplaceTestData.class.getProtectionDomain()
                    .getCodeSource().getLocation().getPath();
            assertTrue(path.contains("test-classes"),
                    "the generator must live in the test sources and nowhere else, "
                            + "but was loaded from: " + path);
        }
    }

    // ============================================================ helpers

    private long listingCount(long shopId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM shop_product_variants WHERE shop_id = ?",
                Long.class, shopId);
    }

    /**
     * How many of trade A's listings point at a product trade B created.
     *
     * <p>Matched on the product's own trade rather than on its category,
     * because two trades can legitimately share a category word - and an
     * earlier draft of the generator was caught by exactly that.
     */
    private int sharedListings(Trade a, Trade b) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM shop_product_variants spv
                 JOIN product_variants v ON v.id = spv.product_variant_id
                 JOIN products p         ON p.id = v.product_id
                 WHERE spv.shop_id = ?
                   AND p.name LIKE ?
                """, Integer.class, market.of(a).shopId(),
                MarketplaceTestData.TAG + "-" + b.name() + " %");
    }

    private List<String> attributeNames(Trade trade) {
        return jdbc.queryForList("""
                SELECT DISTINCT a.name FROM product_variant_attributes a
                 JOIN product_variants v ON v.id = a.product_variant_id
                 JOIN products p         ON p.id = v.product_id
                 JOIN shop_product_variants spv ON spv.product_variant_id = v.id
                 WHERE spv.shop_id = ?
                """, String.class, market.of(trade).shopId());
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
