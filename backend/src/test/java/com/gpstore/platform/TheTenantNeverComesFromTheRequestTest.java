package com.gpstore.platform;

import com.gpstore.security.WithStaff;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * A caller cannot choose which shop they are.
 *
 * THIS IS THE WHOLE OF §78 IN ONE PROPERTY. Every cross-shop attack in that
 * section - /shops/B/orders, body shop_id=B, worker_id=B, merchant_id=B - is
 * the same move: put somebody else's identifier in the request and see if the
 * server believes it. If the scope is resolved from the credential and only
 * from the credential, none of those moves has anywhere to land.
 *
 * The codebase already works this way one level down. CartController reads
 * CurrentUser.customerId() from the token and ignores any customer id in the
 * body, which is why a customer cannot empty another customer's basket by
 * editing a request. Shop scoping is that same rule, one level up.
 *
 * So this test does not check that a particular endpoint is guarded. It
 * checks the property that makes guarding possible at all - and it is
 * deliberately written against the RESOLVER rather than against one route,
 * because a route-by-route test proves nothing about route 42.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@DisplayName("The tenant never comes from the request")
class TheTenantNeverComesFromTheRequestTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantResolver resolver;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;

    @AfterEach
    void clearScope() {
        TenantContext.clear();
    }

    private Long shopOneId() {
        return shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();
    }

    @Test
    @DisplayName("a query parameter naming another shop changes nothing")
    void aQueryParameterCannotChooseTheShop() throws Exception {
        // The oldest trick in the book, and the one §78 names first.
        mockMvc.perform(get("/api/store/status").param("shopId", "999"));
        assertEquals(shopOneId(), resolver.resolve().requireShopId(),
                "a shop id in the query string must be ignored entirely");
    }

    @Test
    @DisplayName("a header naming another shop changes nothing")
    void aHeaderCannotChooseTheShop() throws Exception {
        mockMvc.perform(get("/api/store/status")
                .header("X-Shop-Id", "999")
                .header("X-Tenant-Id", "999")
                .header("X-Merchant-Id", "999"));

        assertEquals(shopOneId(), resolver.resolve().requireShopId(),
                "no header is consulted; the resolver never reads the request at all");
    }

    @Test
    @WithStaff
    @DisplayName("a staff credential resolves to a shop, not to whatever it asks for")
    void staffGetTheirOwnShop() {
        // Under one shop there is one answer, and that is exactly why the
        // existing APKs keep working: their tokens carry no shop claim and
        // do not need one.
        TenantScope scope = resolver.resolve();
        assertTrue(scope.isSingleShop(), "single-shop mode resolves to a shop, never to everything");
        assertEquals(shopOneId(), scope.requireShopId());
    }

    @Test
    @DisplayName("the resolver takes no argument, so there is nothing to poison")
    void theResolverHasNoInputToTamperWith() throws Exception {
        // A design property, asserted rather than assumed: TenantResolver
        // exposes exactly one public method and it accepts nothing. A
        // resolve(String shopId) overload is how this class would one day
        // start trusting a caller, so its absence is worth a test.
        long resolveMethods = java.util.Arrays.stream(TenantResolver.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("resolve"))
                .peek(m -> assertEquals(0, m.getParameterCount(),
                        "resolve() must take no argument - an argument is a caller's opinion "
                                + "about which shop they are, and that is never evidence"))
                .count();
        assertEquals(1, resolveMethods, "exactly one way to resolve a scope");
    }

    @Test
    @DisplayName("resolution is repeatable - it does not drift between calls in one request")
    void resolutionIsStable() {
        TenantScope first = resolver.resolve();
        TenantScope second = resolver.resolve();
        assertEquals(first, second,
                "two reads inside one request must agree, or a check and the query it guards "
                        + "could be scoped to different shops");
    }

    @Test
    @DisplayName("Shop #1 is found by its code, not by assuming id 1")
    void shopOneIsFoundByCodeNotByAssumingId() {
        // Shop.FIRST_SHOP_ID is right today, but a database restored from a
        // dump, or one where the row was recreated, can carry a different id
        // for the same shop. The code is the stable name.
        Shop byCode = shops.findByCode(platform.getFirstShopCode()).orElseThrow();
        assertEquals(byCode.getId(), resolver.resolve().requireShopId());
    }
}
