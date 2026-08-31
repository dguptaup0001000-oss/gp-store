package com.gpstore.store;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * What the app is told about the shop, and the clock it should trust.
 *
 * <p>CARRIES THE SERVER'S TIME ON PURPOSE. A phone whose clock is twenty
 * minutes fast would otherwise show "same-day ordering closed" while the shop
 * is still delivering, or - worse - show it open after close and let someone
 * reach a checkout that rejects them. The client renders the countdown by
 * ticking down {@code countdownSeconds} from the moment the response arrived,
 * never by comparing its own clock against {@code windowEnd}.
 *
 * <p>EVERY FLAG HERE IS ADVISORY. The app uses them to decide what to show;
 * the server decides what to allow, and re-checks on the order path. A client
 * that ignores {@code acceptingOrders} entirely gets a 409 at checkout.
 *
 * @param serverTime        the shop's clock at the moment this was built
 * @param browsingOpen      always true - the catalogue never closes
 * @param acceptingOrders   whether checkout would be accepted right now
 * @param mode              SAME_DAY while the vans are out, NIGHT otherwise
 * @param deliveryType      what an order placed now would be, or null
 * @param deliveryDate      the shop-local day it would arrive, or null
 * @param windowStart       when that day's deliveries begin
 * @param windowEnd         when they end
 * @param deliveryStartTime the shop's opening time, for "delivers from 9 AM"
 * @param deliveryEndTime   the shop's closing time
 * @param countdownSeconds  seconds of same-day ordering left, or null
 * @param closedToday       whether today is a declared full-day closure
 * @param message           the shop's own words about why, or null
 */
public record StoreStatusResponse(
        Instant serverTime,
        boolean browsingOpen,
        boolean acceptingOrders,
        StoreMode mode,
        DeliveryType deliveryType,
        LocalDate deliveryDate,
        Instant windowStart,
        Instant windowEnd,
        LocalTime deliveryStartTime,
        LocalTime deliveryEndTime,
        Long countdownSeconds,
        boolean closedToday,
        String message) {

    public static StoreStatusResponse from(StoreStatus status, StoreScheduleProperties properties) {
        DeliveryWindow window = status.nextWindow();
        Duration remaining = status.countdownRemaining();
        return new StoreStatusResponse(
                status.at(),
                status.browsingOpen(),
                status.acceptingOrders(),
                status.mode(),
                status.deliveryType(),
                status.deliveryDate(),
                window == null ? null : window.start(),
                window == null ? null : window.end(),
                properties.getDeliveryStart(),
                properties.getDeliveryEnd(),
                // Null rather than 0 when inactive, so a client cannot render
                // "closes in 0 minutes" at nine in the morning by forgetting
                // to check a separate flag.
                remaining == null ? null : remaining.toSeconds(),
                status.closedToday(),
                status.closureReason());
    }
}
