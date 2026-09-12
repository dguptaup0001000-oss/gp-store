package com.gpstore.rating;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One shop rating as it is shown, with nothing on it that should not be.
 *
 * <p>WHAT IS DELIBERATELY ABSENT: the customer's id. A storefront listing its
 * ratings is public-facing, and "customer 4412 gave us one star" is an
 * invitation to go and find customer 4412. The shop needs to answer the
 * rating, not the person, and the order id is on the row for the one case
 * where they genuinely need to look it up.
 *
 * <p>{@code hidden} is present rather than the row being omitted, because
 * the merchant's own list shows hidden ratings - with the reason - so that
 * moderation is visible to the shop it happened to.
 */
public record ShopRatingView(
        Long id,
        Long orderId,
        int rating,
        String comment,
        List<ShopRatingReason> reasons,
        LocalDateTime createdAt,
        String merchantResponse,
        LocalDateTime merchantResponseAt,
        String customerReply,
        LocalDateTime customerReplyAt,
        boolean hidden,
        HideReason hiddenReason,
        boolean reported,
        boolean countsTowardsTheAverage) {

    /** The customer-facing shape: a hidden rating never reaches here. */
    public static ShopRatingView from(ShopRating rating) {
        return new ShopRatingView(
                rating.getId(), rating.getOrderId(),
                rating.getRating() == null ? 0 : rating.getRating(),
                rating.getComment(),
                rating.getReasons() == null ? List.of() : List.copyOf(rating.getReasons()),
                rating.getCreatedAt(),
                rating.getMerchantResponse(), rating.getMerchantResponseAt(),
                rating.getCustomerReply(), rating.getCustomerReplyAt(),
                !rating.isVisible(), rating.getHiddenReason(),
                rating.isReported(), rating.countsTowardsTheAverage());
    }
}
