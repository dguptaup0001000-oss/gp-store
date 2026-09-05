package com.gpstore.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every shop-owned table can name its shop; the shared catalogue cannot.
 *
 * WHY BOTH HALVES MATTER. The first half is the obvious one: a table with no
 * shop_id can never be isolated, so a query against it will one day return
 * one shop's rows to another.
 *
 * The second half is the half that gets built wrong. Tagging products with a
 * shop_id is the intuitive move, and it is exactly the model this
 * transformation exists to avoid. A product is what it is regardless of who
 * sells it; the PRICE belongs to the shop. Fortune Sunflower Oil 1L is one
 * catalogue row that three shops offer at Rs 175, Rs 180 and Rs 170 - not
 * three products.
 *
 * So this fails in both directions, and the second direction is what stops a
 * later slice from quietly undoing the design.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("Tenancy columns: present where a shop owns the data, absent where it does not")
class TenancyColumnsAreInPlaceTest {

    /** Tables a SHOP owns. Each needs a shop, and every row must have one. */
    private static final List<String> SHOP_OWNED = List.of(
            "orders", "payments", "deliveries", "delivery_batches", "delivery_partners",
            "invoices", "order_returns", "coupons", "inventory",
            "store_operations_settings", "delivery_pricing_settings",
            "catalog_import_runs", "order_scan_events", "customer_delivery_ratings");

    /**
     * Tables that must NEVER carry a shop.
     *
     * products/product_variants/categories are the central catalogue.
     * customers/addresses/wishlist are the customer, who belongs to the
     * platform and orders from any shop with one account.
     */
    private static final List<String> MUST_NOT_HAVE_SHOP = List.of(
            "products", "product_variants", "categories",
            "customers", "addresses", "wishlist",
            "outbox_events", "idempotency_records", "refresh_tokens", "otp_verifications");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;

    private boolean hasShopColumn(String table) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name=? AND column_name='shop_id'",
                Integer.class, table);
        return n != null && n > 0;
    }

    @Test
    @DisplayName("every shop-owned table has a shop_id")
    void shopOwnedTablesCanNameTheirShop() {
        for (String table : SHOP_OWNED) {
            assertTrue(hasShopColumn(table),
                    table + " has no shop_id, so its rows can never be isolated to one shop");
        }
    }

    @Test
    @DisplayName("no row anywhere was left without a shop")
    void theBackfillLeftNothingBehind() {
        // An orphan row is worse than a missing column: it is invisible to
        // every shop-scoped query and nobody notices until somebody asks
        // where an order went.
        for (String table : SHOP_OWNED) {
            Integer orphans = jdbc.queryForObject(
                    "SELECT count(*) FROM " + table + " WHERE shop_id IS NULL", Integer.class);
            assertEquals(0, orphans,
                    table + " has rows with no shop - the V46 backfill did not cover them");
        }
    }

    @Test
    @DisplayName("the shared catalogue is NOT tagged with a shop")
    void theCatalogueBelongsToNobody() {
        for (String table : MUST_NOT_HAVE_SHOP) {
            assertFalse(hasShopColumn(table),
                    table + " must not carry shop_id. A product is not owned by a shop - the "
                            + "PRICE is. Tagging it here builds the model the catalogue split "
                            + "exists to avoid.");
        }
    }

    @Test
    @DisplayName("everything points at a shop that actually exists")
    void noRowPointsAtAMissingShop() {
        for (String table : SHOP_OWNED) {
            Integer dangling = jdbc.queryForObject(
                    "SELECT count(*) FROM " + table + " t "
                            + "LEFT JOIN shops s ON s.id = t.shop_id WHERE s.id IS NULL",
                    Integer.class);
            assertEquals(0, dangling, table + " references a shop that is not in the shops table");
        }
    }

    @Test
    @DisplayName("a shop code cannot be reused")
    void shopCodesAreUnique() {
        // The code is how a person names a shop in a URL, a report or a
        // support call. Reusing one silently re-points all of them.
        Integer duplicates = jdbc.queryForObject(
                "SELECT count(*) FROM (SELECT code FROM shops GROUP BY code HAVING count(*) > 1) d",
                Integer.class);
        assertEquals(0, duplicates);
        assertTrue(shops.count() >= 1);
    }
}
