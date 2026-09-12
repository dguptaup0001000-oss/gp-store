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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Under a marketplace, a basket says who each part of it is with.
 *
 * THE OTHER HALF OF AppContractsCarryTheShopTest. That one runs under
 * SINGLE_SHOP and asserts the names are NOT fetched - there is nobody to
 * distinguish from, nothing displays a name, and a lookup per cart read on the
 * most-called authenticated endpoint is a cost with no benefit
 * (CheckoutPerformanceTest refuses it).
 *
 * Here the opposite has to hold. A customer buying from two kiranas has no way
 * to know which lines are with which until told, and "delivered separately"
 * has to be said before they pay rather than discovered afterwards.
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
@DisplayName("A marketplace basket names the shops it spans")
class MarketplaceCartLabelsTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;

    private final String tag = "lbl" + System.nanoTime();

    private long shopA;
    private long shopB;
    private Long merchantB;
    private Long customerId;
    private Long cartId;

    @BeforeEach
    void aBasketFromTwoKiranas() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
        shopA = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();

        Merchant second = new Merchant();
        second.setLegalName("Label fixture " + tag);
        second.setDisplayName("Sharma Kirana " + tag);
        second.setStatus(MerchantStatus.ACTIVE);
        second.setIsDemo(Boolean.TRUE);
        second.setActive(Boolean.TRUE);
        merchantB = merchants.save(second).getId();

        Shop b = new Shop();
        b.setMerchantId(merchantB);
        b.setCode("LBL-" + tag);
        b.setDisplayName("Sharma Kirana " + tag);
        b.setStatus(ShopStatus.ACTIVE);
        b.setIsDemo(Boolean.TRUE);
        b.setActive(Boolean.TRUE);
        shopB = shops.save(b).getId();

        jdbc.update("""
                INSERT INTO customers (full_name, email, mobile_number, password, role, active)
                VALUES (?, ?, ?, 'not-a-real-hash', 'CUSTOMER', true)
                """, "Label fixture " + tag, tag + "@example.test",
                "9" + (100000000 + (int) (Math.random() * 899999999)));
        customerId = jdbc.queryForObject(
                "SELECT id FROM customers WHERE email = ?", Long.class, tag + "@example.test");

        Long variantId = jdbc.queryForObject(
                "SELECT id FROM product_variants ORDER BY id LIMIT 1", Long.class);
        Long otherVariantId = jdbc.queryForObject(
                "SELECT id FROM product_variants ORDER BY id OFFSET 1 LIMIT 1", Long.class);

        jdbc.update("INSERT INTO carts (customer_id) VALUES (?)", customerId);
        cartId = jdbc.queryForObject(
                "SELECT id FROM carts WHERE customer_id = ?", Long.class, customerId);
        insertLine(variantId, shopA);
        insertLine(otherVariantId, shopB);
    }

    @AfterEach
    void tidyUp() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        jdbc.update("DELETE FROM cart_items WHERE cart_id = ?", cartId);
        jdbc.update("DELETE FROM carts WHERE id = ?", cartId);
        jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM shops WHERE id = ?", shopB);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantB);
        TenantDefaults.install(PlatformMode.SINGLE_SHOP,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    @Test
    @DisplayName("every line names its shop, and the basket names both shops")
    void theBasketCanBeGroupedAndLabelled() throws Exception {
        // THE STOREFRONT THE CUSTOMER OPENED, exactly as the app sends it.
        // Under a marketplace a customer with no serviceable address on file
        // resolves to no shop at all - fail-closed, and correct - so browsing
        // one is how they get a scope. The header can only NARROW to a shop
        // the marketplace already shows them (§78); it grants nothing.
        String body = mockMvc.perform(get("/api/carts/mine")
                        .header("X-Shop-Id", String.valueOf(shopA))
                        .with(authentication(as(Role.CUSTOMER))))
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"shopId\":" + shopA)
                        && body.contains("\"shopId\":" + shopB),
                "both shops must appear, or the app cannot group a basket it is about to split "
                        + "into two orders: " + body);
        assertTrue(body.contains("Sharma Kirana " + tag),
                "the second kirana's name must be sent, or the group header shows a number: "
                        + body);
    }

    private void insertLine(Long variantId, long shopId) {
        jdbc.update("""
                INSERT INTO cart_items (cart_id, product_variant_id, quantity, price, total_price,
                                        shop_id)
                VALUES (?, ?, 1, 40.00, 40.00, ?)
                """, cartId, variantId, shopId);
    }

    private UsernamePasswordAuthenticationToken as(Role role) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String authority : RolePermissions.authorityNames(role)) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(customerId, tag + "@example.test", role.name()),
                null, authorities);
    }
}
