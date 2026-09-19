package com.gpstore.catalog.shop;

/**
 * Whether a thing bought in person can actually be had, in the words a
 * shopkeeper would use.
 *
 * <p>DELIBERATELY NOT A QUANTITY. Online stock is a number because the
 * application has to decrement it inside a transaction and refuse the
 * hundred-and-first order. Nothing decrements when somebody walks in, and a
 * jeweller counting rings into GP-STORE every morning would simply stop
 * using it. Worse, publishing "3 left" invites a customer to drive across
 * town on the strength of a number nobody is maintaining.
 *
 * <p>So an offline listing answers the question a customer is really asking -
 * is it worth the journey - and the merchant keeps that answer current with
 * one tap rather than a stock take. A merchant who genuinely wants to publish
 * counts can still do so on an ONLINE_PURCHASE listing, where the number is
 * real because the system maintains it.
 */
public enum OfflineAvailability {

    /** In the shop now. Worth the journey. */
    AVAILABLE,

    /** In the shop, but not much of it - come soon, or ring first. */
    LIMITED_AVAILABILITY,

    /** Not on the shelf; the merchant makes or orders it when you ask. */
    MADE_TO_ORDER,

    /** Not available at the moment. Still discoverable, so the customer knows who has it when it returns. */
    OUT_OF_STOCK,

    /** The merchant would rather answer this one personally. */
    CONTACT_SHOP;

    /**
     * Whether it is worth telling a customer to make the trip today.
     *
     * <p>Not a purchase guard - nothing here can be purchased through the
     * application by definition. It exists so a client can sort and label
     * without each one inventing its own opinion of these five words.
     */
    public boolean worthVisitingToday() {
        return this == AVAILABLE || this == LIMITED_AVAILABILITY;
    }
}
