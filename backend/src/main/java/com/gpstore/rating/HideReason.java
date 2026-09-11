package com.gpstore.rating;

/**
 * The ONLY grounds on which a rating may stop being shown (§20).
 *
 * <p>§20 SAYS GENUINE NEGATIVE REVIEWS MUST REMAIN, and a sentence in a
 * policy document does not make that true. What makes it true is that
 * removing a rating requires picking one of these, that the row survives with
 * the choice and the chooser recorded on it, and that the list is a Java enum
 * a reviewer can read in full in ten seconds.
 *
 * <p>READ WHAT IS NOT HERE. There is no UNFAIR, no INACCURATE, no
 * DAMAGES_OUR_RATING, no CUSTOMER_IS_MISTAKEN, and no generic OTHER - an
 * OTHER would be all of them wearing a hat. Every value here describes
 * something wrong with the TEXT, never something wrong with the OPINION. A
 * one-star rating saying the dal was stale is not hideable by any of them,
 * which is the entire point.
 *
 * <p>NOTHING IS DELETED EITHER WAY. Hiding sets a timestamp; the row, the
 * stars and the words stay, so "why do this shop's one-star ratings keep
 * disappearing" is a question the database can answer.
 */
public enum HideReason {

    /** Insults or threats aimed at a person. */
    ABUSIVE_LANGUAGE,

    /** Somebody's phone number, address or account details in the text. */
    PERSONAL_INFORMATION,

    /** Advertising, a link farm, or the same text posted repeatedly. */
    SPAM,

    /** The text is about a different order, shop or item entirely. */
    NOT_ABOUT_THIS_ORDER,

    /** Written as though by the shop, the platform or another customer. */
    IMPERSONATION,

    /** Content that cannot lawfully be published. */
    ILLEGAL_CONTENT;

    /**
     * Whether a hidden rating still counts towards the shop's average.
     *
     * <p>IT DOES, AND THAT IS DELIBERATE for everything except content that
     * was never a rating in the first place. If hiding the words also erased
     * the stars, hiding would be worth doing for the arithmetic alone, and
     * §20 would be one moderation queue away from meaningless. Spam and
     * impersonation are excluded because neither was a customer's opinion of
     * the shop to begin with.
     */
    public boolean stillCounts() {
        return this != SPAM && this != IMPERSONATION;
    }
}
