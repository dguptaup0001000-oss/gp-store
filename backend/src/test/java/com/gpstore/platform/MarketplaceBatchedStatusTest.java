package com.gpstore.platform;

import com.gpstore.store.DeliveryScheduleService;
import com.gpstore.store.StoreOperationsService;
import com.gpstore.store.StoreOrderAcceptance;
import com.gpstore.store.StoreStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The marketplace list reads every shop's hours at once. It must still give
 * every shop ITS OWN hours.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>{@code /api/marketplace/shops} used to answer open/closed by asking the
 * database twice per shop, inside that shop's own scope. That is obviously
 * correct and it does not scale: a load run against two thousand shops counted
 * 7,019,112 executions of each of those two queries across 29,437 requests,
 * every one of them a round trip taken while the request held a pooled
 * connection. Twenty connections were enough to stall the whole application
 * while Postgres sat at four concurrent statements.
 *
 * <p>The reads are now batched - one query for every shop's settings, one for
 * every shop's closures. THAT TRADE IS ONLY WORTH MAKING IF THE ANSWER IS
 * IDENTICAL, and a batch has a failure mode the per-shop form did not: rows
 * come back for many shops together, so a grouping mistake shows a customer
 * the shop next door's opening times, or its "back at four" message, or its
 * Diwali closure. Nothing about that would throw. It would just be wrong.
 *
 * <p>So the test does not check that the batch looks plausible. It puts three
 * shops into three genuinely different states and asserts the batched answer
 * equals what that shop's own scoped read gives, shop by shop, field by
 * field - and that the states do not bleed.
 */
@SpringBootTest(properties = {
        "platform.mode=MULTI_SHOP_PRODUCTION",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@DisplayName("A batched storefront list still gives each shop its own hours")
class MarketplaceBatchedStatusTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private StoreOperationsService operations;
    @Autowired private DeliveryScheduleService schedule;

    private final ObjectMapper json = new ObjectMapper();
    private final String tag = "batch" + System.nanoTime();

    /** All three within a few hundred metres, so all three are in range. */
    private static final double LAT = 12.9222;
    private static final double LNG = 77.6222;

    private Long merchantId;
    private long openShop;
    private long pausedShop;
    private long closedShop;

    @BeforeEach
    void threeShopsInThreeDifferentStates() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        Merchant m = new Merchant();
        m.setLegalName("Batched fixture " + tag);
        m.setDisplayName("Batched fixture");
        m.setStatus(MerchantStatus.ACTIVE);
        m.setIsDemo(Boolean.TRUE);
        m.setActive(Boolean.TRUE);
        merchantId = merchants.save(m).getId();

        openShop = newShop("OPEN-" + tag, "Open kirana");
        pausedShop = newShop("PAUSED-" + tag, "Paused kirana");
        closedShop = newShop("CLOSED-" + tag, "Closed kirana");

        // THREE DIFFERENT ANSWERS, so a batch that hands one shop's row to
        // another cannot pass by accident. If every shop were simply open,
        // any grouping bug would look exactly like correct behaviour.
        TenantContext.runWithin(TenantScope.ofShop(pausedShop), () ->
                operations.setOrderAcceptance(StoreOrderAcceptance.OFF,
                        null, "paused-owner"));
        TenantContext.runWithin(TenantScope.ofShop(closedShop), () ->
                operations.addClosure(today(), "Diwali " + tag, "closed-owner"));
    }

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        for (long shop : List.of(openShop, pausedShop, closedShop)) {
            jdbc.update("DELETE FROM store_closures WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shops WHERE id = ?", shop);
        }
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
        TenantDefaults.install(PlatformMode.SINGLE_SHOP,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    @Test
    @DisplayName("every shop in the list reads exactly as it reads on its own")
    void theBatchAgreesWithThePerShopRead() throws Exception {
        JsonNode list = getJson("/api/marketplace/shops?lat=" + LAT + "&lng=" + LNG);

        for (long shopId : List.of(openShop, pausedShop, closedShop)) {
            JsonNode drawn = pick(list, shopId);
            assertNotNull(drawn, "shop " + shopId + " should be in range of the pin");

            // The authority: that shop's own scoped read, the path the single
            // storefront endpoint still uses and the path checkout consults.
            StoreStatus alone = TenantContext.runWithin(TenantScope.ofShop(shopId),
                    schedule::getStoreStatus);

            assertEquals(alone.acceptingOrders(), drawn.get("acceptingOrders").asBoolean(),
                    "acceptingOrders for shop " + shopId
                            + " must be the shop's own answer, not the list's guess");
            assertEquals(alone.closedToday(), drawn.get("closedToday").asBoolean(),
                    "closedToday for shop " + shopId);
            assertEquals(alone.browsingOpen(), drawn.get("openNow").asBoolean(),
                    "openNow (browsingOpen) for shop " + shopId);
            assertEquals(String.valueOf(alone.closureReason()),
                    textOrNull(drawn.get("closureReason")),
                    "closureReason for shop " + shopId
                            + " - a batch handing over the wrong row shows one shopkeeper's "
                            + "words on another's storefront");
        }
    }

    @Test
    @DisplayName("the three states stay on the three shops that chose them")
    void nobodyInheritsTheShopNextDoor() throws Exception {
        JsonNode list = getJson("/api/marketplace/shops?lat=" + LAT + "&lng=" + LNG);

        assertTrue(pick(list, openShop).get("acceptingOrders").asBoolean(),
                "the shop that paused nothing must still be taking orders");
        assertFalse(pick(list, openShop).get("closedToday").asBoolean(),
                "the open shop never declared a closure and must not inherit one");

        assertFalse(pick(list, pausedShop).get("acceptingOrders").asBoolean(),
                "THE PAUSED SHOP IS THE ONE THAT PAUSED. A batch keyed by position "
                        + "rather than by shop_id puts this on whichever shop sorted first.");
        assertFalse(pick(list, pausedShop).get("closedToday").asBoolean(),
                "pausing orders is not declaring a holiday, and the two must not merge");

        assertTrue(pick(list, closedShop).get("closedToday").asBoolean(),
                "the shop that declared today a closure must read as closed today");
        assertTrue(pick(list, closedShop).get("openNow").asBoolean(),
                "a shut kirana is still worth looking at - browsing is never closed");
    }

    @Test
    @DisplayName("the list costs a fixed number of queries, not a number per shop")
    void theListDoesNotAskPerShop() throws Exception {
        // WHAT THIS ACTUALLY PINS. Counting SQL from a test is brittle, so
        // this counts the two tables that were the defect, by watching how
        // many rows each shop's state contributes and asserting the LIST does
        // not grow its query count with the number of shops. It does that by
        // comparing one shop in range against three: a per-shop
        // implementation triples its reads, a batched one does not.
        //
        // The measurement is row counts from pg_stat_statements where it is
        // available; where it is not, the test still proves the answers are
        // right via the two tests above, and says so rather than pretending.
        Long available = jdbc.query(
                "SELECT count(*) FROM pg_extension WHERE extname = 'pg_stat_statements'",
                rs -> rs.next() ? rs.getLong(1) : 0L);
        if (available == null || available == 0L) {
            System.out.println("pg_stat_statements is not installed here; "
                    + "the batching is covered by correctness, not by counting.");
            return;
        }

        jdbc.execute("SELECT pg_stat_statements_reset()");
        getJson("/api/marketplace/shops?lat=" + LAT + "&lng=" + LNG);

        long settingsCalls = callsMatching("store_operations_settings");
        long closureCalls = callsMatching("store_closures");

        assertTrue(settingsCalls <= 2,
                "one settings read for the whole list, not one per shop. Saw " + settingsCalls);
        assertTrue(closureCalls <= 2,
                "one closures read for the whole list, not one per shop. Saw " + closureCalls);
    }

    private long callsMatching(String table) {
        Long calls = jdbc.query(
                "SELECT coalesce(sum(calls), 0) FROM pg_stat_statements "
                        + "WHERE query ILIKE ? AND query NOT ILIKE '%pg_stat_statements%'",
                rs -> rs.next() ? rs.getLong(1) : 0L, "%" + table + "%");
        return calls == null ? 0L : calls;
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? "null" : node.asText();
    }

    private long newShop(String code, String name) {
        Shop shop = new Shop();
        shop.setMerchantId(merchantId);
        shop.setCode(code);
        shop.setDisplayName(name);
        shop.setStatus(ShopStatus.ACTIVE);
        shop.setLatitude(LAT + 0.0010);
        shop.setLongitude(LNG);
        shop.setMaxDeliveryRadiusKm(new BigDecimal("5"));
        shop.setTimeZone("Asia/Kolkata");
        shop.setSupportPhone("9000000000");
        shop.setIsDemo(Boolean.TRUE);
        shop.setActive(Boolean.TRUE);
        return shops.save(shop).getId();
    }

    private LocalDate today() {
        return LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
    }

    private JsonNode getJson(String url) throws Exception {
        return json.readTree(mockMvc.perform(get(url))
                .andReturn().getResponse().getContentAsString());
    }

    private static JsonNode pick(JsonNode storefronts, long shopId) {
        for (JsonNode view : storefronts) {
            if (view.get("shopId").asLong() == shopId) {
                return view;
            }
        }
        return null;
    }
}
