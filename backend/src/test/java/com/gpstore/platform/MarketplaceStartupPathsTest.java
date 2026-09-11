package com.gpstore.platform;

import com.gpstore.entity.Role;
import com.gpstore.security.AuthenticatedUser;
import com.gpstore.security.RolePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The three things a marketplace has to let somebody do BEFORE they have a shop.
 *
 * EVERY ONE OF THESE WAS BROKEN, and none of them could be. Under SINGLE_SHOP
 * TenantResolver falls back to Shop #1, so every request has a scope whether it
 * has earned one or not and none of these paths had ever run. Turning
 * MULTI_SHOP_PRODUCTION on with a real second shop is what made them run, and
 * they failed in the order a new user meets them:
 *
 *   1. A customer's shop is the nearest one that delivers to their ADDRESS. A
 *      customer with no address resolves to no shop, and the refusal reached
 *      every request they could make - including the one that would have saved
 *      the address. A new customer could do nothing at all.
 *
 *   2. A rider's credential lives on delivery_partners, so the login runs
 *      before there is anything to resolve a shop from - and the shop is on
 *      the row the login is about to find. No delivery worker could sign in.
 *
 *   3. A platform admin defining a catalogue variant is acting for the
 *      marketplace and owns no shelf, but the write auto-listed onto "the
 *      current shop" - 500, so the shared catalogue could not be added to.
 *
 * WHAT THEY HAVE IN COMMON is a chicken and egg: the thing that gives you a
 * shop cannot itself require one. That is the rule these tests hold.
 */
@SpringBootTest(properties = {
        "platform.mode=MULTI_SHOP_PRODUCTION",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000",
        "rate-limit.auth-per-minute=10000"
})
@AutoConfigureMockMvc
@DisplayName("What a marketplace must allow before anybody has a shop")
class MarketplaceStartupPathsTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;

    private final String tag = "start" + System.nanoTime();

    private Long customerWithNoAddress;
    private Long shopOwner;

    @BeforeEach
    void nobodyHasAnythingYet() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
        customerWithNoAddress = newAccount("newcomer", Role.CUSTOMER);
        shopOwner = newAccount("owner", Role.ADMIN);
        jdbc.update("INSERT INTO shop_staff (shop_id, customer_id, is_default, active) "
                        + "SELECT id, ?, true, true FROM shops WHERE code = ?",
                shopOwner, platform.getFirstShopCode());
    }

    @AfterEach
    void tidyUp() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        jdbc.update("DELETE FROM delivery_partners WHERE name LIKE ?", tag + "%");
        jdbc.update("DELETE FROM addresses WHERE customer_id IN (?, ?)",
                customerWithNoAddress, shopOwner);
        jdbc.update("DELETE FROM shop_staff WHERE customer_id = ?", shopOwner);
        jdbc.update("DELETE FROM customers WHERE id IN (?, ?)",
                customerWithNoAddress, shopOwner);
        TenantDefaults.install(PlatformMode.SINGLE_SHOP,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    @Test
    @DisplayName("a customer with no address can still save their first one")
    void theAddressThatEarnsAShopDoesNotRequireOne() throws Exception {
        MvcResult result = send(post("/api/addresses"), customerWithNoAddress, Role.CUSTOMER,
                """
                {"fullName":"Newcomer","mobileNumber":"9000000001","houseNo":"1",
                 "area":"Market Road","city":"Gorakhpur","state":"UP","pincode":"273001",
                 "country":"India","latitude":27.16231,"longitude":83.940468,
                 "defaultAddress":true}
                """);

        assertEquals(200, result.getResponse().getStatus(),
                "A NEW CUSTOMER COULD DO NOTHING AT ALL. Their shop comes from their address, "
                        + "so with no address they resolve to no shop - and that refusal reached "
                        + "the request that would have given them one: "
                        + result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("a hired rider can sign in to the worker app")
    void theWorkerLoginDoesNotRequireAShopEither() throws Exception {
        String email = tag + "-rider@gmail.com";
        String created = body(post("/api/admin/workers"), shopOwner, Role.ADMIN,
                """
                {"name":"%s rider","loginEmail":"%s","mobile":"8%d",
                 "password":"WorkerPass!2345","vehicleType":"BIKE","available":true}
                """.formatted(tag, email, 100000000 + (int) (Math.random() * 899999999)));
        assertTrue(created.contains("\"canSignIn\":true"),
                "the shop could not give the rider a login: " + created);

        // NO CREDENTIAL ON THIS REQUEST, which is the point: it IS the
        // credential check. It must reach WorkerAuthService rather than being
        // refused by the tenant filter for having no shop.
        MvcResult signIn = mockMvc.perform(post("/api/worker/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"%s\",\"password\":\"WorkerPass!2345\"}"
                                .formatted(email)))
                .andReturn();

        assertEquals(200, signIn.getResponse().getStatus(),
                "NO DELIVERY WORKER COULD SIGN IN. The shop is on the roster row this login is "
                        + "about to find, so requiring one first is a door locked from inside: "
                        + signIn.getResponse().getContentAsString());
        assertTrue(signIn.getResponse().getContentAsString().contains("accessToken"),
                "a successful worker sign-in returns a token: "
                        + signIn.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("bad worker credentials are still refused, by the auth service")
    void openingTheDoorDidNotUnlockIt() throws Exception {
        // The other half of the change above. Taking the tenant filter off a
        // route must not take the AUTHENTICATION off it.
        MvcResult signIn = mockMvc.perform(post("/api/worker/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + tag + "-nobody@gmail.com\","
                                + "\"password\":\"WrongPass!2345\"}"))
                .andReturn();

        assertEquals(401, signIn.getResponse().getStatus(),
                "an unknown rider must still be refused: "
                        + signIn.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------ fixtures

    private String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
                                request, Long accountId, Role role, String json) throws Exception {
        MvcResult result = send(request, accountId, role, json);
        int status = result.getResponse().getStatus();
        String content = result.getResponse().getContentAsString();
        assertTrue(status >= 200 && status < 300, request + " returned " + status + ": " + content);
        return content;
    }

    private MvcResult send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
                                   request, Long accountId, Role role, String json) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(tokenFor(accountId, role));
        try {
            return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(json))
                    .andReturn();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Long newAccount(String kind, Role role) {
        String email = tag + "-" + kind + "@example.test";
        jdbc.update("""
                INSERT INTO customers (full_name, email, mobile_number, password, role, active)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, true)
                """, kind + " " + tag, email,
                "9" + (100000000 + (int) (Math.random() * 899999999)), role.name());
        return jdbc.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
    }

    private UsernamePasswordAuthenticationToken tokenFor(Long accountId, Role role) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String authority : RolePermissions.authorityNames(role)) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(accountId, tag + "@example.test", role.name()),
                null, authorities);
    }
}
