package com.gpstore.rating;

import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.platform.Merchant;
import com.gpstore.platform.MerchantRepository;
import com.gpstore.platform.MerchantStatus;
import com.gpstore.platform.PlatformMode;
import com.gpstore.platform.PlatformProperties;
import com.gpstore.platform.Shop;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.ShopStatus;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantDefaults;
import com.gpstore.platform.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RATING THE SHOP IS NOT RATING THE ATTA (Part 3 §17-§22).
 *
 * <p>THE SIX RULES, EACH GUARDING A DIFFERENT FAILURE:
 *
 * <ul>
 *   <li>§17 - a shop that delivered a perfect packet of a mediocre biscuit
 *       must not carry the biscuit's stars, so the two are different rows.</li>
 *   <li>§18 - the reason is the half a shopkeeper can act on.</li>
 *   <li>§19 - one number flatters a new shop and buries an improved one, so
 *       the display carries lifetime, recent and how many people.</li>
 *   <li>§20 - genuine negatives remain. This is the one that is easy to
 *       write in a policy and impossible to enforce without schema.</li>
 *   <li>§21 - one response each, or a merchant buries a one-star under their
 *       own replies.</li>
 *   <li>§22 - a rating costs a real order, and an order buys exactly one.</li>
 * </ul>
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
@DisplayName("Rating the shop is not rating the atta")
class RatingTheShopIsNotRatingTheAttaTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private ShopRatingService service;

    private final String tag = "rate" + System.nanoTime();

    private long shopA;
    private long shopB;
    private Long merchantId;
    private Long customerId;
    private Long otherCustomerId;

    @BeforeEach
    void twoShopsAndAnOrder() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
        shopA = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();

        Merchant m = new Merchant();
        m.setLegalName("Rating fixture " + tag);
        m.setDisplayName("Rating fixture");
        m.setStatus(MerchantStatus.ACTIVE);
        m.setIsDemo(Boolean.TRUE);
        m.setActive(Boolean.TRUE);
        merchantId = merchants.save(m).getId();

        Shop b = new Shop();
        b.setMerchantId(merchantId);
        b.setCode("RATE-" + tag);
        b.setDisplayName("The shop next door");
        b.setStatus(ShopStatus.ACTIVE);
        b.setLatitude(27.16);
        b.setLongitude(83.94);
        b.setMaxDeliveryRadiusKm(new BigDecimal("5"));
        b.setTimeZone("Asia/Kolkata");
        b.setIsDemo(Boolean.TRUE);
        b.setActive(Boolean.TRUE);
        shopB = shops.save(b).getId();

        customerId = newCustomer("rater");
        otherCustomerId = newCustomer("bystander");
    }

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        jdbc.update("DELETE FROM shop_rating_reasons WHERE shop_rating_id IN "
                + "(SELECT id FROM shop_ratings WHERE customer_id IN (?, ?))",
                customerId, otherCustomerId);
        jdbc.update("DELETE FROM shop_ratings WHERE customer_id IN (?, ?)",
                customerId, otherCustomerId);
        jdbc.update("DELETE FROM orders WHERE order_number LIKE ?", "RATE-" + tag + "%");
        jdbc.update("DELETE FROM customers WHERE id IN (?, ?)", customerId, otherCustomerId);
        jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM shops WHERE id = ?", shopB);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
        // BACK TO SINGLE_SHOP, NOT BACK TO THIS CLASS'S OWN MODE. TenantDefaults
        // is a static holder shared by every cached Spring context in the run,
        // and this class's PlatformProperties says MULTI_SHOP_PRODUCTION -
        // so restoring platform.getMode() here would leave the whole JVM in
        // multi-shop mode and make later single-shop tests fail depending on
        // what order surefire happened to run them in.
        TenantDefaults.install(PlatformMode.SINGLE_SHOP,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    // ----------------------------------------------------------------- §22

    @Nested
    @DisplayName("§22 a rating is bought with a real order")
    class Earned {

        @Test
        @DisplayName("an order buys exactly one rating, and editing it does not buy a second")
        void oneRatingPerOrder() {
            long order = deliveredOrder(shopA, customerId);

            ShopRating first = platformScope(() ->
                    service.rate(customerId, order, 2, Set.of(ShopRatingReason.DELIVERED_LATE), "Late"));
            ShopRating edited = platformScope(() ->
                    service.rate(customerId, order, 4, Set.of(), "They put it right"));

            assertEquals(first.getId(), edited.getId(),
                    "CHANGING YOUR MIND IS NOT A SECOND RATING. People do change their minds, "
                            + "and a shop that fixed the problem deserves the correction - but a "
                            + "second row would be one order buying two stars.");
            assertEquals(1L, inShop(shopA, () -> service.summary().count()));
            assertEquals(4.0, inShop(shopA, () -> service.summary().average()));
        }

        @Test
        @DisplayName("somebody else's order is not yours to rate")
        void onlyYourOwnOrder() {
            long order = deliveredOrder(shopA, otherCustomerId);

            assertThrows(ResourceNotFoundException.class,
                    () -> platformScope(() -> service.rate(customerId, order, 1, Set.of(), "bad")),
                    "AND THE SAME 404 AN UNKNOWN ORDER GIVES, so probing ids tells an attacker "
                            + "nothing about which orders exist.");
        }

        @Test
        @DisplayName("an order you cancelled yourself is not the shop's failure")
        void yourOwnCancellationIsNotTheShopsFault() {
            long order = endedOrder(shopA, customerId, "CANCELLED", "CUSTOMER");

            ConflictException refused = assertThrows(ConflictException.class,
                    () -> platformScope(() -> service.rate(customerId, order, 1,
                            Set.of(ShopRatingReason.ORDER_CANCELLED_BY_SHOP), "rubbish")));

            assertTrue(refused.getMessage().contains("cancelled by you"),
                    "ORDER_CANCELLED_BY_SHOP IS IN THE REASON LIST FOR A REASON - a customer "
                            + "the shop let down has something to say. Letting them say it "
                            + "about their OWN cancellation turns the list into a weapon.");
        }

        @Test
        @DisplayName("an order the shop refused can be rated, because that is the shop's doing")
        void aRejectedOrderCanBeRated() {
            long order = endedOrder(shopA, customerId, "REJECTED", "MERCHANT");

            ShopRating rating = platformScope(() -> service.rate(customerId, order, 1,
                    Set.of(ShopRatingReason.ORDER_CANCELLED_BY_SHOP), "Never turned up"));

            assertNotNull(rating.getId());
        }

        @Test
        @DisplayName("an order still in flight cannot be rated yet")
        void notUntilItArrives() {
            long order = openOrder(shopA, customerId);
            assertThrows(ConflictException.class,
                    () -> platformScope(() -> service.rate(customerId, order, 5, Set.of(), "great")));
        }
    }

    // ----------------------------------------------------------------- §18

    @Nested
    @DisplayName("§18 the reason is the half a shopkeeper can act on")
    class Reasons {

        @Test
        @DisplayName("the reasons are kept, and counted for the shop")
        void reasonsAreCounted() {
            platformScope(() -> service.rate(customerId, deliveredOrder(shopA, customerId), 2,
                    Set.of(ShopRatingReason.DELIVERED_LATE, ShopRatingReason.ITEMS_MISSING),
                    "Two things wrong"));

            var summary = inShop(shopA, () -> service.summary());
            assertEquals(2, summary.topReasons().size());
            assertTrue(summary.topReasons().stream()
                            .anyMatch(r -> r.reason() == ShopRatingReason.DELIVERED_LATE),
                    "TWO STARS TELLS A SHOPKEEPER NOTHING. Two stars and DELIVERED_LATE tells "
                            + "them to look at their dispatch times.");
            assertTrue(summary.topReasons().stream().noneMatch(
                    ShopRatingSummary.ReasonCount::praise),
                    "and the praise flag is on the row, so a screen need not hard-code which "
                            + "codes are complaints");
        }

        @Test
        @DisplayName("more than five reasons says nothing anybody can act on")
        void tooManyReasonsIsRefused() {
            long order = deliveredOrder(shopA, customerId);
            Set<ShopRatingReason> everything = Set.of(ShopRatingReason.values());
            assertThrows(BadRequestException.class,
                    () -> platformScope(() -> service.rate(customerId, order, 3, everything, null)));
        }
    }

    // ----------------------------------------------------------------- §19

    @Nested
    @DisplayName("§19 one number flatters a new shop and buries an improved one")
    class TheDisplay {

        @Test
        @DisplayName("the summary carries lifetime, recent, the count and the spread")
        void threeFiguresNotOne() {
            platformScope(() -> service.rate(customerId, deliveredOrder(shopA, customerId), 5,
                    Set.of(ShopRatingReason.PACKED_WELL), "Perfect"));
            platformScope(() -> service.rate(otherCustomerId, deliveredOrder(shopA, otherCustomerId),
                    3, Set.of(), "Fine"));

            ShopRatingSummary summary = inShop(shopA, () -> service.summary());

            assertEquals(2L, summary.count());
            assertEquals(4.0, summary.average());
            assertEquals(2L, summary.recentCount(), "both are inside the recent window");
            assertEquals(ShopRatingService.RECENT_DAYS, summary.recentDays(),
                    "and the window is stated rather than left for a reader to guess");
            assertEquals(2L, summary.verifiedCount(),
                    "EVERY SHOP RATING IS VERIFIED - there is no route that makes one without "
                            + "an order - and saying so is the point, because \"4.0 from 2 "
                            + "verified orders\" says what \"4.0 (2)\" does not.");
            assertEquals(1L, summary.distribution().get(5));
            assertEquals(1L, summary.distribution().get(3));
            assertEquals(0L, summary.distribution().get(1));
        }

        @Test
        @DisplayName("a shop nobody has rated has no rating, not a middling one")
        void anUnratedShopIsUnrated() {
            ShopRatingSummary summary = inShop(shopB, () -> service.summary());
            assertEquals(0L, summary.count());
            assertEquals(0.0, summary.average(),
                    "INVENTING A THREE FOR A NEW KIRANA would misrepresent it to a customer, "
                            + "and is exactly the sort of number that ends up in a ranking.");
            assertFalse(summary.hasEnoughToShow());
        }
    }

    // ----------------------------------------------------------------- §20

    @Nested
    @DisplayName("§20 genuine negative reviews remain")
    class Moderation {

        @Test
        @DisplayName("there is no reason code that means \"this is unfair\"")
        void theListIsAboutTheTextNotTheOpinion() {
            String codes = Arrays.toString(HideReason.values());
            for (String forbidden : new String[]{"UNFAIR", "NEGATIVE", "INACCURATE",
                    "WRONG", "DAMAGES", "MISTAKEN", "OTHER"}) {
                assertFalse(codes.contains(forbidden),
                        "HideReason must not contain " + forbidden + ". Every value has to "
                                + "describe something wrong with the TEXT, never something "
                                + "wrong with the OPINION - and a generic OTHER would be all "
                                + "of them wearing a hat. Found: " + codes);
            }
        }

        @Test
        @DisplayName("hiding needs a reason, and the row survives it")
        void hidingIsNotDeleting() {
            ShopRating rating = platformScope(() -> service.rate(customerId,
                    deliveredOrder(shopA, customerId), 1,
                    Set.of(ShopRatingReason.RUDE_SERVICE), "Shouted at me. 09812345678"));

            assertThrows(BadRequestException.class,
                    () -> inShop(shopA, () -> service.hide(rating.getId(), null, "mod")));

            ShopRating hidden = inShop(shopA, () ->
                    service.hide(rating.getId(), HideReason.PERSONAL_INFORMATION, "mod"));

            assertFalse(hidden.isVisible(), "the words stop being shown");
            Long stillThere = jdbc.queryForObject(
                    "SELECT count(*) FROM shop_ratings WHERE id = ?", Long.class, rating.getId());
            assertEquals(1L, stillThere,
                    "AND THE ROW IS STILL THERE. If moderating deleted it, \"why do this "
                            + "shop's one-star ratings keep disappearing\" would be a question "
                            + "the database could not answer.");
        }

        @Test
        @DisplayName("a hidden rating still counts towards the average")
        void hidingDoesNotImproveTheAverage() {
            ShopRating one = platformScope(() -> service.rate(customerId,
                    deliveredOrder(shopA, customerId), 1, Set.of(), "Abusive text here"));
            platformScope(() -> service.rate(otherCustomerId,
                    deliveredOrder(shopA, otherCustomerId), 5, Set.of(), "Lovely"));

            assertEquals(3.0, inShop(shopA, () -> service.summary().average()));

            inShop(shopA, () -> service.hide(one.getId(), HideReason.ABUSIVE_LANGUAGE, "mod"));

            assertEquals(3.0, inShop(shopA, () -> service.summary().average()),
                    "IF HIDING ALSO IMPROVED THE ARITHMETIC, hiding would be worth doing for "
                            + "the arithmetic alone and §20 would be one moderation queue away "
                            + "from meaningless.");
            assertEquals(2L, inShop(shopA, () -> service.summary().count()));
        }

        @Test
        @DisplayName("spam was never an opinion, so it leaves the average")
        void spamIsTheOneException() {
            ShopRating spam = platformScope(() -> service.rate(customerId,
                    deliveredOrder(shopA, customerId), 1, Set.of(), "BUY CHEAP WATCHES"));
            platformScope(() -> service.rate(otherCustomerId,
                    deliveredOrder(shopA, otherCustomerId), 5, Set.of(), "Lovely"));

            inShop(shopA, () -> service.hide(spam.getId(), HideReason.SPAM, "mod"));

            assertEquals(5.0, inShop(shopA, () -> service.summary().average()));
            assertEquals(1L, inShop(shopA, () -> service.summary().count()));
        }

        @Test
        @DisplayName("the merchant cannot hide anything - reporting is all they get")
        void reportingIsNotHiding() {
            ShopRating rating = platformScope(() -> service.rate(customerId,
                    deliveredOrder(shopA, customerId), 1, Set.of(), "Terrible"));

            ShopRating reported = inShop(shopA, () ->
                    service.report(rating.getId(), "We think this is a competitor", "shop"));

            assertTrue(reported.isReported());
            assertTrue(reported.isVisible(),
                    "A MERCHANT WHO COULD SUPPRESS A RATING BY OBJECTING TO IT would have a "
                            + "delete button with an extra step.");
            assertEquals(1.0, inShop(shopA, () -> service.summary().average()),
                    "and it still counts");
            assertEquals(1L, inShop(shopA, () -> service.reported(
                    org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements()));
        }
    }

    // ----------------------------------------------------------------- §21

    @Nested
    @DisplayName("§21 one response each")
    class TheConversation {

        @Test
        @DisplayName("the shop answers once, and a second attempt is refused")
        void theShopAnswersOnce() {
            ShopRating rating = platformScope(() -> service.rate(customerId,
                    deliveredOrder(shopA, customerId), 2, Set.of(), "Late"));

            inShop(shopA, () -> service.merchantRespond(rating.getId(), "Sorry - rider broke down", "shop"));

            ConflictException second = assertThrows(ConflictException.class,
                    () -> inShop(shopA, () ->
                            service.merchantRespond(rating.getId(), "Also, please reconsider", "shop")));
            assertTrue(second.getMessage().contains("One response each"),
                    "A MERCHANT POSTING REPEATEDLY could bury a one-star under their own "
                            + "replies, which is §20's problem by a different road.");
        }

        @Test
        @DisplayName("the customer replies once, and only to an answer")
        void theCustomerRepliesOnce() {
            ShopRating rating = platformScope(() -> service.rate(customerId,
                    deliveredOrder(shopA, customerId), 2, Set.of(), "Late"));

            assertThrows(ConflictException.class,
                    () -> platformScope(() ->
                            service.customerReply(rating.getId(), customerId, "?")),
                    "there is nothing to reply to yet");

            inShop(shopA, () -> service.merchantRespond(rating.getId(), "Sorry", "shop"));
            platformScope(() -> service.customerReply(rating.getId(), customerId, "Thanks"));

            assertThrows(ConflictException.class,
                    () -> platformScope(() ->
                            service.customerReply(rating.getId(), customerId, "And another thing")));
        }

        @Test
        @DisplayName("somebody else's rating is not yours to reply to")
        void onlyYourOwnRating() {
            ShopRating rating = platformScope(() -> service.rate(customerId,
                    deliveredOrder(shopA, customerId), 2, Set.of(), "Late"));
            inShop(shopA, () -> service.merchantRespond(rating.getId(), "Sorry", "shop"));

            assertThrows(ResourceNotFoundException.class,
                    () -> platformScope(() ->
                            service.customerReply(rating.getId(), otherCustomerId, "me too")));
        }
    }

    // ------------------------------------------------------------ isolation

    @Nested
    @DisplayName("§6 a shop cannot read or answer its competitor's ratings")
    class OneShopAtATime {

        @Test
        @DisplayName("shop B's summary does not contain shop A's stars")
        void summariesDoNotLeak() {
            platformScope(() -> service.rate(customerId, deliveredOrder(shopA, customerId),
                    1, Set.of(), "Terrible"));

            assertEquals(1L, inShop(shopA, () -> service.summary().count()));
            assertEquals(0L, inShop(shopB, () -> service.summary().count()),
                    "ONE KIRANA'S ONE-STAR MUST NOT APPEAR IN ANOTHER'S AVERAGE, and it is "
                            + "the shop filter that stops it - not a screen declining to draw it.");
        }

        @Test
        @DisplayName("shop B cannot answer, hide or report a rating left for shop A")
        void theCompetitorCannotTouchIt() {
            ShopRating rating = platformScope(() -> service.rate(customerId,
                    deliveredOrder(shopA, customerId), 1, Set.of(), "Terrible"));

            // A CrossShopAccessException, not a 404, and that is the right
            // answer: a load by primary key is not a query, so the shop
            // filter does not reach it - TenantEntityListener's @PostLoad
            // does, and refuses the row as it is materialised. Asserting the
            // exact exception is deliberate: if somebody later "simplifies"
            // the listener away, a quieter failure mode would let this pass.
            assertThrows(com.gpstore.platform.CrossShopAccessException.class,
                    () -> inShop(shopB, () ->
                            service.merchantRespond(rating.getId(), "Not us", "shopB")));
            assertThrows(com.gpstore.platform.CrossShopAccessException.class,
                    () -> inShop(shopB, () -> service.report(rating.getId(), "spam", "shopB")));
        }
    }

    // ------------------------------------------------------------- fixtures

    private Long newCustomer(String who) {
        String mobile = "9" + String.format("%09d",
                Math.abs(System.nanoTime() % 1_000_000_000L));
        String email = who + "." + tag + "@example.test";
        jdbc.update("INSERT INTO customers (full_name, email, mobile_number, active) "
                + "VALUES (?, ?, ?, true)", who + " " + tag, email, mobile);
        return jdbc.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
    }

    private long deliveredOrder(long shopId, Long forCustomer) {
        return order(shopId, forCustomer, "DELIVERED", null);
    }

    private long endedOrder(long shopId, Long forCustomer, String status, String fault) {
        return order(shopId, forCustomer, status, fault);
    }

    private long openOrder(long shopId, Long forCustomer) {
        return order(shopId, forCustomer, "CONFIRMED", null);
    }

    private long order(long shopId, Long forCustomer, String status, String fault) {
        String number = "RATE-" + tag + "-" + System.nanoTime();
        jdbc.update("INSERT INTO orders (order_number, shop_id, customer_id, order_date, "
                        + "total_amount, order_status, payment_status, fault, ended_at) "
                        + "VALUES (?, ?, ?, now(), ?, ?, 'PENDING', ?, now())",
                number, shopId, forCustomer, new BigDecimal("240.00"), status, fault);
        return jdbc.queryForObject("SELECT id FROM orders WHERE order_number = ?",
                Long.class, number);
    }

    private <T> T inShop(long shopId, java.util.function.Supplier<T> work) {
        return TenantContext.runWithin(TenantScope.ofShop(shopId), work::get);
    }

    private void inShop(long shopId, Runnable work) {
        TenantContext.runWithin(TenantScope.ofShop(shopId), work);
    }

    /** How a customer's own request reaches an order from any shop (§5). */
    private <T> T platformScope(java.util.function.Supplier<T> work) {
        return TenantContext.runWithin(TenantScope.platform(), work::get);
    }
}
