package com.gpstore.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Everything the shop knows about one customer, in one answer.
 *
 * ONE CALL, NOT SIX. The admin app could stitch this together from the
 * customer, address, cart, wishlist and order endpoints - and on a shop
 * counter's connection that is five chances to half-load a screen and show a
 * shopkeeper a customer with no addresses because one request timed out.
 * Assembling it server-side means the screen is either right or absent.
 *
 * STAFF-ONLY, AND SHAPED THAT WAY. This carries a named person's phone
 * number, their home address and what is sitting in their basket. It is built
 * only behind CUSTOMERS_VIEW and there is no customer-facing route that
 * returns it. Notably absent: the password hash, the FCM token, and anything
 * about how they pay - none of which help a shopkeeper serve somebody, and
 * all of which would be a liability on a screen.
 */
public record AdminCustomerDetailResponse(
        Long id,
        String fullName,
        String email,
        String mobileNumber,
        String role,
        Boolean active,
        Boolean verified,
        String profileImageUrl,

        List<AddressLine> addresses,
        CartSummary cart,
        List<WishlistLine> wishlist,
        OrderStats orders,
        Engagement engagement,
        DeliveryConduct conduct
) {

    public record AddressLine(
            Long id,
            String label,
            String fullName,
            String mobileNumber,
            String address,
            String landmark,
            /** The customer's own directions, in their words. */
            String directions,
            String pincode,
            Boolean isDefault,
            /** Whether a pin was ever captured - a home nobody can find is a fact worth showing. */
            Boolean hasLocation
    ) {}

    public record CartSummary(
            Integer totalItems,
            BigDecimal totalAmount,
            List<CartLine> items
    ) {}

    public record CartLine(
            String productName,
            String pack,
            Integer quantity,
            BigDecimal totalPrice,
            String imageUrl
    ) {}

    public record WishlistLine(
            Long productId,
            String productName,
            String brand,
            String imageUrl
    ) {}

    /**
     * @param firstOrderDate deliberately NOT called "joined". The customers
     *                       table has never had a created-at column, so the
     *                       date somebody signed up is genuinely not recorded
     *                       and inventing one from their first order would be
     *                       a plausible-looking lie on an admin screen.
     */
    public record OrderStats(
            long count,
            BigDecimal lifetimeSpend,
            LocalDateTime firstOrderDate,
            LocalDateTime lastOrderDate,
            long cancelledCount
    ) {}

    /**
     * How much the app has actually been used.
     *
     * CLIENT-REPORTED AND CAPPED, so treat it as an impression rather than a
     * measurement - it distinguishes a regular from somebody who installed the
     * app once and never came back, which is what it is for. It is not
     * evidence and nothing should be decided against a person on it.
     */
    public record Engagement(
            long totalSeconds,
            long sessionCount,
            LocalDateTime lastSeen
    ) {}

    /**
     * How riders have found this customer at the door.
     *
     * SCORED BY THE PERSON WHO WAS THERE, out of ten, one rating per delivery.
     * It exists because a shop needs to know before the van leaves whether an
     * address means an argument - the abusive customer, the one who is never
     * in, the one who refuses at the door after the goods have been carried up
     * three floors.
     *
     * averageScore is NULL, not zero, for a customer nobody has rated. Zero
     * would read as the worst possible customer, which is the opposite of "we
     * do not know". The screen must render the two differently.
     *
     * A JUDGEMENT BY ONE PERSON ON ONE DAY. Two ratings is a hunch, not a
     * pattern, so ratedDeliveries is carried alongside the average rather
     * than hidden behind it - and nothing here should be automated into
     * refusing somebody service.
     */
    public record DeliveryConduct(
            Double averageScore,
            long ratedDeliveries,
            List<ConductLine> recent
    ) {}

    public record ConductLine(
            Long orderId,
            Integer score,
            LocalDateTime ratedAt
    ) {}
}
