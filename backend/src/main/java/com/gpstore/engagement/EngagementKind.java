package com.gpstore.engagement;

/**
 * The only things GP-STORE actually observes about an offline listing.
 *
 * <p>EACH OF THESE IS SOMETHING THAT HAPPENED IN THE APP. The card was drawn;
 * the customer opened it; they asked for directions; they rang the shop. No
 * entry here is an inference, a model or an estimate, which is what makes the
 * numbers safe to show a merchant.
 *
 * <p>WHAT IS DELIBERATELY ABSENT: anything about a sale. There is no VISITED,
 * no BOUGHT and no value. A customer who taps Directions may walk in and buy,
 * may change their mind on the way, or may have tapped by accident - GP-STORE
 * cannot tell those apart and must not pretend otherwise.
 */
public enum EngagementKind {
    /** The card appeared in a feed or a search result the customer scrolled to. */
    VIEWED_CARD,

    /** They opened it, which is a deliberate act rather than a scroll past. */
    OPENED_DETAIL,

    /** They asked for directions - the strongest signal of real intent here. */
    ASKED_DIRECTIONS,

    /** They rang the shop's public number from the listing. */
    CALLED_SHOP;

    /** Parses a wire value strictly: an unknown kind is refused, not guessed. */
    public static EngagementKind of(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
