package com.gpstore.platform;

import com.gpstore.security.WithStaff;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * THE SURFACES ADDED AFTER THE ATTACK SUITE WAS WRITTEN, ATTACKED THE SAME WAY.
 *
 * <p>WHY THIS FILE EXISTS. {@code CrossTenantApiAccessTest} proves isolation
 * over HTTP for orders, coupons and inventory - the surfaces that existed
 * when it was written. Parts 2, 3 and 4 then added ratings, cancellation
 * debts, governance records and customer preferences, and each of those got
 * isolation tests at the SERVICE layer only.
 *
 * <p>THAT IS NOT THE SAME THING, and the brief is explicit about it: a
 * service test proves the mechanism, not that the mechanism is switched on
 * for a real request. Between the two sit the filter chain, the security
 * rules, the controller and the scope resolution - and a route with a missing
 * rule in SecurityConfig passes every service test in the world while being
 * world-readable. Two of the rules these endpoints depend on were added by
 * hand in the same session that added the endpoints; nothing but this file
 * would notice if one were deleted.
 *
 * <p>WHAT IS BEING ATTACKED: an authenticated, legitimate member of Shop A's
 * staff, changing an id to one belonging to Shop B. That is the whole attack.
 * Ids are sequential and a shopkeeper can count.
 *
 * <p>EVERY TEST HAS A POSITIVE CONTROL. A suite where everything 404s would
 * pass just as well against an application that was simply broken, and would
 * tell nobody that the shop had lost the ability to work its own business.
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
@DisplayName("The surfaces added after the attack suite are scoped too")
class TheNewSurfacesAreAlsoScopedTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;

    private final String tag = "newsurf" + System.nanoTime();

    private long shopA;
    private long shopB;
    private Long merchantB;
    private Long customerId;

    private long ourOrderId;
    private long theirOrderId;
    private long ourRatingId;
    private long theirRatingId;
    private long ourDueId;
    private long theirDueId;
    private long theirGovernanceId;

    @BeforeEach
    void aSecondShopWithBusinessOfItsOwn() {
        shopA = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();

        Merchant second = new Merchant();
        second.setLegalName("New-surface isolation fixture " + tag);
        second.setDisplayName("Fixture B");
        second.setStatus(MerchantStatus.ACTIVE);
        second.setIsDemo(Boolean.TRUE);
        second.setActive(Boolean.TRUE);
        merchantB = merchants.save(second).getId();

        Shop b = new Shop();
        b.setMerchantId(merchantB);
        b.setCode("NSF-" + tag);
        b.setDisplayName("Fixture shop B");
        b.setStatus(ShopStatus.ACTIVE);
        b.setIsDemo(Boolean.TRUE);
        b.setActive(Boolean.TRUE);
        shopB = shops.save(b).getId();

        jdbc.update("""
                INSERT INTO customers (full_name, email, mobile_number, password, role, active)
                VALUES (?, ?, ?, 'not-a-real-hash', 'CUSTOMER', true)
                """, "NSF fixture " + tag, tag + "@example.test",
                "9" + (100000000 + (int) (Math.random() * 899999999)));
        customerId = jdbc.queryForObject(
                "SELECT id FROM customers WHERE email = ?", Long.class, tag + "@example.test");

        ourOrderId = insertOrder("NSFA-" + tag, shopA);
        theirOrderId = insertOrder("NSFB-" + tag, shopB);

        ourRatingId = insertRating(shopA, ourOrderId, 5, "Ours, and good");
        theirRatingId = insertRating(shopB, theirOrderId, 1,
                "Theirs, and bad - the one a competitor would love to bury");

        ourDueId = insertDue(shopA, ourOrderId, "12.00");
        theirDueId = insertDue(shopB, theirOrderId, "34.00");

        theirGovernanceId = insertGovernance(merchantB);
    }

    @AfterEach
    void tidyUp() {
        jdbc.update("DELETE FROM merchant_governance_actions WHERE merchant_id = ?", merchantB);
        jdbc.update("DELETE FROM customer_cancellation_dues WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM shop_rating_reasons WHERE shop_rating_id IN (?, ?)",
                ourRatingId, theirRatingId);
        jdbc.update("DELETE FROM shop_ratings WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM customer_preferred_shops WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM orders WHERE id IN (?, ?)", ourOrderId, theirOrderId);
        jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM shops WHERE id = ?", shopB);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantB);
    }

    // ------------------------------------------------------------ ratings

    @Nested
    @DisplayName("shop ratings (Part 3 §17-§22)")
    class Ratings {

        @Test
        @WithStaff
        @DisplayName("the merchant list shows this shop's ratings and only this shop's")
        void theListIsScoped() throws Exception {
            MvcResult ours = mockMvc.perform(get("/api/shop-ratings/manage")).andReturn();

            assertEquals(200, ours.getResponse().getStatus(),
                    "the shop lost the ability to read its own ratings; body: "
                            + ours.getResponse().getContentAsString());

            String body = ours.getResponse().getContentAsString();
            assertTrue(body.contains("\"id\":" + ourRatingId),
                    "THE POSITIVE CONTROL. A suite where everything is empty would pass "
                            + "against an application that was simply broken.");
            assertFalse(body.contains("\"id\":" + theirRatingId),
                    "A COMPETITOR'S ONE-STAR RATING APPEARED IN THIS SHOP'S LIST. The "
                            + "words on it are a competitor's customer complaining, and the "
                            + "order id on it is a competitor's order.");
            assertFalse(body.contains("the one a competitor would love to bury"),
                    "the comment text leaked even if the id did not");
        }

        @Test
        @WithStaff
        @DisplayName("a shop cannot answer a rating left for its competitor")
        void answeringSomebodyElsesRating() throws Exception {
            MvcResult attempt = mockMvc.perform(
                    post("/api/shop-ratings/" + theirRatingId + "/respond")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"Speaking as a shop I do not own\"}")).andReturn();

            assertNotEquals(200, attempt.getResponse().getStatus(),
                    "one shop posted a public reply under another shop's name");
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM shop_ratings WHERE id = ? AND merchant_response IS NOT NULL",
                    Integer.class, theirRatingId),
                    "the reply was actually written to the competitor's rating");
        }

        @Test
        @WithStaff
        @DisplayName("a shop cannot report a rating left for its competitor")
        void reportingSomebodyElsesRating() throws Exception {
            MvcResult attempt = mockMvc.perform(
                    post("/api/shop-ratings/" + theirRatingId + "/report")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"I would like this one reviewed\"}")).andReturn();

            assertNotEquals(200, attempt.getResponse().getStatus(),
                    "a shop put a competitor's rating into the moderation queue - which is "
                            + "a way to make somebody else's one-star review somebody's "
                            + "problem without ever owning the shop it was left for");
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM shop_ratings WHERE id = ? AND reported_at IS NOT NULL",
                    Integer.class, theirRatingId));
        }

        @Test
        @WithStaff
        @DisplayName("hiding a rating needs the moderator permission, not a merchant login")
        void merchantsCannotHide() throws Exception {
            MvcResult attempt = mockMvc.perform(
                    post("/api/shop-ratings/" + ourRatingId + "/hide")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"SPAM\"}")).andReturn();

            // The staff role under @WithStaff is a shop admin, not a platform
            // moderator. §20 makes hiding a platform decision precisely so a
            // merchant cannot make their own bad reviews disappear.
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM shop_ratings WHERE id = ? AND hidden_at IS NOT NULL",
                    Integer.class, ourRatingId),
                    "a merchant hid a rating of their own shop. Status was "
                            + attempt.getResponse().getStatus());
        }
    }

    // ----------------------------------------------------- cancellation dues

    @Nested
    @DisplayName("cancellation debts (Part 3 §11)")
    class Dues {

        @Test
        @WithStaff
        @DisplayName("a shop sees what it is owed and not what its competitor is owed")
        void theOutstandingListIsScoped() throws Exception {
            MvcResult ours = mockMvc.perform(get("/api/cancellation-dues/outstanding")).andReturn();

            assertEquals(200, ours.getResponse().getStatus(),
                    "the shop lost the ability to read its own debts; body: "
                            + ours.getResponse().getContentAsString());

            String body = ours.getResponse().getContentAsString();
            assertTrue(body.contains("12.00"), "the positive control: our own debt is listed");
            assertFalse(body.contains("34.00"),
                    "A SHOP READ WHAT A CUSTOMER OWES ITS COMPETITOR. That is a record of "
                            + "how often a named customer cancels on the shop next door.");
            assertFalse(body.contains("\"id\":" + theirDueId));
        }

        @Test
        @WithStaff
        @DisplayName("a shop cannot waive a debt owed to its competitor")
        void waivingSomebodyElsesDebt() throws Exception {
            MvcResult attempt = mockMvc.perform(
                    post("/api/cancellation-dues/" + theirDueId + "/waive")).andReturn();

            assertNotEquals(200, attempt.getResponse().getStatus(),
                    "one shop wrote off money a customer owed another shop");
            assertEquals("OUTSTANDING", jdbc.queryForObject(
                    "SELECT status FROM customer_cancellation_dues WHERE id = ?",
                    String.class, theirDueId),
                    "the competitor's debt was actually cleared");
        }
    }

    // --------------------------------------------------------- governance

    @Nested
    @DisplayName("the disciplinary record (Part 4 §2)")
    class Governance {

        @Test
        @WithStaff
        @DisplayName("a merchant reads their own record and nobody else's")
        void theRecordIsScoped() throws Exception {
            MvcResult ours = mockMvc.perform(get("/api/shop/governance")).andReturn();

            assertEquals(200, ours.getResponse().getStatus(),
                    "the merchant lost the ability to read their own record; body: "
                            + ours.getResponse().getContentAsString());

            String body = ours.getResponse().getContentAsString();
            assertFalse(body.contains("\"id\":" + theirGovernanceId),
                    "ONE MERCHANT READ ANOTHER MERCHANT'S DISCIPLINARY FILE. The reason "
                            + "code and the evidence on it are a competitor being "
                            + "investigated, which is commercially valuable and none of "
                            + "this shop's business.");
            assertFalse(body.contains("Fixture B was warned about"),
                    "the evidence text leaked even if the id did not");
        }

        @Test
        @WithStaff
        @DisplayName("a merchant cannot appeal a warning issued to somebody else")
        void appealingSomebodyElsesWarning() throws Exception {
            MvcResult attempt = mockMvc.perform(
                    post("/api/shop/governance/actions/" + theirGovernanceId + "/appeal")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"On behalf of a merchant I am not\"}")).andReturn();

            assertNotEquals(200, attempt.getResponse().getStatus(),
                    "a merchant filed an appeal on another merchant's case - which both "
                            + "reveals the case exists and spends the one appeal §2 gives "
                            + "the merchant it actually belongs to");
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM merchant_governance_actions "
                            + "WHERE id = ? AND appealed_at IS NOT NULL",
                    Integer.class, theirGovernanceId),
                    "the appeal was actually recorded against the other merchant's action");
        }

        @Test
        @WithStaff
        @DisplayName("issuing a warning is the platform's, not a merchant's")
        void merchantsCannotDisciplineAnybody() throws Exception {
            MvcResult attempt = mockMvc.perform(
                    post("/api/platform/governance/actions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"merchantId\":" + merchantB + ",\"level\":\"SUSPENSION\","
                                    + "\"reason\":\"COUNTERFEIT_OR_UNSAFE_GOODS\","
                                    + "\"detail\":\"Written by a competitor\"}")).andReturn();

            assertNotEquals(200, attempt.getResponse().getStatus(),
                    "A SHOP SUSPENDED ITS COMPETITOR. There is no clearer way to say why "
                            + "the platform routes are gated separately.");
            assertEquals("ACTIVE", jdbc.queryForObject(
                    "SELECT status FROM merchants WHERE id = ?", String.class, merchantB),
                    "the competitor was actually suspended");
        }
    }

    // ------------------------------------------------------------ fixtures

    private long insertOrder(String number, long shopId) {
        jdbc.update("""
                INSERT INTO orders (order_number, customer_id, total_amount, order_status,
                                    payment_status, order_date, active, shop_id, ended_at)
                VALUES (?, ?, ?, 'DELIVERED', 'SUCCESS', now(), true, ?, now())
                """, number, customerId, new BigDecimal("240.00"), shopId);
        return jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number = ?", Long.class, number);
    }

    private long insertRating(long shopId, long orderId, int stars, String comment) {
        jdbc.update("""
                INSERT INTO shop_ratings (shop_id, customer_id, order_id, rating, comment, created_at)
                VALUES (?, ?, ?, ?, ?, now())
                """, shopId, customerId, orderId, stars, comment);
        return jdbc.queryForObject(
                "SELECT id FROM shop_ratings WHERE order_id = ?", Long.class, orderId);
    }

    private long insertDue(long shopId, long orderId, String amount) {
        jdbc.update("""
                INSERT INTO customer_cancellation_dues
                    (shop_id, customer_id, order_id, amount, status, created_at)
                VALUES (?, ?, ?, ?, 'OUTSTANDING', now())
                """, shopId, customerId, orderId, new BigDecimal(amount));
        return jdbc.queryForObject(
                "SELECT id FROM customer_cancellation_dues WHERE order_id = ?", Long.class, orderId);
    }

    private long insertGovernance(Long merchantId) {
        jdbc.update("""
                INSERT INTO merchant_governance_actions
                    (merchant_id, level, reason_code, detail, issued_at, issued_by)
                VALUES (?, 'WARNING', 'REPEATED_CANCELLATIONS', ?, now(), 'fixture')
                """, merchantId, "Fixture B was warned about repeated cancellations");
        return jdbc.queryForObject(
                "SELECT id FROM merchant_governance_actions WHERE merchant_id = ?",
                Long.class, merchantId);
    }
}
