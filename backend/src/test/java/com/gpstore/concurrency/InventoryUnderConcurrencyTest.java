package com.gpstore.concurrency;

import com.gpstore.exception.ConflictException;
import com.gpstore.platform.*;
import com.gpstore.service.InventoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static com.gpstore.concurrency.ConcurrencyHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stock, when two people reach for it at once.
 *
 * THE INVARIANTS, and each is asserted rather than hoped for:
 *
 *   NOBODY OVERSELLS. Two buyers of the last unit: exactly one succeeds, and
 *   the stock lands at zero rather than at minus one. The guard is the UPDATE
 *   itself - "stock = stock - n WHERE stock >= n" matches one row or none -
 *   so this proves the guard, not the absence of a race.
 *
 *   TWO SHOPS DO NOT CONTEND. The catalogue is shared (§10) but the stock is
 *   not: one product_variant, two inventory rows, one per shop. Two shops
 *   selling the same atta at the same moment must both succeed, and neither
 *   may see the other's count move. If this failed, one merchant's busy
 *   Saturday would slow another merchant's shop down - and the two are
 *   independent businesses (§103).
 *
 *   AND THEY DO NOT BLOCK EACH OTHER EITHER. Correctness is not enough:
 *   holding a lock on Shop A's row while Shop B decrements its own must not
 *   make B wait. Proved by holding A's row locked and timing B.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("Stock, when two people reach for it at once")
class InventoryUnderConcurrencyTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private InventoryService inventoryService;

    private final String tag = "cinv" + System.nanoTime();

    private long shopA;
    private long shopB;
    private Long merchantB;
    private Long variantId;

    @BeforeEach
    void oneVariantStockedByTwoShops() {
        shopA = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();

        Merchant second = new Merchant();
        second.setLegalName("Concurrency fixture " + tag);
        second.setDisplayName("Concurrency B");
        second.setStatus(MerchantStatus.ACTIVE);
        second.setIsDemo(Boolean.TRUE);
        second.setActive(Boolean.TRUE);
        merchantB = merchants.save(second).getId();

        Shop b = new Shop();
        b.setMerchantId(merchantB);
        b.setCode("CINV-" + tag);
        b.setDisplayName("Concurrency shop B");
        b.setStatus(ShopStatus.ACTIVE);
        b.setIsDemo(Boolean.TRUE);
        b.setActive(Boolean.TRUE);
        shopB = shops.save(b).getId();

        // ONE central variant - the shared catalogue - with a stock row per
        // shop. This is the exact shape §10 describes and the one a naive
        // implementation gets wrong by keying stock on the variant alone.
        variantId = jdbc.queryForObject(
                "SELECT id FROM product_variants ORDER BY id LIMIT 1", Long.class);
        jdbc.update("DELETE FROM inventory WHERE product_variant_id = ? AND shop_id in (?, ?)",
                variantId, shopA, shopB);
    }

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        jdbc.update("DELETE FROM inventory WHERE product_variant_id = ? AND shop_id in (?, ?)",
                variantId, shopA, shopB);
        jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM shops WHERE id = ?", shopB);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantB);
    }

    @Test
    @DisplayName("two buyers of the last unit: one succeeds, one is refused, stock never goes negative")
    void theLastUnitCannotBeSoldTwice() {
        stock(shopA, 1);

        List<Callable<Integer>> buyers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            buyers.add(() -> TenantContext.runWithin(TenantScope.ofShop(shopA), () -> {
                inventoryService.decrementForPurchase(variantId, 1);
                return 1;
            }));
        }

        List<Outcome<Integer>> outcomes = raceAll(buyers);

        assertEquals(1, succeeded(outcomes),
                "exactly one buyer may get the last unit." + describe(outcomes));
        assertTrue(outcomes.stream().anyMatch(o -> o.failedWith(ConflictException.class)),
                "the loser must be told the stock ran out, not handed a unit that is not there."
                        + describe(outcomes));
        assertEquals(0, stockOf(shopA),
                "stock must land at zero. A negative here is an oversell: goods promised that "
                        + "do not exist, which a kirana finds out about at the door");
    }

    @Test
    @DisplayName("ten buyers, three units: exactly three succeed and stock lands at zero")
    void concurrentBuyersNeverExceedWhatIsOnTheShelf() {
        stock(shopA, 3);

        List<Callable<Integer>> buyers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            buyers.add(() -> TenantContext.runWithin(TenantScope.ofShop(shopA), () -> {
                inventoryService.decrementForPurchase(variantId, 1);
                return 1;
            }));
        }

        List<Outcome<Integer>> outcomes = raceAll(buyers);

        assertEquals(3, succeeded(outcomes),
                "three units means three buyers, however many are racing." + describe(outcomes));
        assertEquals(0, stockOf(shopA), "the shelf must be empty, not overdrawn");
    }

    @Test
    @DisplayName("two shops selling the same central variant do not touch each other's stock")
    void twoShopsSellingOneCatalogueItemAreIndependent() {
        stock(shopA, 1);
        stock(shopB, 1);

        List<Callable<String>> buyers = List.of(
                () -> TenantContext.runWithin(TenantScope.ofShop(shopA), () -> {
                    inventoryService.decrementForPurchase(variantId, 1);
                    return "A";
                }),
                () -> TenantContext.runWithin(TenantScope.ofShop(shopB), () -> {
                    inventoryService.decrementForPurchase(variantId, 1);
                    return "B";
                }));

        List<Outcome<String>> outcomes = raceAll(buyers);

        assertEquals(2, succeeded(outcomes),
                "BOTH must succeed. One product_variant, two inventory rows - a shared catalogue "
                        + "with per-shop stock (§10). If one failed, stock is keyed on the variant "
                        + "alone and the marketplace cannot exist." + describe(outcomes));
        assertEquals(0, stockOf(shopA), "Shop A sold its one unit");
        assertEquals(0, stockOf(shopB), "Shop B sold its one unit");
    }

    @Test
    @DisplayName("one shop's held lock does not make another shop's sale wait")
    void oneShopsTransactionDoesNotBlockAnother() throws Exception {
        stock(shopA, 5);
        stock(shopB, 5);

        // Hold Shop A's inventory row locked in a transaction that will not
        // commit for two seconds.
        Thread holder = new Thread(() -> TenantContext.runWithin(TenantScope.ofShop(shopA), () -> {
            jdbc.execute((java.sql.Connection connection) -> {
                boolean previousAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement(
                        "SELECT id FROM inventory WHERE product_variant_id = ? AND shop_id = ? FOR UPDATE")) {
                    statement.setLong(1, variantId);
                    statement.setLong(2, shopA);
                    statement.executeQuery();
                    Thread.sleep(2000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    connection.rollback();
                    connection.setAutoCommit(previousAutoCommit);
                }
                return null;
            });
            return null;
        }));
        holder.start();
        Thread.sleep(300);   // let the holder take the lock

        long startedAt = System.currentTimeMillis();
        TenantContext.runWithin(TenantScope.ofShop(shopB), () -> {
            inventoryService.decrementForPurchase(variantId, 1);
            return null;
        });
        long tookMs = System.currentTimeMillis() - startedAt;

        holder.join(10_000);

        assertEquals(4, stockOf(shopB), "Shop B's sale must have gone through");
        assertEquals(5, stockOf(shopA), "Shop A's stock must be untouched");
        assertTrue(tookMs < 1500,
                "Shop B waited " + tookMs + "ms behind a lock Shop A was holding. These are "
                        + "independent businesses (§103) - one merchant's busy Saturday must not "
                        + "slow another's shop down. A row-level lock on A's row should not be "
                        + "visible to B at all");
    }

    @Test
    @DisplayName("a refused purchase leaves the row exactly as it was")
    void afailedDecrementChangesNothing() {
        stock(shopA, 2);

        assertThrows(ConflictException.class,
                () -> TenantContext.runWithin(TenantScope.ofShop(shopA), () -> {
                    inventoryService.decrementForPurchase(variantId, 5);
                    return null;
                }));

        assertEquals(2, stockOf(shopA),
                "a decrement that could not be satisfied must not partially apply - the guard is "
                        + "one conditional UPDATE, so it matches or it does not");
    }

    // ------------------------------------------------------------- fixtures

    private void stock(long shopId, int units) {
        jdbc.update("INSERT INTO inventory (product_variant_id, stock, reserved_stock, shop_id) "
                + "VALUES (?, ?, 0, ?)", variantId, units, shopId);
    }

    private int stockOf(long shopId) {
        Integer stock = jdbc.queryForObject(
                "SELECT stock FROM inventory WHERE product_variant_id = ? AND shop_id = ?",
                Integer.class, variantId, shopId);
        return stock == null ? -1 : stock;
    }
}
