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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Suspending a merchant has to actually stop them.
 *
 * WHAT THIS CLOSES. Suspension already cascaded to a merchant's shops and
 * already stopped customers ordering. It did not stop the MERCHANT:
 * ShopMembership.isOperable refuses only REMOVED and REJECTED, so the owner of
 * a suspended business kept full write access to their own back office -
 * prices, catalogue, orders, payouts - while the platform believed it had
 * stopped them. §32 of the specification says a suspended merchant may not
 * operate, and until this test it could.
 *
 * WHAT THIS DELIBERATELY DOES NOT DO, because getting enforcement wrong in the
 * other direction is its own harm: it does not lock the merchant out, hide
 * their records, or close the appeal. See ShopOperationGate.
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
@DisplayName("A suspended merchant may read and appeal, and may not trade")
class SuspendedMerchantIsReadOnlyTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PlatformOnboardingService onboarding;
    @Autowired private MerchantLifecycleService merchantLifecycle;
    @Autowired private ShopLifecycleService shopLifecycle;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> madeShops = new ArrayList<>();
    private final List<Long> madeMerchants = new ArrayList<>();
    private final List<Long> madeCustomers = new ArrayList<>();

    private String tag() {
        return "susp" + System.nanoTime();
    }

    private static String uniquePhone() {
        return "9" + String.format("%09d", Math.abs(System.nanoTime() % 1_000_000_000L));
    }

    private PlatformOnboardingService.OnboardedMerchant onboardOne(String tag) {
        var made = onboarding.onboard("Suspendable " + tag, "Owner " + tag,
                tag + "@example.test", uniquePhone(), null,
                26.7606, 83.3732, new BigDecimal("5.0"), null, false);
        madeMerchants.add(made.merchantId());
        madeShops.add(made.shopId());
        madeCustomers.add(made.ownerCustomerId());
        return made;
    }

    /** The merchant's own owner account, as their app authenticates. */
    private UsernamePasswordAuthenticationToken owner(Long customerId) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String authority : RolePermissions.authorityNames(Role.ADMIN)) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(customerId, "owner@example.test", Role.ADMIN.name()),
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
            jdbc.update("DELETE FROM audit_logs WHERE entity_id = ? AND entity_type = 'Merchant'",
                    merchantId);
            jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
        }
        for (Long customerId : madeCustomers) {
            jdbc.update("DELETE FROM refresh_tokens WHERE customer_id = ?", customerId);
            jdbc.update("DELETE FROM audit_logs WHERE entity_id = ? AND entity_type = 'Customer'",
                    customerId);
            jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        }
        madeShops.clear();
        madeMerchants.clear();
        madeCustomers.clear();
    }

    @Test
    @DisplayName("a suspended merchant cannot change their own shop")
    void suspensionStopsWrites() throws Exception {
        var made = onboardOne(tag());
        merchantLifecycle.transition(made.merchantId(), MerchantStatus.ACTIVE, "trading");
        merchantLifecycle.transition(made.merchantId(), MerchantStatus.SUSPENDED,
                "selling goods they did not have");

        mockMvc.perform(put("/api/shop/profile")
                        .with(authentication(owner(made.ownerCustomerId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Renamed While Suspended\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a suspended merchant can still read their own records")
    void suspensionDoesNotHideTheirOwnBusinessFromThem() throws Exception {
        var made = onboardOne(tag());
        merchantLifecycle.transition(made.merchantId(), MerchantStatus.ACTIVE, "trading");
        merchantLifecycle.transition(made.merchantId(), MerchantStatus.SUSPENDED, "enforcement");

        // NOT A KINDNESS - THE RECORD IS THEIRS. Suspension must not quietly
        // become deletion (§4), and a merchant who cannot see their own orders
        // cannot answer for them either.
        mockMvc.perform(get("/api/shop/profile")
                        .with(authentication(owner(made.ownerCustomerId()))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a paused merchant keeps working, because a pause is not a punishment")
    void pausingIsNotSuspending() throws Exception {
        var made = onboardOne(tag());
        merchantLifecycle.transition(made.merchantId(), MerchantStatus.ACTIVE, "trading");
        merchantLifecycle.transition(made.merchantId(), MerchantStatus.PAUSED,
                "closed for Diwali");

        // A shutter down for a festival must not take the shopkeeper's own
        // back office away from them - they are getting ready to reopen.
        mockMvc.perform(put("/api/shop/profile")
                        .with(authentication(owner(made.ownerCustomerId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Back On Monday\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("pausing a merchant pauses its shops, and does not suspend them")
    void aPauseIsRecordedAsAPause() {
        var made = onboardOne(tag());
        merchantLifecycle.transition(made.merchantId(), MerchantStatus.ACTIVE, "trading");
        // THE SHOP HAS TO BE OPEN BEFORE PAUSING IT MEANS ANYTHING. onboard
        // deliberately leaves a new storefront in DRAFT - empty shelves are
        // not a shop customers should find - and the cascade only moves shops
        // that were actually trading. A DRAFT shop staying DRAFT through a
        // merchant pause is correct, not a miss.
        shopLifecycle.transitionAsPlatform(made.shopId(), ShopStatus.ACTIVE, "stocked and open");
        merchantLifecycle.transition(made.merchantId(), MerchantStatus.PAUSED, "festival");

        Shop shop = shops.findById(made.shopId()).orElseThrow();
        // RECORDING A PAUSE AS A SUSPENSION PUTS AN ACCUSATION IN A MERCHANT'S
        // PERMANENT RECORD for closing over a holiday - and that record is
        // what an appeal is later argued from.
        assertEquals(ShopStatus.PAUSED, shop.getStatus());
        assertEquals(MerchantStatus.PAUSED,
                merchants.findById(made.merchantId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("a pause can be lifted, and can still be escalated to a suspension")
    void pauseIsNotAShelter() {
        assertTrue(MerchantStatus.PAUSED.canMoveTo(MerchantStatus.ACTIVE));
        // A merchant who pauses after an enforcement action was opened must
        // still be reachable by it.
        assertTrue(MerchantStatus.PAUSED.canMoveTo(MerchantStatus.SUSPENDED));
        // And lifting enforcement is a decision to let a business trade again,
        // not a downgrade to "they are just closed today".
        assertTrue(!MerchantStatus.SUSPENDED.canMoveTo(MerchantStatus.PAUSED));
        assertTrue(!MerchantStatus.PAUSED.canTrade());
    }
}
