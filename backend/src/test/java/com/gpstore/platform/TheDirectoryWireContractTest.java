package com.gpstore.platform;

import com.gpstore.entity.Role;
import com.gpstore.security.AuthenticatedUser;
import com.gpstore.security.RolePermissions;

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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The JSON the Super Admin app actually receives.
 *
 * <h2>Why this file exists</h2>
 *
 * <p>The marketplace work shipped a backend whose every test passed while the
 * customer app could not add a single item to a cart. {@code addable} was a
 * derived accessor on a record, Jackson serialises records from their
 * components only, and the field simply never reached the wire. The Flutter
 * model read it with a safe {@code ?? false} default, so nothing crashed and
 * nothing logged - every card just drew the wrong button.
 *
 * <p>The lesson was not to remove the defaults. A null-hostile model would
 * have crashed the screen instead, which is worse. The lesson was that the
 * CONTRACT needs its own test: something that asserts on the serialised
 * response rather than on the Java object, key by key, for every key the app
 * reads.
 *
 * <p>So this is that test for the directory. It fails if a field is renamed
 * on either side, which is otherwise a silently blank screen rather than a
 * compile error.
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
@DisplayName("The directory wire contract")
class TheDirectoryWireContractTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();
    private final String tag = "wire" + System.nanoTime();
    private Long admin;
    private Long buyer;
    private Long merchantId;
    private Long shopId;

    @BeforeEach
    void data() {
        admin = customer("Wire Admin " + tag, tag + "-admin@example.test", "9300000001");
        jdbc.update("UPDATE customers SET role='PLATFORM_ADMIN' WHERE id=?", admin);
        buyer = customer("Wire Buyer " + tag, tag + "-buyer@example.test", "9300000002");

        jdbc.update("""
                INSERT INTO merchants
                    (legal_name,display_name,contact_email,contact_phone,contact_name,
                     owner_customer_id,status,active,is_demo,created_at,updated_at)
                VALUES (?,?,?,'9300000003',?,?,'ACTIVE',true,true,
                        CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, "Wire Legal " + tag, "Wire Shop " + tag,
                tag + "-m@example.test", "Wire Contact " + tag, buyer);
        merchantId = jdbc.queryForObject("SELECT id FROM merchants WHERE legal_name=?",
                Long.class, "Wire Legal " + tag);

        jdbc.update("""
                INSERT INTO shops
                    (merchant_id,code,display_name,status,active,is_demo,
                     latitude,longitude,max_delivery_radius_km,time_zone)
                VALUES (?,?,?,'ACTIVE',true,true,21.1,79.1,10,'Asia/Kolkata')
                """, merchantId, "wire-" + tag, "Wire Shop " + tag);
        shopId = jdbc.queryForObject("SELECT id FROM shops WHERE code=?",
                Long.class, "wire-" + tag);

        jdbc.update("""
                INSERT INTO orders
                    (order_number,shop_id,customer_id,total_amount,delivery_fee,
                     order_status,order_date)
                VALUES (?,?,?,?,25,'DELIVERED',CURRENT_TIMESTAMP)
                """, "GPS-W-" + tag, shopId, buyer, new BigDecimal("525"));
    }

    @AfterEach
    void tidyUp() {
        jdbc.update("DELETE FROM orders WHERE order_number=?", "GPS-W-" + tag);
        jdbc.update("DELETE FROM shops WHERE id=?", shopId);
        jdbc.update("DELETE FROM merchants WHERE id=?", merchantId);
        jdbc.update("DELETE FROM audit_logs WHERE entity_type='Customer' AND entity_id IN (?,?)",
                admin, buyer);
        jdbc.update("DELETE FROM customers WHERE id IN (?,?)", admin, buyer);
    }

    @Test
    @DisplayName("a merchant search row carries every key MerchantHit.fromJson reads")
    void merchantHitContract() throws Exception {
        JsonNode row = firstRow("/api/platform/control/merchants/search", "Wire Shop " + tag);

        for (String field : new String[] {
                "id", "merchantRef", "displayName", "legalName", "ownerName",
                "email", "phone", "status", "active", "shopCount", "createdAt"}) {
            assertTrue(row.has(field),
                    "MerchantHit.fromJson reads '" + field + "' and it is not on the wire. "
                            + "Its model default would absorb that silently, exactly as the "
                            + "marketplace's addable did: " + row);
        }
        // The values, not just the keys - a present-but-wrong field is the
        // same blank screen.
        assertTrue(row.get("shopCount").asInt() >= 1, row.toString());
        assertTrue(row.get("merchantRef").asText().startsWith("M-"), row.toString());
        assertTrue(row.get("ownerName").asText().contains(tag), row.toString());
    }

    @Test
    @DisplayName("a customer search row carries every key CustomerHit.fromJson reads")
    void customerHitContract() throws Exception {
        JsonNode row = firstRow("/api/platform/control/customers/search", "Wire Buyer " + tag);

        for (String field : new String[] {
                "id", "customerRef", "name", "email", "phone", "active",
                "orderCount", "createdAt", "lastOrderAt"}) {
            assertTrue(row.has(field),
                    "CustomerHit.fromJson reads '" + field + "' and it is not on the wire: " + row);
        }
        assertTrue(row.get("customerRef").asText().startsWith("C-"), row.toString());
        assertTrue(row.get("orderCount").asInt() >= 1, row.toString());
    }

    @Test
    @DisplayName("the page envelope carries the server's own total")
    void pageEnvelopeContract() throws Exception {
        JsonNode page = json.readTree(body(
                "/api/platform/control/customers/search", "Wire Buyer " + tag));
        for (String field : new String[] {
                "content", "page", "size", "totalElements", "totalPages"}) {
            assertTrue(page.has(field),
                    "DirectoryPage.fromJson reads '" + field + "': " + page);
        }
        assertTrue(page.get("totalElements").asInt() >= 1,
                "a screen counting content.length instead would say '20 matches' "
                        + "for every search on a real marketplace");
    }

    @Test
    @DisplayName("a merchant profile carries every section and every activity key")
    void merchantProfileContract() throws Exception {
        JsonNode profile = json.readTree(mockMvc.perform(
                        get("/api/platform/control/merchants/{id}/profile", merchantId)
                                .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        for (String section : new String[] {
                "core", "commerce", "workforce", "reputation", "activity", "security"}) {
            assertTrue(profile.has(section), "the screen renders '" + section + "': " + profile);
        }
        for (String field : new String[] {
                "firstOrderAt", "lastOrderAt", "lastListingUpdateAt", "lastAdminEventAt",
                "activeDaysInWindow", "sessionsMeasured", "note"}) {
            assertTrue(profile.get("activity").has(field),
                    "MerchantActivity.fromJson reads '" + field + "'");
        }
        // §8's honesty, asserted on the wire and not only in Java: the app
        // must be TOLD that active time is not measured, or it will draw a
        // zero and the operator will read it as "this merchant did nothing".
        assertFalse(profile.get("activity").get("sessionsMeasured").asBoolean(),
                "staff phones are deliberately not timed, so this must be false");
        assertTrue(profile.get("activity").get("note").asText().length() > 20,
                "the note is what stops a screen drawing a zero instead");

        for (String field : new String[] {
                "orderStatuses", "activeListings", "outOfStockListings", "onlineListings",
                "visitToBuyListings", "serviceListings", "activeOffers"}) {
            assertTrue(profile.get("commerce").has(field),
                    "the trading card renders '" + field + "'");
        }
    }

    @Test
    @DisplayName("a customer profile carries every section, and shop affinity keeps preferred separate")
    void customerProfileContract() throws Exception {
        JsonNode profile = json.readTree(mockMvc.perform(
                        get("/api/platform/control/customers/{id}/profile", buyer)
                                .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        for (String section : new String[] {
                "core", "addresses", "shops", "categories", "payments", "refunds",
                "reviews", "returns", "activity", "security"}) {
            assertTrue(profile.has(section), "the screen renders '" + section + "': " + profile);
        }

        JsonNode shop = profile.get("shops").get(0);
        for (String field : new String[] {
                "shopId", "shopName", "orders", "spent", "lastOrderAt", "preferred"}) {
            assertTrue(shop.has(field), "ShopAffinity.fromJson reads '" + field + "'");
        }
        // THE DISTINCTION, ON THE WIRE. This customer has an order at this
        // shop and never saved a preference, so the two fields must disagree.
        assertTrue(shop.get("orders").asInt() >= 1, shop.toString());
        assertFalse(shop.get("preferred").asBoolean(),
                "buying somewhere is not choosing it, and the wire must not blur that");

        for (String field : new String[] {
                "firstSessionAt", "lastSessionAt", "sessions", "totalSeconds",
                "activeDays", "lastOrderAt", "sessionsMeasured", "note"}) {
            assertTrue(profile.get("activity").has(field),
                    "CustomerActivity.fromJson reads '" + field + "'");
        }
    }

    @Test
    @DisplayName("no record shape in the profile package can carry a credential")
    void nothingInTheseShapesIsASecret() {
        for (Class<?> type : PlatformProfiles.class.getDeclaredClasses()) {
            if (!type.isRecord()) {
                continue;
            }
            for (RecordComponent component : type.getRecordComponents()) {
                String name = component.getName().toLowerCase(Locale.ROOT);

                // Substrings that cannot innocently appear in an operational
                // field name.
                for (String forbidden : new String[] {
                        "password", "otp", "secret", "token", "credential",
                        "cvv", "privatekey", "apikey", "activation", "passwordhash"}) {
                    assertFalse(name.contains(forbidden),
                            type.getSimpleName() + "." + component.getName()
                                    + " looks like a credential. Complete information does "
                                    + "not mean the things that let somebody become this "
                                    + "account, and a field is easier to add than to notice");
                }

                // "pin" MATCHED EXACTLY, not as a substring: an Indian postal
                // pincode is an address field and has nothing to do with a
                // payment PIN. A blanket contains("pin") fails on the honest
                // field and teaches the next person to weaken the whole rule
                // to get green, which is how a guard stops guarding.
                assertFalse(name.equals("pin") || name.endsWith("pin")
                                && !name.equals("pincode"),
                        type.getSimpleName() + "." + component.getName()
                                + " looks like a payment PIN");
            }
        }
    }

    // ------------------------------------------------------------- fixture

    private JsonNode firstRow(String path, String query) throws Exception {
        JsonNode page = json.readTree(body(path, query));
        assertTrue(page.get("content").size() > 0,
                "the fixture should have matched something for '" + query + "': " + page);
        return page.get("content").get(0);
    }

    private String body(String path, String query) throws Exception {
        return mockMvc.perform(get(path).param("q", query)
                        .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private Long customer(String name, String email, String mobile) {
        jdbc.update("""
                INSERT INTO customers
                    (full_name,email,mobile_number,password,role,enabled,active,verified,created_at)
                VALUES (?,?,?,'not-a-real-hash','CUSTOMER',true,true,true,CURRENT_TIMESTAMP)
                """, name, email, mobile);
        return jdbc.queryForObject("SELECT id FROM customers WHERE email=?", Long.class, email);
    }

    private UsernamePasswordAuthenticationToken token(Long id, Role role) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String authority : RolePermissions.authorityNames(role)) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(id, tag + "@example.test", role.name()), null, authorities);
    }
}
