package com.gpstore.rating;

/**
 * The raw counts behind a rating summary, straight out of one query.
 *
 * <p>A CONSTRUCTOR EXPRESSION, not a projection interface, so the aggregate
 * runs in the database and comes back as one row. The alternative - loading
 * every rating a shop has ever had to average them in Java - is fine on the
 * day a shop has nine ratings and is a full table scan on the day it has
 * ninety thousand.
 *
 * <p>Longs may arrive null from SUM over an empty set; {@link #or0} is why
 * nothing downstream has to remember that.
 */
public record RatingTally(
        long count, double average,
        Long five, Long four, Long three, Long two, Long one) {

    public static RatingTally empty() {
        return new RatingTally(0L, 0.0, 0L, 0L, 0L, 0L, 0L);
    }

    private static long or0(Long value) {
        return value == null ? 0L : value;
    }

    public long fives()  { return or0(five); }
    public long fours()  { return or0(four); }
    public long threes() { return or0(three); }
    public long twos()   { return or0(two); }
    public long ones()   { return or0(one); }

    /** Rounded to one decimal, the way a badge shows it. */
    public double averageToOneDecimal() {
        return Math.round(average * 10.0) / 10.0;
    }
}
