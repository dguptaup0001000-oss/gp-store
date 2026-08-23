package com.gpstore.pricing;

import java.math.BigDecimal;
import java.util.List;

/**
 * One delivery price, with every step that produced it.
 *
 * WHY THE WORKING IS CARRIED AND NOT JUST THE ANSWER. The admin screen has to
 * show how a charge was arrived at - distance, weight, normal charge, profit,
 * subsidy - and recomputing it later would be a different number: settings
 * change, costs change, and the customer was charged what they were charged.
 * The quote IS the record.
 *
 * WHAT THE CUSTOMER SEES is only {@link #finalCharge} and
 * {@link #freeDelivery}. Everything else here is the shop's business, and §9
 * of the brief is explicit that the profit arithmetic must not leak into the
 * app.
 */
public record DeliveryQuote(

        /** Road kilometres if a router supplied them, otherwise the straight line. */
        BigDecimal distanceKm,

        /** True when {@link #distanceKm} is a straight line rather than a measured route. */
        boolean distanceEstimated,

        BigDecimal totalWeightKg,

        BigDecimal distanceCharge,
        BigDecimal weightCharge,

        /** distanceCharge + weightCharge. What delivery costs before any subsidy. */
        BigDecimal normalCharge,

        /** Gross margin available on this order to spend on delivery. */
        BigDecimal orderProfit,

        /** multiplier x normalCharge - the profit that would make delivery free. */
        BigDecimal freeDeliveryRequiredProfit,

        boolean freeDelivery,

        /** How much of the normal charge the order's own margin absorbed. */
        BigDecimal subsidy,

        /** What the customer actually pays. Never above normal, never below zero. */
        BigDecimal finalCharge,

        /**
         * Anything the shop should know about how this was calculated -
         * a missing cost price, an item with no weight, an estimated distance.
         *
         * Never shown to a customer. Never silently dropped either: §12 of the
         * brief requires missing financial data to be logged for review rather
         * than assumed away, and this is where that surfaces.
         */
        List<String> warnings) {

    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }

    /** The single line the customer app is allowed to render. */
    public String customerLabel() {
        return freeDelivery ? "FREE DELIVERY" : "Delivery ₹" + finalCharge.stripTrailingZeros().toPlainString();
    }
}
