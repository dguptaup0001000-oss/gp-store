package com.gpstore.platform;

import com.gpstore.entity.Role;
import com.gpstore.security.AuthenticatedUser;
import com.gpstore.security.RolePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * A second merchant must land in their OWN shop, in the mode production runs.
 *
 * WHY THIS TEST EXISTS, AND WHY IT IS IN SINGLE_SHOP. `platform.mode` is set
 * nowhere in this repository - not in application.properties, not in
 * application-prod.properties, not in the compose file, not in the deploy
 * workflow - so it takes its default, SINGLE_SHOP, in production.
 *
 * In that mode TenantResolver.resolve() returns Shop #1 to EVERY authenticated
 * account, on its first line, before it looks at membership at all:
 *
 *     if (!platform.getMode().requiresExplicitShopContext()) {
 *         return TenantScope.ofShop(firstShopId());
 *     }
 *
 * With one shop on the platform that is correct and harmless - Shop #1 is the
 * only answer there is. The moment a SECOND shop exists it stops being
 * harmless, because the second merchant's owner sends no X-Shop-Id on their
 * first request and resolves to somebody else's business.
 *
 * This test is deliberately written to FAIL LOUDLY if that is what happens,
 * rather than to document it.
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
@DisplayName("A second merchant's owner never lands in the first merchant's shop")
class SecondMerchantLandsInTheirOwnShopTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PlatformOnboardingService onboarding;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> madeShops = new ArrayList<>();
    private final List<Long> madeMerchants = new ArrayList<>();
    private final List<Long> madeCustomers = new ArrayList<>();

    private static String uniquePhone() {
        return "9" + String.format("%09d", Math.abs(System.nanoTime() % 1_000_000_000L));
    }

    private UsernamePasswordAuthenticationToken owner(Long customerId) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String authority : RolePermissions.authorityNames(Role.ADMIN)) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(customerId, "second@example.test", Role.ADMIN.name()),
                null, authorities);
    }

    @AfterEach
    void removeWhatThisMade() {
        for (Long shopId : madeShops) {
            jdbc.update("DELETE FROM shop_staff WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM audit_logs WHERE entity_id = ? AND entity_type = 'Shop'", shopId);
            jdbc.update("DELETE FROM shops WHERE id = ?", shopId);
        }
        for (Long merchantId : madeMerchants) {
            jdbc.update("DELETE FROM audit_logs WHERE entity_id = ? AND entity_type = 'Merchant'", merchantId);
            jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
        }
        for (Long customerId : madeCustomers) {
            jdbc.update("DELETE FROM refresh_tokens WHERE customer_id = ?", customerId);
            jdbc.update("DELETE FROM audit_logs WHERE entity_id = ? AND entity_type = 'Customer'", customerId);
            jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        }
        madeShops.clear();
        madeMerchants.clear();
        madeCustomers.clear();
    }

    @Test
    @DisplayName("their shop profile is theirs, not the first shop's")
    void theOwnerOfTheSecondShopSeesTheSecondShop() throws Exception {
        String tag = "second" + System.nanoTime();
        var made = onboarding.onboard("Gupta Hardware " + tag, "Owner " + tag,
                tag + "@example.test", uniquePhone(), null,
                26.7606, 83.3732, new BigDecimal("5.0"), null, false);
        madeMerchants.add(made.merchantId());
        madeShops.add(made.shopId());
        madeCustomers.add(made.ownerCustomerId());

        // NO X-Shop-Id, deliberately. This is the merchant's FIRST request
        // after signing in, before any switcher has been shown: the app has
        // nothing to put in the header yet. Whatever the server decides here
        // is what a real shopkeeper sees on their opening screen.
        MvcResult result = mockMvc.perform(get("/api/shop/profile")
                        .with(authentication(owner(made.ownerCustomerId()))))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"id\":" + made.shopId()),
                "The owner of shop " + made.shopId() + " asked for their own shop profile and the "
                        + "server answered with a different shop. In SINGLE_SHOP mode "
                        + "TenantResolver.resolve() returns Shop #1 to every account before it "
                        + "checks membership, so a second merchant reads the first merchant's "
                        + "business. Body was: " + body);
    }
}
