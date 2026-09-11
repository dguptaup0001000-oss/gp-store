package com.gpstore.rating;

import java.util.List;
import java.util.Map;

/**
 * How a shop's rating is SHOWN (§19), which is not the same as what it is.
 *
 * <p>ONE NUMBER IS MISLEADING IN BOTH DIRECTIONS. A shop with 4.8 from six
 * ratings is not more trustworthy than one with 4.4 from nine hundred, and a
 * shop that was terrible two years ago and excellent since deserves to be
 * read as it is now. §19 therefore asks for three things at once, and this
 * record is all three: the lifetime figure, the recent figure, and how many
 * people are actually behind them.
 *
 * <p>EVERY RATING HERE IS VERIFIED, and {@link #verifiedCount} says so
 * plainly rather than leaving it implied. A shop rating requires a real order
 * at that shop - there is no path that produces an unverified one - so the
 * count is the whole count. It is reported as its own field because a
 * customer reading "4.6 from 212 verified orders" is being told something a
 * bare "4.6 (212)" does not say.
 *
 * @param recentDays    the width of the "recently" window
 * @param distribution  how many gave each star count, 5 down to 1
 * @param topReasons    the commonest reasons given (§18), commonest first
 */
public record ShopRatingSummary(
        double average,
        long count,
        double recentAverage,
        long recentCount,
        int recentDays,
        long verifiedCount,
        Map<Integer, Long> distribution,
        List<ReasonCount> topReasons) {

    /** One reason and how often it was given. */
    public record ReasonCount(ShopRatingReason reason, long count, boolean praise) {}

    /**
     * A shop nobody has rated yet.
     *
     * <p>ZERO, NOT NULL, AND NOT A DEFAULT OF THREE. A new kirana has no
     * rating; inventing a middling one for it would misrepresent the shop to
     * a customer and is exactly the sort of number that ends up in a ranking.
     */
    public static ShopRatingSummary unrated(int recentDays) {
        return new ShopRatingSummary(0.0, 0L, 0.0, 0L, recentDays, 0L,
                Map.of(5, 0L, 4, 0L, 3, 0L, 2, 0L, 1, 0L), List.of());
    }

    /** Whether there is enough here to show a number at all. */
    public boolean hasEnoughToShow() {
        return count > 0;
    }
}
