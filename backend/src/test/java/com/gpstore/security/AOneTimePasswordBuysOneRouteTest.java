package com.gpstore.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpstore.entity.Role;
import com.gpstore.auth.OtpPurpose;
import com.gpstore.platform.PlatformStaffService;
import com.gpstore.platform.PlatformProperties;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.TenantContextFilter;
import com.gpstore.service.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An account still on the password somebody else chose reaches exactly one route.
 *
 * WHY THIS IS ENFORCED IN THE FILTER AND NOT IN THE APP. A client that
 * merely SHOWS a change-password screen can be navigated around - deep link,
 * an older build, a hand-built request - and the account would then be
 * working normally on a credential the platform owner also holds. Every
 * action it took would be deniable, which is the exact property the forced
 * change exists to remove.
 *
 * So the refusal lives in JwtFilter, beside the live active/role recheck
 * that is already there, and these tests attack it over real HTTP rather
 * than trusting the flag.
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
@DisplayName("A one-time password buys one route and no others")
class AOneTimePasswordBuysOneRouteTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PlatformStaffService staff;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private com.gpstore.service.AuthService authService;
    @Autowired private com.gpstore.otp.OtpProvider otpProvider;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;

    private final List<Long> opened = new ArrayList<>();
    private final String tag = "onetime" + System.nanoTime();

    private record Opened(PlatformStaffService.OpenedAccount account, String token) {}

    private Opened openMerchant() {
        PlatformStaffService.OpenedAccount account = staff.openAccount(
                "Merchant " + tag, tag + "@example.test", null, Role.ADMIN);
        opened.add(account.customerId());
        // Exactly the token login would mint: the account is real, active,
        // and its role is ADMIN. Nothing about the JWT says it owes a
        // change - the filter reads that from the live row, which is the
        // whole point.
        String token = jwtService.generateToken(
                account.customerId(), account.email(), Role.ADMIN);
        return new Opened(account, token);
    }

    @AfterEach
    void removeWhatThisMade() {
        for (Long id : opened) {
            jdbc.update("DELETE FROM refresh_tokens WHERE customer_id = ?", id);
            jdbc.update("DELETE FROM password_reset_tokens WHERE customer_id = ?", id);
            jdbc.update("DELETE FROM audit_logs WHERE entity_id = ? AND entity_type = 'Customer'", id);
            jdbc.update("DELETE FROM customers WHERE id = ?", id);
        }
        opened.clear();
    }

    @Test
    @DisplayName("every route but the password change is refused, with a code the app can route on")
    void everythingElseIsRefused() throws Exception {
        Opened merchant = openMerchant();

        // A spread on purpose: the merchant's own back office, a shop-admin
        // surface, a plain customer surface, and the platform console. If
        // ANY of them answered, the account would be usable on a shared
        // credential.
        for (String path : new String[] {
                "/api/shop/profile",
                "/api/shop/listings",
                "/api/orders/my-orders",
                "/api/admin/workers",
                "/api/platform/merchants"}) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + merchant.token()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        }
    }

    @Test
    @DisplayName("403 and not 401, so a signed-in merchant is not bounced to the login screen")
    void itIsNotAnAuthenticationFailure() throws Exception {
        Opened merchant = openMerchant();

        // THE TOKEN IS VALID AND THE ACCOUNT IS LIVE. What is missing is a
        // step the account owes. A 401 here would send a correctly
        // signed-in merchant back to a login that succeeds, and round the
        // loop again.
        mockMvc.perform(get("/api/shop/profile")
                        .header("Authorization", "Bearer " + merchant.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("the change itself is allowed, and afterwards everything works")
    void theChangeIsAllowedAndOpensTheApp() throws Exception {
        Opened merchant = openMerchant();

        mockMvc.perform(put("/api/auth/change-password")
                        .header("Authorization", "Bearer " + merchant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s","newPassword":"TheirOwnPass42"}
                                """.formatted(merchant.account().oneTimePassword())))
                .andExpect(status().isOk());

        // The same token, now on an account that owes nothing.
        //
        // THE PROBE MOVED, AND THE REASON IS THE SAME ONE IT WAS CHOSEN FOR.
        // It used to be /api/orders/my-orders, picked because /api/shop/**
        // "would refuse it for a legitimate second reason and prove nothing".
        // That second reason grew: Order is a ShopOwned entity, so my-orders
        // needs a tenant scope, and an account on nobody's staff list has no
        // shop to resolve to once the platform mode is the marketplace. It was
        // only ever 200 here because SINGLE_SHOP resolved every credential into
        // Shop #1.
        //
        // /api/addresses/mine has neither confound: it is in
        // TenantContextFilter.spansEveryShop, so it needs no shop, and Address
        // is keyed on the customer id rather than being shop-owned. It is
        // blocked by the password gate above like everything else and opens the
        // moment the gate lifts - which is the only thing this test is about.
        mockMvc.perform(get("/api/addresses/mine")
                        .header("Authorization", "Bearer " + merchant.token()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("ACTIVE and ADMIN alone do not create merchant or shop authority")
    void anActiveAdminWithoutShopMembershipIsNotAMerchant() throws Exception {
        Opened account = openMerchant();

        mockMvc.perform(put("/api/auth/change-password")
                        .header("Authorization", "Bearer " + account.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s","newPassword":"TheirOwnPass42"}
                                """.formatted(account.account().oneTimePassword())))
                .andExpect(status().isOk());

        // The account is enabled, active and has the ADMIN role, and it owes
        // no password change. It still has no merchant/shop membership, so a
        // role string or the customer account's ACTIVE flag must not become a
        // tenant grant.
        mockMvc.perform(get("/api/shop/profile")
                        .header("Authorization", "Bearer " + account.token()))
                .andExpect(status().isForbidden());

        Long firstShop = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();
        mockMvc.perform(get("/api/shop/profile")
                        .header("Authorization", "Bearer " + account.token())
                        .header(TenantContextFilter.SHOP_HEADER, firstShop.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a wrong current password does not lift the gate")
    void aFailedChangeKeepsTheGateClosed() throws Exception {
        Opened merchant = openMerchant();

        mockMvc.perform(put("/api/auth/change-password")
                        .header("Authorization", "Bearer " + merchant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"NotTheOneTime9","newPassword":"TheirOwnPass42"}
                                """))
                .andExpect(result ->
                        org.junit.jupiter.api.Assertions.assertNotEquals(
                                200, result.getResponse().getStatus(),
                                "guessing the current password must not succeed"));

        mockMvc.perform(get("/api/orders/my-orders")
                        .header("Authorization", "Bearer " + merchant.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    }

    @Test
    @DisplayName("a reset closes the gate again on an account that had opened it")
    void aResetPutsTheGateBack() throws Exception {
        Opened merchant = openMerchant();

        mockMvc.perform(put("/api/auth/change-password")
                        .header("Authorization", "Bearer " + merchant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s","newPassword":"TheirOwnPass42"}
                                """.formatted(merchant.account().oneTimePassword())))
                .andExpect(status().isOk());

        staff.resetPassword(merchant.account().customerId());

        // A FRESH TOKEN, because the reset revoked the old session on
        // purpose. The gate has to be back for the new one too.
        String after = jwtService.generateToken(
                merchant.account().customerId(), merchant.account().email(), Role.ADMIN);
        mockMvc.perform(get("/api/orders/my-orders")
                        .header("Authorization", "Bearer " + after))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    }

    @Test
    @DisplayName("login tells the app it owes a change, because the app cannot ask")
    void loginSaysSo() throws Exception {
        Opened merchant = openMerchant();

        // WHY THE LOGIN RESPONSE AND NOT A LOOKUP. While the gate is closed,
        // /api/customers/me answers 403 like everything else, so there is no
        // request the app could make to discover this. If login did not say
        // it, the merchant would land on a console whose every call fails.
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","activationCode":"%s"}
                                """.formatted(
                                merchant.account().email(),
                                merchant.account().oneTimePassword(),
                                // THE FIRST LOGIN NOW TAKES BOTH HALVES. The
                                // account has never been claimed, so the
                                // activation code is required exactly once -
                                // which does not change what this test is
                                // about: that login REPORTS the owed password
                                // change, because no later request can ask.
                                merchant.account().activationCode())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andReturn().getResponse().getContentAsString();

        // The one-time password really is the login - not just a row in the
        // database that the platform console printed once.
        org.junit.jupiter.api.Assertions.assertTrue(
                objectMapper.readTree(body).get("token").asText().length() > 20,
                "login must still issue a usable token; the gate is a redirect, not a refusal to sign in");

        mockMvc.perform(put("/api/auth/change-password")
                        .header("Authorization", "Bearer " + merchant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s","newPassword":"TheirOwnPass42"}
                                """.formatted(merchant.account().oneTimePassword())))
                .andExpect(status().isOk());

        // AND IT STOPS SAYING SO. A flag that never clears would strand the
        // merchant on the password screen every single time they sign in.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"TheirOwnPass42"}
                                """.formatted(merchant.account().email())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false));
    }

    @Test
    @DisplayName("a reset-token reset also lifts the gate, so a forgotten slip is not a lockout")
    void theResetTokenPathAlsoLiftsIt() throws Exception {
        Opened merchant = openMerchant();

        // THE PATH A MERCHANT WHO LOST THE HANDOVER SLIP ACTUALLY TAKES.
        // They cannot use change-password - that needs the one-time password
        // they no longer have - so they go through "forgot password". If that
        // route left the flag set, they would arrive at a change screen with
        // nothing to type in the first field and no way past it: a permanent
        // lockout created by the very feature meant to protect them.
        authService.requestPasswordResetOtp(merchant.account().email());
        String otp = otpProvider
                .peekIssuedOtpForTests(merchant.account().email(), OtpPurpose.PASSWORD_RESET)
                .orElseThrow(() -> new AssertionError("no reset OTP was issued for the staff account"));
        var reset = authService.verifyPasswordResetOtp(merchant.account().email(), otp);
        authService.completePasswordReset(reset.getResetToken(), "AfterTheReset88");

        String after = jwtService.generateToken(
                merchant.account().customerId(), merchant.account().email(), Role.ADMIN);
        // Same probe, same reason - see theChangeIsAllowedAndOpensTheApp.
        mockMvc.perform(get("/api/addresses/mine")
                        .header("Authorization", "Bearer " + after))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"AfterTheReset88"}
                                """.formatted(merchant.account().email())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false));
    }

    @Test
    @DisplayName("an ordinary account is not caught by any of this")
    void anOrdinaryAccountIsUnaffected() throws Exception {
        long stamp = System.nanoTime();
        String email = "plain-" + stamp + "@example.test";
        String phone = "9" + String.format("%09d", Math.abs(stamp % 1_000_000_000L));

        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Plain Shopper","email":"%s","phone":"%s","password":"Passw0rd!23"}
                                """.formatted(email, phone)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        opened.add(json.get("customerId").asLong());

        // NULL MEANS FALSE, and this is the regression that matters most on
        // a live database: every account that existed before the column must
        // keep working. A default of true would have locked out the entire
        // customer base on the next deploy.
        mockMvc.perform(get("/api/orders/my-orders")
                        .header("Authorization", "Bearer " + json.get("token").asText()))
                .andExpect(status().isOk());
    }
}
