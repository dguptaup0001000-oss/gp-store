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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The business is not the shop, and the staff of a shop are not the business.
 *
 * §12 asks for merchant-level and shop-level data to stay apart, and says
 * plainly that merchant-level information must not reach shop staff. That is
 * not a tidiness rule: a merchant's standing with the platform - whether they
 * are suspended, what the reason was - is the sort of thing an order manager
 * hired last week has no business reading, and would certainly repeat.
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
@DisplayName("Merchant-level data reaches the owner and stops there")
class MerchantLevelDataIsNotShopDataTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PlatformOnboardingService onboarding;
    @Autowired private ShopMembership membership;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> madeShops = new ArrayList<>();
    private final List<Long> madeMerchants = new ArrayList<>();
    private final List<Long> madeCustomers = new ArrayList<>();

    private static String uniquePhone() {
        return "9" + String.format("%09d", Math.abs(System.nanoTime() % 1_000_000_000L));
    }

    private PlatformOnboardingService.OnboardedMerchant onboardOne() {
        String tag = "mlevel" + System.nanoTime();
        var made = onboarding.onboard("Deepak Enterprises " + tag, "Deepak " + tag,
                tag + "@example.test", uniquePhone(), null,
                26.7606, 83.3732, new BigDecimal("5.0"), null, false);
        madeMerchants.add(made.merchantId());
        madeShops.add(made.shopId());
        madeCustomers.add(made.ownerCustomerId());
        return made;
    }

    private UsernamePasswordAuthenticationToken account(Long customerId, Role role) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String authority : RolePermissions.authorityNames(role)) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(customerId, "person@example.test", role.name()),
                null, authorities);
    }

    /** Somebody who works in the shop and does not own the business. */
    private Long hireInto(Long shopId, Role role) {
        String email = "staff" + System.nanoTime() + "@example.test";
        jdbc.update("INSERT INTO customers (full_name, email, mobile_number, role, active, "
                        + "enabled, verified, password) VALUES (?, ?, ?, ?, true, true, false, ?)",
                "Hired Hand", email, uniquePhone(), role.name(), "{noop}x");
        Long id = jdbc.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
        madeCustomers.add(id);
        membership.grant(shopId, id, false);
        return id;
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
            jdbc.update("DELETE FROM shop_staff WHERE customer_id = ?", customerId);
            jdbc.update("DELETE FROM refresh_tokens WHERE customer_id = ?", customerId);
            jdbc.update("DELETE FROM audit_logs WHERE entity_id = ? AND entity_type = 'Customer'", customerId);
            jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        }
        madeShops.clear();
        madeMerchants.clear();
        madeCustomers.clear();
    }

    @Test
    @DisplayName("the owner sees the business, with its reference and its shop count")
    void theOwnerSeesTheBusiness() throws Exception {
        var made = onboardOne();

        mockMvc.perform(get("/api/shop/merchant")
                        .header("X-Shop-Id", String.valueOf(made.shopId()))
                        .with(authentication(account(made.ownerCustomerId(), Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(made.merchantId()))
                .andExpect(jsonPath("$.merchantRef").value(PublicIds.merchant(made.merchantId())))
                .andExpect(jsonPath("$.shopCount").value(1));
    }

    @Test
    @DisplayName("an order manager at the same shop is refused the business account")
    void shopStaffAreNotTheBusiness() throws Exception {
        var made = onboardOne();
        Long orderManager = hireInto(made.shopId(), Role.ORDER_MANAGER);

        // THEY HOLD REAL SHOP PERMISSIONS - this is not a test that an
        // unauthorised stranger is refused. It is that holding the shop does
        // not mean holding the business, which is exactly the confusion §12
        // exists to prevent.
        mockMvc.perform(get("/api/shop/merchant")
                        .header("X-Shop-Id", String.valueOf(made.shopId()))
                        .with(authentication(account(orderManager, Role.ORDER_MANAGER))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a manager at the same shop is refused it too")
    void notEvenAManager() throws Exception {
        var made = onboardOne();
        Long manager = hireInto(made.shopId(), Role.MANAGER);

        // A MANAGER HOLDS ALMOST EVERYTHING A SHOP CAN GRANT, including
        // ANALYTICS_VIEW and the money that comes with it. The business
        // account is still not theirs: the check is ownership of the merchant,
        // not the size of a permission set, because a permission set is
        // something the shop can hand out and ownership is not.
        mockMvc.perform(get("/api/shop/merchant")
                        .header("X-Shop-Id", String.valueOf(made.shopId()))
                        .with(authentication(account(manager, Role.MANAGER))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("another merchant's owner cannot read this business")
    void ownersAreOwnersOfTheirOwnBusinessOnly() throws Exception {
        var mine = onboardOne();
        var theirs = onboardOne();

        // Naming somebody else's shop in the header is refused before it gets
        // anywhere near the merchant row - TenantResolver.select only grants a
        // shop the credential already permits.
        mockMvc.perform(get("/api/shop/merchant")
                        .header("X-Shop-Id", String.valueOf(theirs.shopId()))
                        .with(authentication(account(mine.ownerCustomerId(), Role.ADMIN))))
                .andExpect(status().isForbidden());
    }
}
