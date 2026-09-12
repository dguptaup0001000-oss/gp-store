package com.gpstore.rating;

import com.gpstore.entity.Order;
import com.gpstore.enums.OrderFault;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.order.OrderLifecycle;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantScope;
import com.gpstore.repository.OrderRepository;
import com.gpstore.service.AuditLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rating the shop: who may, once, and what happens afterwards (§17-§22).
 *
 * <p>FIVE RULES, and each one is a defence against a different failure:
 *
 * <ul>
 *   <li>§17 - this is about the SHOP. The product has its own review.</li>
 *   <li>§19 - the summary shows lifetime AND recent AND how many people are
 *       behind them, because one number flatters a new shop and buries an
 *       improved one.</li>
 *   <li>§20 - nothing is ever deleted. Hiding takes a reason from a closed
 *       list, and the row survives with the reason and the name on it.</li>
 *   <li>§21 - the merchant answers once, the customer replies once, and then
 *       it stops being a comment thread.</li>
 *   <li>§22 - a rating has to be bought with a real order, an order buys
 *       exactly one, and only an order the customer actually received (or
 *       that the shop failed to deliver) can be rated at all.</li>
 * </ul>
 */
@Service
public class ShopRatingService {

    /**
     * The window {@link ShopRatingSummary#recentAverage} covers.
     *
     * <p>Ninety days: long enough that a quiet kirana still has a recent
     * figure, short enough that last Diwali's rush does not still be
     * describing how the shop runs today.
     */
    public static final int RECENT_DAYS = 90;

    /**
     * How long after an order ends it may still be rated (§22).
     *
     * <p>A rating left eighteen months after the fact is not feedback, and
     * an account that can rate any order it ever placed is an account worth
     * buying. Thirty days is long enough for somebody who meant to get round
     * to it.
     */
    public static final int RATING_WINDOW_DAYS = 30;

    private static final int MAX_REASONS = 5;

    private final ShopRatingRepository ratings;
    private final OrderRepository orders;
    private final AuditLogService auditLog;

    public ShopRatingService(ShopRatingRepository ratings, OrderRepository orders,
                             AuditLogService auditLog) {
        this.ratings = ratings;
        this.orders = orders;
        this.auditLog = auditLog;
    }

    // --------------------------------------------------------- leaving one

    /**
     * The customer rates the shop for one order.
     *
     * <p>THE SHOP COMES FROM THE ORDER, never from the request. A rating
     * whose target a caller could name is a rating anybody can leave on
     * anybody - which is the whole of the abuse problem §22 describes, handed
     * over in a parameter.
     */
    @Transactional
    public ShopRating rate(Long customerId, Long orderId, int stars,
                           Set<ShopRatingReason> reasons, String comment) {

        if (stars < 1 || stars > 5) {
            throw new BadRequestException("A rating is between 1 and 5 stars.");
        }

        Order order = orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (order.getCustomer() == null || !order.getCustomer().getId().equals(customerId)) {
            // The same 404 an unknown order gives, so probing ids tells an
            // attacker nothing about which orders exist.
            throw new ResourceNotFoundException("Order not found");
        }

        requireRateable(order);

        Long shopId = order.getShopId();

        // In the ORDER's shop, not the caller's scope: a customer reading
        // their history is in the platform scope, and the rating belongs to
        // the kirana that served them.
        return TenantContext.runWithin(TenantScope.ofShop(shopId), () -> {

            ShopRating rating = ratings.findByOrderId(orderId).orElseGet(ShopRating::new);
            boolean isNew = rating.getId() == null;

            if (!isNew) {
                // Editing your own rating is allowed - people change their
                // minds, and a shop that fixed the problem deserves the
                // correction. What is not allowed is a second row, which is
                // why this reads the existing one rather than inserting.
                if (!customerId.equals(rating.getCustomerId())) {
                    throw new ResourceNotFoundException("Order not found");
                }
                if (!rating.isVisible()) {
                    throw new ConflictException(
                            "This rating has been hidden and cannot be edited.");
                }
                rating.setUpdatedAt(LocalDateTime.now());
            }

            rating.setShopId(shopId);
            rating.setCustomerId(customerId);
            rating.setOrderId(orderId);
            rating.setRating(stars);
            rating.setComment(trimmedOrNull(comment, 1000));
            rating.setReasons(validated(reasons));

            ShopRating saved = ratings.save(rating);
            auditLog.log(isNew ? "SHOP_RATED" : "SHOP_RATING_UPDATED",
                    "ShopRating", saved.getId(),
                    stars + " star(s) on order " + orderId);
            return saved;
        });
    }

    /**
     * Which orders may be rated, and why that is not simply "delivered".
     *
     * <p>A CUSTOMER WHOSE ORDER THE SHOP REFUSED HAS SOMETHING TO SAY, and
     * ORDER_CANCELLED_BY_SHOP is in the reason list precisely because of it.
     * What they may not do is rate an order THEY cancelled - that is not the
     * shop's failure, and allowing it would make the reason list a weapon.
     * So the test is the §12 fault column, which already carries that
     * distinction for the cancellation charge.
     */
    private void requireRateable(Order order) {
        if (!OrderLifecycle.isTerminal(order.getOrderStatus())) {
            throw new ConflictException(
                    "You can rate this order once it has been delivered.");
        }
        if (order.getFault() == OrderFault.CUSTOMER) {
            throw new ConflictException(
                    "This order was cancelled by you, so there is nothing to rate the shop on.");
        }
        LocalDateTime ended = order.getEndedAt() != null ? order.getEndedAt() : order.getOrderDate();
        if (ended != null && ended.plusDays(RATING_WINDOW_DAYS).isBefore(LocalDateTime.now())) {
            throw new ConflictException(
                    "This order is more than " + RATING_WINDOW_DAYS
                            + " days old and can no longer be rated.");
        }
    }

    private Set<ShopRatingReason> validated(Set<ShopRatingReason> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return new LinkedHashSet<>();
        }
        if (reasons.size() > MAX_REASONS) {
            throw new BadRequestException(
                    "Pick at most " + MAX_REASONS + " reasons - a rating that says everything "
                            + "says nothing a shop can act on.");
        }
        return new LinkedHashSet<>(reasons);
    }

    // ------------------------------------------------ §21 one response each

    /**
     * The shop answers, once.
     *
     * <p>ONCE IS THE RULE, not a suggestion, and it is enforced here rather
     * than in the screen that draws the button. A merchant who could post
     * repeatedly could bury a one-star rating under their own replies, which
     * is §20's problem reached by a different road.
     */
    @Transactional
    public ShopRating merchantRespond(Long ratingId, String text, String actor) {
        String response = trimmedOrNull(text, 1000);
        if (response == null) {
            throw new BadRequestException("Write something for the customer to read.");
        }
        ShopRating rating = mine(ratingId);
        if (rating.getMerchantResponse() != null) {
            throw new ConflictException(
                    "You have already responded to this rating. One response each (§21).");
        }
        rating.setMerchantResponse(response);
        rating.setMerchantResponseAt(LocalDateTime.now());
        rating.setMerchantResponseBy(actor);
        ShopRating saved = ratings.save(rating);
        auditLog.log("SHOP_RATING_ANSWERED", "ShopRating", saved.getId(), "by " + actor);
        return saved;
    }

    /** The customer replies, once, and only to an answer. */
    @Transactional
    public ShopRating customerReply(Long ratingId, Long customerId, String text) {
        String reply = trimmedOrNull(text, 1000);
        if (reply == null) {
            throw new BadRequestException("Write something for the shop to read.");
        }
        ShopRating rating = ratings.findById(ratingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rating not found"));
        if (!customerId.equals(rating.getCustomerId())) {
            throw new ResourceNotFoundException("Rating not found");
        }
        if (rating.getMerchantResponse() == null) {
            throw new ConflictException("The shop has not responded yet.");
        }
        if (rating.getCustomerReply() != null) {
            throw new ConflictException(
                    "You have already replied. One response each (§21).");
        }
        rating.setCustomerReply(reply);
        rating.setCustomerReplyAt(LocalDateTime.now());
        return ratings.save(rating);
    }

    // ------------------------------------------------------ §22 reporting

    /**
     * The shop flags a rating it believes breaks the rules.
     *
     * <p>REPORTING DOES NOT HIDE IT, and that is the entire design. A
     * merchant who could suppress a rating by objecting to it would have a
     * delete button with an extra step, and §20 would be decorative. The flag
     * puts it in front of a platform reviewer and changes nothing else: it
     * stays visible, and it stays in the average.
     */
    @Transactional
    public ShopRating report(Long ratingId, String reason, String actor) {
        ShopRating rating = mine(ratingId);
        if (rating.isReported()) {
            return rating;
        }
        rating.setReportedAt(LocalDateTime.now());
        rating.setReportedBy(actor);
        rating.setReportReason(trimmedOrNull(reason, 300));
        ShopRating saved = ratings.save(rating);
        auditLog.log("SHOP_RATING_REPORTED", "ShopRating", saved.getId(),
                "reported by " + actor + (reason == null ? "" : ": " + reason));
        return saved;
    }

    // ------------------------------------------------ §20 hidden, not gone

    /**
     * A platform reviewer stops the WORDS being shown.
     *
     * <p>Three things this deliberately does not do: it does not delete the
     * row, it does not take the stars out of the average (unless the reason
     * says the rating was never a customer's opinion - see
     * {@link HideReason#stillCounts}), and it does not accept a free-text
     * justification. The reason is an enum whose values all describe
     * something wrong with the TEXT, so "this review is unfair" is not a
     * sentence this method can be told.
     */
    @Transactional
    public ShopRating hide(Long ratingId, HideReason reason, String actor) {
        if (reason == null) {
            throw new BadRequestException(
                    "Hiding a rating needs a reason, and it has to be one of: "
                            + java.util.Arrays.toString(HideReason.values()));
        }
        ShopRating rating = ratings.findById(ratingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rating not found"));
        rating.setHiddenAt(LocalDateTime.now());
        rating.setHiddenReason(reason);
        rating.setHiddenBy(actor);
        ShopRating saved = ratings.save(rating);
        auditLog.log("SHOP_RATING_HIDDEN", "ShopRating", saved.getId(),
                reason + " by " + actor
                        + (reason.stillCounts() ? " (still counts towards the average)"
                                                : " (removed from the average)"));
        return saved;
    }

    /** Puts one back. Also audited: an un-hide is a decision too. */
    @Transactional
    public ShopRating unhide(Long ratingId, String actor) {
        ShopRating rating = ratings.findById(ratingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rating not found"));
        rating.setHiddenAt(null);
        rating.setHiddenReason(null);
        rating.setHiddenBy(null);
        ShopRating saved = ratings.save(rating);
        auditLog.log("SHOP_RATING_RESTORED", "ShopRating", saved.getId(), "by " + actor);
        return saved;
    }

    // -------------------------------------------------------- §19 the display

    /** The three figures a storefront shows, for the shop in scope. */
    @Transactional(readOnly = true)
    public ShopRatingSummary summary() {
        RatingTally lifetime = orEmpty(ratings.tallyForever());
        LocalDateTime since = LocalDateTime.now().minusDays(RECENT_DAYS);
        RatingTally recent = orEmpty(ratings.tallySince(since));

        Map<Integer, Long> distribution = new LinkedHashMap<>();
        distribution.put(5, lifetime.fives());
        distribution.put(4, lifetime.fours());
        distribution.put(3, lifetime.threes());
        distribution.put(2, lifetime.twos());
        distribution.put(1, lifetime.ones());

        List<ShopRatingSummary.ReasonCount> topReasons = new ArrayList<>();
        for (Object[] row : ratings.reasonCounts(since)) {
            ShopRatingReason reason = (ShopRatingReason) row[0];
            topReasons.add(new ShopRatingSummary.ReasonCount(
                    reason, ((Number) row[1]).longValue(), reason.isPraise()));
        }

        return new ShopRatingSummary(
                lifetime.averageToOneDecimal(), lifetime.count(),
                recent.averageToOneDecimal(), recent.count(),
                RECENT_DAYS,
                // EVERY SHOP RATING IS VERIFIED. There is no route that makes
                // one without an order, so the verified count is the count -
                // reported separately because saying so is the point (§19).
                lifetime.count(),
                distribution, topReasons);
    }

    /** The ratings a customer sees on a storefront: visible ones only. */
    @Transactional(readOnly = true)
    public Page<ShopRating> visible(Pageable pageable) {
        return ratings.findByHiddenAtIsNullOrderByCreatedAtDesc(pageable);
    }

    /** What the merchant sees: everything about their own shop, hidden included. */
    @Transactional(readOnly = true)
    public Page<ShopRating> all(Pageable pageable) {
        return ratings.findByOrderByCreatedAtDesc(pageable);
    }

    @Transactional(readOnly = true)
    public List<ShopRating> leftBy(Long customerId) {
        return ratings.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }

    @Transactional(readOnly = true)
    public Page<ShopRating> reported(Pageable pageable) {
        return ratings.findByReportedAtIsNotNullOrderByReportedAtDesc(pageable);
    }

    // ------------------------------------------------------------- helpers

    /**
     * A rating belonging to the shop in scope.
     *
     * <p>NO PERMISSION CHECK HERE, AND NONE NEEDED. A load by primary key is
     * not a query, so the Hibernate filter does not reach it - which is
     * exactly the blind spot TenantEntityListener's @PostLoad exists to
     * close. A merchant who types another shop's rating id gets a
     * CrossShopAccessException as the row is materialised, before this method
     * returns and before any of the callers below can act on it.
     *
     * <p>Writing an explicit check here would invite somebody to "simplify"
     * it later by comparing shop ids read off the row, which is a check that
     * silently stops working the day a caller forgets it.
     */
    private ShopRating mine(Long ratingId) {
        return ratings.findById(ratingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rating not found"));
    }

    private static RatingTally orEmpty(RatingTally tally) {
        return tally == null ? RatingTally.empty() : tally;
    }

    private static String trimmedOrNull(String raw, int max) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
