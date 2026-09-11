package com.gpstore.platform;

import com.gpstore.entity.StoreClosure;
import com.gpstore.store.DeliveryScheduleService;
import com.gpstore.store.StoreOperationsService;
import com.gpstore.store.StoreOrderAcceptance;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * WHEN A SHOP IS OPEN, AND HOW FAR A CUSTOMER CAN LOOK.
 *
 * Two things the marketplace was missing, and one it had wrong.
 *
 * MISSING: the storefront list said where each shop is and how far it
 * delivers, and said nothing about whether it was open. So the app had no
 * honest way to draw a shut kirana, and the customer found out at checkout.
 * The answer is not a new flag - it already exists, per shop, in the hours
 * model (the owner's acceptance override and the days they declared closed),
 * and is what checkout itself consults. This test is what keeps the two from
 * drifting: if the storefront ever answers OPEN for a shop that would refuse
 * the order, it fails.
 *
 * WRONG: store_closures was shared. One merchant closing for a wedding closed
 * every shop on the marketplace, and - because the day was globally unique -
 * the second shop to declare a festival was told the day was already taken.
 * On a marketplace, two shops closing for the same festival is the normal
 * case.
 *
 * AND FARTHER: "nothing delivers to me" is a real answer for a customer at the
 * edge of a town, and it must not be the last screen. Searching farther widens
 * what the customer can SEE without widening what any shop has promised - so
 * every result still carries that shop's own answer to "will you come here".
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
@DisplayName("A storefront's hours are its own, and a customer can look farther")
class StorefrontHoursAndReachTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private ShopDiscovery discovery;
    @Autowired private StoreOperationsService operations;
    @Autowired private DeliveryScheduleService schedule;

    private final ObjectMapper json = new ObjectMapper();
    private final String tag = "hours" + System.nanoTime();

    /** A pin in Bengaluru, and two shops placed relative to it. */
    private static final double LAT = 12.9111;
    private static final double LNG = 77.6111;

    private long shopA;
    private long nearShop;
    private long farShop;
    private Long merchantId;

    @BeforeEach
    void twoShopsAtDifferentDistances() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
        shopA = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();

        com.gpstore.platform.Merchant m = new com.gpstore.platform.Merchant();
        m.setLegalName("Hours fixture " + tag);
        m.setDisplayName("Hours fixture");
        m.setStatus(MerchantStatus.ACTIVE);
        m.setIsDemo(Boolean.TRUE);
        m.setActive(Boolean.TRUE);
        merchantId = merchants.save(m).getId();

        // ~500 m away and willing to travel 3 km: it delivers here.
        nearShop = newShop("NEAR-" + tag, "Near kirana", LAT + 0.0045, LNG, "3");
        // ~6 km away and willing to travel 2 km: it exists, and it will not come.
        farShop = newShop("FAR-" + tag, "Far kirana", LAT + 0.0540, LNG, "2");
    }

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        for (long shop : List.of(nearShop, farShop, shopA)) {
            jdbc.update("DELETE FROM store_closures WHERE shop_id = ? AND reason LIKE ?",
                    shop, "%" + tag + "%");
        }
        // The acceptance override is a real setting on a real shop - putting
        // Shop #1 back to AUTO matters, because leaving it OFF would refuse
        // every order in every test that runs after this one.
        TenantContext.runWithin(TenantScope.ofShop(shopA), () ->
                operations.setOrderAcceptance(StoreOrderAcceptance.AUTO, null, "test-teardown"));
        for (long shop : List.of(nearShop, farShop)) {
            jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shops WHERE id = ?", shop);
        }
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
        TenantDefaults.install(PlatformMode.SINGLE_SHOP,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    // --------------------------------------------------------- closed days

    @Test
    @DisplayName("two shops may close for the same festival")
    void thesameDayMayBeClosedByBothShops() {
        LocalDate festival = today();

        StoreClosure byNear = TenantContext.runWithin(TenantScope.ofShop(nearShop),
                () -> operations.addClosure(festival, "Diwali " + tag, "near-owner"));
        StoreClosure byFar = TenantContext.runWithin(TenantScope.ofShop(farShop),
                () -> operations.addClosure(festival, "Diwali " + tag, "far-owner"));

        assertNotNull(byNear.getId());
        assertNotNull(byFar.getId(),
                "THE SECOND SHOP TO DECLARE A FESTIVAL WAS BEING REFUSED, because the closed day "
                        + "was unique across the whole marketplace. Two kiranas closing for Diwali "
                        + "is the normal case, not a conflict.");
        assertEquals(nearShop, byNear.getShopId());
        assertEquals(farShop, byFar.getShopId());
    }

    @Test
    @DisplayName("one shop's holiday is invisible to the shop next door")
    void aClosureBelongsToOneShop() {
        LocalDate festival = today();
        TenantContext.runWithin(TenantScope.ofShop(nearShop),
                () -> operations.addClosure(festival, "Wedding " + tag, "near-owner"));

        List<StoreClosure> seenByNear = TenantContext.runWithin(
                TenantScope.ofShop(nearShop), operations::upcomingClosures);
        List<StoreClosure> seenByFar = TenantContext.runWithin(
                TenantScope.ofShop(farShop), operations::upcomingClosures);

        assertTrue(seenByNear.stream().anyMatch(c -> festival.equals(c.getClosedOn())),
                "the shop that declared the holiday must see it");
        assertFalse(seenByFar.stream().anyMatch(c -> ("Wedding " + tag).equals(c.getReason())),
                "ONE MERCHANT CLOSING FOR A FAMILY WEDDING WAS CLOSING EVERY SHOP ON THE "
                        + "MARKETPLACE. Nobody else agreed to shut, and nobody else could see why "
                        + "their delivery window had moved.");

        assertTrue(TenantContext.runWithin(TenantScope.ofShop(nearShop),
                        () -> schedule.getStoreStatus().closedToday()),
                "and the shop that is shut must read as shut");
        assertFalse(TenantContext.runWithin(TenantScope.ofShop(farShop),
                        () -> schedule.getStoreStatus().closedToday()),
                "while the one that is not, is not");
    }

    // ------------------------------------------------- open/closed on the API

    @Test
    @DisplayName("the storefront list says whether each shop is taking orders")
    void theStorefrontCarriesTheShopsOwnHours() throws Exception {
        TenantContext.runWithin(TenantScope.ofShop(nearShop), () ->
                operations.setOrderAcceptance(StoreOrderAcceptance.OFF,
                        "Shut for stocktaking " + tag, "near-owner"));

        JsonNode shopsNear = getJson("/api/marketplace/shops?lat=" + LAT + "&lng=" + LNG);
        JsonNode near = pick(shopsNear, nearShop);
        assertNotNull(near, "the near shop delivers here and must be listed even while shut");

        assertFalse(near.get("acceptingOrders").asBoolean(),
                "A STOREFRONT THAT SAYS NOTHING ABOUT ITS HOURS LETS THE CUSTOMER FIND OUT AT "
                        + "CHECKOUT. The shop has switched itself off; the list has to say so.");
        assertTrue(near.get("openNow").asBoolean(),
                "BROWSING IS NEVER CLOSED. A shut kirana is still worth looking at - what changes "
                        + "is when the order arrives, not whether the catalogue may be read.");
        assertEquals("Shut for stocktaking " + tag, near.get("closureReason").asText());

        // AND IT IS THIS SHOP'S ANSWER, not the deployment's: Shop #1 was not
        // switched off and must still be taking orders.
        assertTrue(TenantContext.runWithin(TenantScope.ofShop(shopA),
                        () -> schedule.getStoreStatus().acceptingOrders()),
                "one merchant closing must not close the shop next door");
    }

    // ------------------------------------------------------- search farther

    @Test
    @DisplayName("by default a customer sees only the shops that will come to them")
    void localFirstIsStillTheDefault() throws Exception {
        JsonNode listed = getJson("/api/marketplace/shops?lat=" + LAT + "&lng=" + LNG);
        assertNotNull(pick(listed, nearShop), "the shop whose own radius covers this pin");
        assertNull(pick(listed, farShop),
                "a shop that has not promised to come here must not be offered as though it had");

        JsonNode discovery = getJson("/api/marketplace/discovery?lat=" + LAT + "&lng=" + LNG);
        assertTrue(discovery.get("radiusKm").isNull(),
                "no radius was searched: this is the delivering-shops answer, not a distance one");
        assertEquals("3", discovery.get("nextRadiusKm").asText(),
                "and the app is told where \"search farther\" would start, by the server");
        assertNull(pick(discovery.get("shops"), farShop));
    }

    @Test
    @DisplayName("searching farther shows the shop, and still says it will not come")
    void searchFartherWidensSightNotPromises() throws Exception {
        JsonNode wide = getJson(
                "/api/marketplace/discovery?lat=" + LAT + "&lng=" + LNG + "&radiusKm=10");

        JsonNode far = pick(wide.get("shops"), farShop);
        assertNotNull(far, "\"nothing delivers to me\" must not be the last screen a customer sees");
        assertFalse(far.get("deliversHere").asBoolean(),
                "SEEING A SHOP IS NOT BEING PROMISED DELIVERY BY IT. The search radius is the "
                        + "customer's; the delivery radius is the shop's, and widening one must "
                        + "never widen the other.");

        JsonNode near = pick(wide.get("shops"), nearShop);
        assertNotNull(near);
        assertTrue(near.get("deliversHere").asBoolean(),
                "and a shop that does come here still says so at any search radius");

        assertEquals("15", wide.get("nextRadiusKm").asText(), "the next rung of the ladder");
    }

    @Test
    @DisplayName("the ladder ends, and a client cannot search past it")
    void theSearchRadiusIsBounded() throws Exception {
        JsonNode top = getJson(
                "/api/marketplace/discovery?lat=" + LAT + "&lng=" + LNG + "&radiusKm=25");
        assertTrue(top.get("nextRadiusKm").isNull(),
                "at the widest rung there is nowhere farther to offer, and the button must stop");

        JsonNode absurd = getJson(
                "/api/marketplace/discovery?lat=" + LAT + "&lng=" + LNG + "&radiusKm=100000");
        assertEquals(0, new BigDecimal(absurd.get("radiusKm").asText())
                        .compareTo(ShopDiscovery.MAX_SEARCH_RADIUS_KM),
                "A RADIUS ARRIVES FROM A CLIENT, so it is clamped by the server. Trusting it "
                        + "would turn a local marketplace into a national one from a query string.");
        assertTrue(absurd.get("nextRadiusKm").isNull());
    }

    @Test
    @DisplayName("the service says the same thing the endpoint does")
    void theServiceAndTheEndpointAgree() {
        List<ShopDiscovery.NearbyShop> within =
                discovery.shopsWithin(LAT, LNG, new BigDecimal("10"));
        assertTrue(within.stream().anyMatch(n -> n.shop().getId().equals(farShop)
                && !n.deliversHere()));
        assertTrue(within.stream().anyMatch(n -> n.shop().getId().equals(nearShop)
                && n.deliversHere()));

        // Nearest first, which is the local-first part.
        assertTrue(within.get(0).distanceKm() <= within.get(within.size() - 1).distanceKm());

        assertTrue(discovery.shopsServing(LAT, LNG).stream()
                        .noneMatch(n -> n.shop().getId().equals(farShop)),
                "and the delivering list never gains a shop from a wider search");
    }

    // -------------------------------------------------------------- fixtures

    private LocalDate today() {
        return schedule.now().atZone(schedule.getProperties().getZone()).toLocalDate();
    }

    private long newShop(String code, String name, double lat, double lng, String radiusKm) {
        Shop shop = new Shop();
        shop.setMerchantId(merchantId);
        shop.setCode(code);
        shop.setDisplayName(name);
        shop.setStatus(ShopStatus.ACTIVE);
        shop.setLatitude(lat);
        shop.setLongitude(lng);
        shop.setMaxDeliveryRadiusKm(new BigDecimal(radiusKm));
        shop.setTimeZone("Asia/Kolkata");
        shop.setSupportPhone("9000000000");
        shop.setIsDemo(Boolean.TRUE);
        shop.setActive(Boolean.TRUE);
        return shops.save(shop).getId();
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
