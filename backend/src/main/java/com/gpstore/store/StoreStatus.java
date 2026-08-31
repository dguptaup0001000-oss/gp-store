package com.gpstore.store;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * What the shop is doing at one instant, with the three concepts held apart.
 *
 * <p>THE WHOLE POINT OF THIS TYPE is that "the store is closed" is three
 * different questions and the shop answers them differently:
 *
 * <ol>
 *   <li>{@code browsingOpen} - may a customer look at products? ALWAYS YES.
 *       There is no code path that sets this false. It is a field rather than
 *       a constant so the API response says so out loud, and so the day
 *       somebody proposes gating the catalogue there is one obvious place
 *       where that lie would have to be written.
 *   <li>{@code acceptingOrders} - may a customer place an order right now?
 *       Normally yes at 3am; no only if the owner turned it off, or the shop
 *       has no reachable delivery day.
 *   <li>{@code mode} / {@code nextWindow} - WHEN will it arrive? This is the
 *       one the clock actually decides.
 * </ol>
 *
 * <p>Collapsing these is how a shop that is very much open tells a customer at
 * 11pm that it is closed, and loses the order.
 *
 * @param at                 the instant this answer describes
 * @param mode               same-day or night, derived from the clock
 * @param browsingOpen       always true; see above
 * @param acceptingOrders    whether a new order may be created now
 * @param acceptance         the owner's override that produced acceptingOrders
 * @param closedToday        whether the shop-local today is a full-day closure
 * @param closureReason      what to tell the customer, or null
 * @param nextWindow         the next delivery run, or null if none is reachable
 * @param deliveryType       what an order placed now would be, or null if none
 * @param countdownRemaining time left of same-day ordering, or null if not close
 */
public record StoreStatus(
        Instant at,
        StoreMode mode,
        boolean browsingOpen,
        boolean acceptingOrders,
        StoreOrderAcceptance acceptance,
        boolean closedToday,
        String closureReason,
        DeliveryWindow nextWindow,
        DeliveryType deliveryType,
        Duration countdownRemaining) {

    /** Whether the "same-day ordering closes in N minutes" warning is showing. */
    public boolean countdownActive() {
        return countdownRemaining != null;
    }

    /** The day an order placed now would arrive, or null if none is reachable. */
    public LocalDate deliveryDate() {
        return nextWindow == null ? null : nextWindow.date();
    }
}
