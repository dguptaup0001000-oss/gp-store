package com.gpstore.platform;

import com.gpstore.entity.Role;
import com.gpstore.security.AuthenticatedUser;
import com.gpstore.security.RolePermissions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Super Admin's merchant and customer directory.
 *
 * <p>An operator with somebody on the phone has a name, half a phone number or
 * an order reference, and needs the right record in one search and one tap.
 * These are the things that has to be true for that to be safe.
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
@DisplayName("The Super Admin directory")
class SuperAdminDirectoryTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    private final String tag = "dir" + System.nanoTime();
    private Long admin;
    private Long shopper;
    private Long ownerAccount;
    private Long merchantId;
    private Long shopId;
    private Long otherMerchantId;
    private Long otherShopId;

    @BeforeEach
    void aMerchantAShopAndACustomer() {
        admin = customer("Platform Owner " + tag, tag + "-admin@example.test", "9000000001");
        jdbc.update("UPDATE customers SET role='PLATFORM_ADMIN' WHERE id=?", admin);

        shopper = customer("Anita Verma " + tag, tag + "-anita@example.test", "9876543210");
        ownerAccount = customer("Deepak Kumar " + tag, tag + "-deepak@example.test", "9811122233");

        merchantId = merchant("Deepak Phone Shop " + tag, "Deepak Traders " + tag,
                tag + "-shop@example.test", "9811122233", ownerAccount);
        shopId = shop(merchantId, "deepak-main-" + tag, "Deepak Phone Shop " + tag,
                "Deepak Traders Pvt Ltd " + tag);

        otherMerchantId = merchant("Raju Kirana " + tag, "Raju Stores " + tag,
                tag + "-raju@example.test", "9700000000", null);
        otherShopId = shop(otherMerchantId, "raju-main-" + tag, "Raju Kirana " + tag, null);
    }

    @AfterEach
    void tidyUp() {
        for (Long s : new Long[] {shopId, otherShopId}) {
            jdbc.update("DELETE FROM orders WHERE shop_id=?", s);
            jdbc.update("DELETE FROM shops WHERE id=?", s);
        }
        jdbc.update("DELETE FROM merchants WHERE id IN (?,?)", merchantId, otherMerchantId);
        jdbc.update("DELETE FROM audit_logs WHERE entity_type='Customer' AND entity_id IN (?,?,?)",
                admin, shopper, ownerAccount);
        jdbc.update("DELETE FROM customers WHERE id IN (?,?,?)", admin, shopper, ownerAccount);
    }

    // =================================================== who may look at all

    @Nested
    @DisplayName("Who may look")
    class Authorization {

        @Test
        @DisplayName("a customer is refused both directories and both profiles")
        void anOrdinaryCustomerIsRefused() throws Exception {
            for (String path : deniablePaths()) {
                mockMvc.perform(get(path).param("q", "deepak")
                                .with(authentication(token(shopper, Role.CUSTOMER))))
                        .andExpect(status().isForbidden());
            }
        }

        @Test
        @DisplayName("a merchant admin is refused - their own shop is not the platform")
        void aMerchantIsRefused() throws Exception {
            for (String path : deniablePaths()) {
                mockMvc.perform(get(path).param("q", "deepak")
                                .with(authentication(token(ownerAccount, Role.ADMIN))))
                        .andExpect(status().isForbidden());
            }
        }

        @Test
        @DisplayName("a worker is refused")
        void aWorkerIsRefused() throws Exception {
            for (String path : deniablePaths()) {
                mockMvc.perform(get(path).param("q", "deepak")
                                .with(authentication(token(shopper, Role.DELIVERY_BOY))))
                        .andExpect(status().isForbidden());
            }
        }

        @Test
        @DisplayName("an anonymous caller is refused before anything is read")
        void anonymousIsRefused() throws Exception {
            for (String path : deniablePaths()) {
                mockMvc.perform(get(path).param("q", "deepak"))
                        .andExpect(status().is4xxClientError());
            }
        }

        @Test
        @DisplayName("X-Shop-Id cannot turn a merchant into a platform admin")
        void aHeaderNeverGrantsAScope() throws Exception {
            // The merchant owns this shop, so the header names something they
            // genuinely have. It still must not widen what they may reach.
            mockMvc.perform(get("/api/platform/control/merchants/{id}/profile", merchantId)
                            .header("X-Shop-Id", String.valueOf(shopId))
                            .with(authentication(token(ownerAccount, Role.ADMIN))))
                    .andExpect(status().isForbidden());

            mockMvc.perform(get("/api/platform/control/customers/{id}/profile", shopper)
                            .header("X-Shop-Id", String.valueOf(shopId))
                            .with(authentication(token(ownerAccount, Role.ADMIN))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("the platform admin is allowed")
        void thePlatformAdminIsAllowed() throws Exception {
            mockMvc.perform(get("/api/platform/control/merchants/search").param("q", "deepak")
                            .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/platform/control/customers/search").param("q", "anita")
                            .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                    .andExpect(status().isOk());
        }

        private List<String> deniablePaths() {
            return List.of(
                    "/api/platform/control/merchants/search",
                    "/api/platform/control/customers/search",
                    "/api/platform/control/merchants/" + merchantId + "/profile",
                    "/api/platform/control/customers/" + shopper + "/profile");
        }
    }

    // ======================================================= finding people

    @Nested
    @DisplayName("Finding a merchant")
    class MerchantSearch {

        @Test
        @DisplayName("by display name, legal name, owner, email and merchant reference")
        void theObviousWaysAllWork() throws Exception {
            for (String term : List.of("Deepak Phone", "Deepak Traders", "Deepak Kumar",
                    tag + "-shop@example.test", "M-" + merchantId)) {
                mockMvc.perform(get("/api/platform/control/merchants/search").param("q", term)
                                .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.content[?(@.id == " + merchantId + ")]").exists());
            }
        }

        @Test
        @DisplayName("by the shop, because that is often all the caller remembers")
        void theShopFindsTheMerchantBehindIt() throws Exception {
            for (String term : List.of("deepak-main-" + tag, "Deepak Traders Pvt Ltd")) {
                mockMvc.perform(get("/api/platform/control/merchants/search").param("q", term)
                                .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.content[0].id").value(merchantId));
            }
        }

        @Test
        @DisplayName("a merchant with shops is one row, not one row per shop")
        void theShopMatchDoesNotMultiplyTheMerchant() throws Exception {
            shop(merchantId, "deepak-second-" + tag, "Deepak Phone Shop Two " + tag, null);
            try {
                mockMvc.perform(get("/api/platform/control/merchants/search")
                                .param("q", "Deepak Phone")
                                .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.content.length()").value(1))
                        .andExpect(jsonPath("$.content[0].shopCount").value(2));
            } finally {
                jdbc.update("DELETE FROM shops WHERE code=?", "deepak-second-" + tag);
            }
        }

        @Test
        @DisplayName("the row carries what an operator needs to tell two Deepaks apart")
        void theRowIsUseful() throws Exception {
            mockMvc.perform(get("/api/platform/control/merchants/search").param("q", "Deepak Phone")
                            .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].merchantRef").value("M-" + merchantId))
                    .andExpect(jsonPath("$.content[0].ownerName").value("Deepak Kumar " + tag))
                    .andExpect(jsonPath("$.content[0].email").value(tag + "-shop@example.test"))
                    .andExpect(jsonPath("$.content[0].shopCount").value(1))
                    .andExpect(jsonPath("$.content[0].status").exists());
        }

        @Test
        @DisplayName("nothing found is an empty page, not an error")
        void noResultsIsAnAnswer() throws Exception {
            mockMvc.perform(get("/api/platform/control/merchants/search")
                            .param("q", "zzzz-nobody-" + tag)
                            .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(0))
                    .andExpect(jsonPath("$.totalElements").value(0));
        }

        @Test
        @DisplayName("one character is refused rather than returning the whole platform")
        void aSingleCharacterIsRefused() throws Exception {
            mockMvc.perform(get("/api/platform/control/merchants/search").param("q", "a")
                            .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Finding a customer")
    class CustomerSearch {

        @Test
        @DisplayName("by name, email, reference and bare id")
        void theObviousWaysAllWork() throws Exception {
            for (String term : List.of("Anita", tag + "-anita@example.test",
                    "C-" + shopper, String.valueOf(shopper))) {
                mockMvc.perform(get("/api/platform/control/customers/search").param("q", term)
                                .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.content[?(@.id == " + shopper + ")]").exists());
            }
        }

        @Test
        @DisplayName("a phone number in any shape people write it")
        void phoneNumbersAreNormalised() throws Exception {
            // The stored number is 9876543210. An operator reading it off a
            // note may type any of these, and all of them are the same person.
            for (String term : List.of("9876543210", "+91 9876543210", "98765-43210",
                    "091 98765 43210", "+919876543210")) {
                mockMvc.perform(get("/api/platform/control/customers/search").param("q", term)
                                .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.content[?(@.id == " + shopper + ")]")
                                .exists());
            }
        }

        @Test
        @DisplayName("a bare id finds that customer, not every id containing those digits")
        void anIdIsExact() throws Exception {
            String body = mockMvc.perform(get("/api/platform/control/customers/search")
                            .param("q", String.valueOf(shopper))
                            .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertTrue(body.contains("\"id\":" + shopper), body);
        }

        @Test
        @DisplayName("an order number finds the customer who placed it")
        void anOrderNumberFindsTheBuyer() throws Exception {
            String orderNumber = "GPS-" + tag;
            order(shopId, shopper, orderNumber, "DELIVERED", "500", "40");
            try {
                mockMvc.perform(get("/api/platform/control/customers/search").param("q", orderNumber)
                                .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.content[0].id").value(shopper));
            } finally {
                jdbc.update("DELETE FROM orders WHERE order_number=?", orderNumber);
            }
        }

        @Test
        @DisplayName("paging is server-side and the total is the whole result set")
        void pagingIsServerSide() throws Exception {
            mockMvc.perform(get("/api/platform/control/customers/search")
                            .param("q", tag).param("page", "0").param("size", "1")
                            .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.size").value(1))
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.totalElements").value(
                            org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
        }

        @Test
        @DisplayName("an apostrophe is a character, not a way into the database")
        void theTermCannotTerminateTheStatement() throws Exception {
            for (String hostile : List.of("' OR '1'='1", "'; DROP TABLE customers; --",
                    "%' UNION SELECT password FROM customers --")) {
                mockMvc.perform(get("/api/platform/control/customers/search").param("q", hostile)
                                .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.content.length()").value(0));
            }
            // The table is still there, which is the point of the second one.
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM customers", Long.class) > 0);
        }
    }

    // ============================================================ the tap

    @Nested
    @DisplayName("One tap to a profile")
    class Profiles {

        @Test
        @DisplayName("a merchant profile carries every section without a second request")
        void theMerchantProfileIsComplete() throws Exception {
            order(shopId, shopper, "GPS-M-" + tag, "DELIVERED", "1000", "50");
            try {
                mockMvc.perform(get("/api/platform/control/merchants/{id}/profile", merchantId)
                                .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.core.identity.merchantRef").value("M-" + merchantId))
                        .andExpect(jsonPath("$.core.shops[0].shopRef").value("S-" + shopId))
                        .andExpect(jsonPath("$.commerce.orderStatuses").exists())
                        .andExpect(jsonPath("$.commerce.visitToBuyListings").isNumber())
                        .andExpect(jsonPath("$.commerce.serviceListings").isNumber())
                        .andExpect(jsonPath("$.workforce.total").isNumber())
                        .andExpect(jsonPath("$.reputation.averageRating").exists())
                        .andExpect(jsonPath("$.activity.sessionsMeasured").value(false))
                        .andExpect(jsonPath("$.activity.note").exists())
                        .andExpect(jsonPath("$.security").isArray());
            } finally {
                jdbc.update("DELETE FROM orders WHERE order_number=?", "GPS-M-" + tag);
            }
        }

        @Test
        @DisplayName("merchant-wide totals and the per-shop breakdown both appear")
        void bothTotalsAndBreakdown() throws Exception {
            Long second = shop(merchantId, "deepak-two-" + tag, "Deepak Two " + tag, null);
            order(shopId, shopper, "GPS-A-" + tag, "DELIVERED", "1000", "50");
            order(second, shopper, "GPS-B-" + tag, "DELIVERED", "500", "25");
            try {
                mockMvc.perform(get("/api/platform/control/merchants/{id}/profile", merchantId)
                                .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                        .andExpect(status().isOk())
                        // 1000 + 500 product value, delivery excluded from GMV
                        .andExpect(jsonPath("$.core.gmv").value(1500))
                        .andExpect(jsonPath("$.core.shops.length()").value(2));
            } finally {
                jdbc.update("DELETE FROM orders WHERE order_number IN (?,?)",
                        "GPS-A-" + tag, "GPS-B-" + tag);
                jdbc.update("DELETE FROM shops WHERE id=?", second);
            }
        }

        @Test
        @DisplayName("one merchant's money never includes another merchant's")
        void merchantsAreNotMixed() throws Exception {
            order(shopId, shopper, "GPS-MINE-" + tag, "DELIVERED", "1000", "0");
            order(otherShopId, shopper, "GPS-THEIRS-" + tag, "DELIVERED", "9999", "0");
            try {
                mockMvc.perform(get("/api/platform/control/merchants/{id}/profile", merchantId)
                                .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.core.gmv").value(1000));
            } finally {
                jdbc.update("DELETE FROM orders WHERE order_number IN (?,?)",
                        "GPS-MINE-" + tag, "GPS-THEIRS-" + tag);
            }
        }

        @Test
        @DisplayName("a customer profile shows where they buy, and says what is preferred")
        void whereTheCustomerBuys() throws Exception {
            order(shopId, shopper, "GPS-1-" + tag, "DELIVERED", "100", "0");
            order(shopId, shopper, "GPS-2-" + tag, "DELIVERED", "100", "0");
            order(otherShopId, shopper, "GPS-3-" + tag, "DELIVERED", "100", "0");
            try {
                mockMvc.perform(get("/api/platform/control/customers/{id}/profile", shopper)
                                .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                        .andExpect(status().isOk())
                        // Most-bought-from first: two orders beats one.
                        .andExpect(jsonPath("$.shops[0].shopId").value(shopId))
                        .andExpect(jsonPath("$.shops[0].orders").value(2))
                        // BUYING THERE OFTEN IS NOT A PREFERENCE. The customer
                        // never saved one, so nothing here may claim they did.
                        .andExpect(jsonPath("$.shops[0].preferred").value(false))
                        .andExpect(jsonPath("$.shops[1].orders").value(1));
            } finally {
                jdbc.update("DELETE FROM orders WHERE order_number IN (?,?,?)",
                        "GPS-1-" + tag, "GPS-2-" + tag, "GPS-3-" + tag);
            }
        }

        @Test
        @DisplayName("an explicitly saved preference is marked, and only that one")
        void preferredMeansTheySaidSo() throws Exception {
            order(shopId, shopper, "GPS-P1-" + tag, "DELIVERED", "100", "0");
            order(shopId, shopper, "GPS-P2-" + tag, "DELIVERED", "100", "0");
            order(otherShopId, shopper, "GPS-P3-" + tag, "DELIVERED", "100", "0");
            Long categoryId = jdbc.queryForObject(
                    "SELECT id FROM categories ORDER BY id LIMIT 1", Long.class);
            jdbc.update("""
                    INSERT INTO customer_preferred_shops
                        (customer_id, category_id, preferred_shop_id, slot, created_at)
                    VALUES (?,?,?,1,CURRENT_TIMESTAMP)
                    """, shopper, categoryId, otherShopId);
            try {
                String body = mockMvc.perform(
                                get("/api/platform/control/customers/{id}/profile", shopper)
                                        .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                        .andExpect(status().isOk())
                        // The shop they buy from MOST is still not preferred...
                        .andExpect(jsonPath("$.shops[0].shopId").value(shopId))
                        .andExpect(jsonPath("$.shops[0].preferred").value(false))
                        // ...and the one they actually chose is, on one order.
                        .andExpect(jsonPath("$.shops[1].shopId").value(otherShopId))
                        .andExpect(jsonPath("$.shops[1].preferred").value(true))
                        .andReturn().getResponse().getContentAsString();
                assertTrue(body.contains("\"preferred\":true"), body);
            } finally {
                jdbc.update("DELETE FROM customer_preferred_shops WHERE customer_id=?", shopper);
                jdbc.update("DELETE FROM orders WHERE order_number IN (?,?,?)",
                        "GPS-P1-" + tag, "GPS-P2-" + tag, "GPS-P3-" + tag);
            }
        }

        @Test
        @DisplayName("a customer with no orders is an empty profile, not a failure")
        void nullsAndMissingDataAreFine() throws Exception {
            mockMvc.perform(get("/api/platform/control/customers/{id}/profile", shopper)
                            .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.shops.length()").value(0))
                    .andExpect(jsonPath("$.payments.length()").value(0))
                    .andExpect(jsonPath("$.activity.sessionsMeasured").value(false))
                    .andExpect(jsonPath("$.activity.sessions").value(0));
        }

        @Test
        @DisplayName("an id that does not exist is 404, and never another record")
        void unknownIdsAreNotFound() throws Exception {
            mockMvc.perform(get("/api/platform/control/customers/{id}/profile", 999_999_999L)
                            .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/platform/control/merchants/{id}/profile", 999_999_999L)
                            .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/platform/control/customers/{id}/profile", -1L)
                            .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                    .andExpect(status().isBadRequest());
        }
    }

    // ========================================================== the secrets

    @Test
    @DisplayName("no profile ever carries a credential")
    void nothingHereLetsAnybodyBecomeSomebody() throws Exception {
        order(shopId, shopper, "GPS-S-" + tag, "DELIVERED", "100", "0");
        try {
            for (String path : List.of(
                    "/api/platform/control/customers/" + shopper + "/profile",
                    "/api/platform/control/merchants/" + merchantId + "/profile")) {
                String body = mockMvc.perform(get(path)
                                .with(authentication(token(admin, Role.PLATFORM_ADMIN))))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString()
                        .toLowerCase(Locale.ROOT);

                for (String forbidden : List.of("password", "passwordhash", "password_hash",
                        "activation_code", "activationcode", "otp", "refresh_token",
                        "refreshtoken", "accesstoken", "jwt", "cvv", "upipin",
                        "privatekey", "apisecret", "webhooksecret")) {
                    assertFalse(body.contains(forbidden),
                            "'" + forbidden + "' appears in " + path
                                    + " - complete information does not mean handing over "
                                    + "the things that let somebody become this account");
                }
            }
        } finally {
            jdbc.update("DELETE FROM orders WHERE order_number=?", "GPS-S-" + tag);
        }
    }

    // ------------------------------------------------------------- fixture

    private Long customer(String name, String email, String mobile) {
        jdbc.update("""
                INSERT INTO customers
                    (full_name,email,mobile_number,password,role,enabled,active,verified,created_at)
                VALUES (?,?,?,'not-a-real-hash','CUSTOMER',true,true,true,CURRENT_TIMESTAMP)
                """, name, email, mobile);
        return jdbc.queryForObject("SELECT id FROM customers WHERE email=?", Long.class, email);
    }

    private Long merchant(String display, String legal, String email, String phone, Long owner) {
        jdbc.update("""
                INSERT INTO merchants
                    (legal_name,display_name,contact_email,contact_phone,contact_name,
                     owner_customer_id,status,active,is_demo,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'ACTIVE',true,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, legal, display, email, phone, display, owner);
        return jdbc.queryForObject(
                "SELECT id FROM merchants WHERE legal_name=?", Long.class, legal);
    }

    private Long shop(Long merchant, String code, String name, String businessName) {
        jdbc.update("""
                INSERT INTO shops
                    (merchant_id,code,display_name,business_name,status,active,is_demo,
                     latitude,longitude,max_delivery_radius_km,time_zone)
                VALUES (?,?,?,?,'ACTIVE',true,true,21.1,79.1,10,'Asia/Kolkata')
                """, merchant, code, name, businessName);
        return jdbc.queryForObject("SELECT id FROM shops WHERE code=?", Long.class, code);
    }

    private void order(Long shop, Long buyer, String number, String status,
                       String productAmount, String deliveryFee) {
        BigDecimal product = new BigDecimal(productAmount);
        BigDecimal delivery = new BigDecimal(deliveryFee);
        jdbc.update("""
                INSERT INTO orders
                    (order_number,shop_id,customer_id,total_amount,delivery_fee,
                     order_status,order_date)
                VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)
                """, number, shop, buyer, product.add(delivery), delivery, status);
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
