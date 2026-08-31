package com.gpstore.store;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;

/**
 * Every hour, minute and timezone the delivery schedule runs on, in one place.
 *
 * <p>WHY THIS CLASS EXISTS. 09:00, 21:00, 08:00 and 15 minutes are the kind of
 * numbers that get typed into whichever file needs them next - a controller
 * here, a scheduled job there, a Flutter widget that hardcodes the same 21:00
 * again. The day the shop wants to close at 20:00 instead, somebody changes
 * four of the six and the other two keep quoting the old hour to customers.
 * Nothing outside this class may name those numbers.
 *
 * <p>THE TIMEZONE IS THE SHOP'S, NOT THE SERVER'S AND NOT THE PHONE'S. The JVM
 * runs Etc/UTC on purpose (see application.properties - the JDBC session
 * timezone is pinned to UTC so timestamps round-trip unchanged), so
 * {@code LocalTime.now()} on this server is 05:30 behind the shop and would
 * put the evening cutoff at 15:30 India time. Every calculation converts an
 * Instant into {@link #getZone()} explicitly. Asia/Kolkata has no daylight
 * saving, but the conversion is still done through ZoneId rather than a fixed
 * +05:30 offset, because a fixed offset is a bug waiting for the first shop in
 * a zone that does observe it.
 */
@Component
@ConfigurationProperties(prefix = "store.schedule")
public class StoreScheduleProperties {

    private static final Logger log = LoggerFactory.getLogger(StoreScheduleProperties.class);

    /**
     * The shop's own timezone. Customers' phones are irrelevant: a customer
     * in London ordering from a Kanpur shop is subject to Kanpur's hours.
     */
    private ZoneId zone = ZoneId.of("Asia/Kolkata");

    /** When the vans start. Deliveries are never scheduled before this. */
    private LocalTime deliveryStart = LocalTime.of(9, 0);

    /** When the vans stop. An order placed at or after this waits for the next window. */
    private LocalTime deliveryEnd = LocalTime.of(21, 0);

    /**
     * When staff start packing the orders taken overnight, so the 09:00 run
     * leaves loaded. Read by the morning preparation list, not by pricing.
     */
    private LocalTime morningPreparation = LocalTime.of(8, 0);

    /**
     * How long before {@link #deliveryEnd} the customer is warned that
     * same-day delivery is about to close. A warning, not a cutoff: an order
     * placed at 20:59 is still same-day.
     */
    private Duration closingCountdown = Duration.ofMinutes(15);

    /**
     * How far ahead to look for an open day before giving up.
     *
     * <p>Bounds the search in {@code DeliverySchedule}: without it, a shop
     * marked closed indefinitely turns "when is the next delivery?" into an
     * infinite loop on a request thread. Thirty days is far longer than any
     * real closure and short enough to fail visibly.
     */
    private int maxClosureLookaheadDays = 30;

    /**
     * Rejects a configuration that cannot describe a working day, at startup,
     * rather than letting it produce nonsense windows on the order path.
     *
     * <p>Deliberately fails fast rather than silently substituting defaults: a
     * shop that typed its hours in backwards should be told, not quietly
     * given somebody else's.
     */
    @PostConstruct
    void validate() {
        if (zone == null) {
            throw new IllegalStateException("store.schedule.zone must be set");
        }
        if (deliveryStart == null || deliveryEnd == null || morningPreparation == null) {
            throw new IllegalStateException(
                    "store.schedule delivery-start, delivery-end and morning-preparation must all be set");
        }
        if (!deliveryStart.isBefore(deliveryEnd)) {
            throw new IllegalStateException(
                    "store.schedule.delivery-start (" + deliveryStart + ") must be before delivery-end ("
                            + deliveryEnd + "); a window that ends before it starts has no deliverable minutes");
        }
        if (morningPreparation.isAfter(deliveryStart)) {
            throw new IllegalStateException(
                    "store.schedule.morning-preparation (" + morningPreparation + ") is after delivery-start ("
                            + deliveryStart + "); the packing list would be built after the van has left");
        }
        if (closingCountdown == null || closingCountdown.isNegative()) {
            closingCountdown = Duration.ZERO;
        }
        // A countdown longer than the window would be "closing soon" from the
        // moment the shop opens, which trains customers to ignore it.
        Duration window = Duration.between(deliveryStart, deliveryEnd);
        if (closingCountdown.compareTo(window) > 0) {
            log.warn("store.schedule.closing-countdown ({}) is longer than the delivery window ({}); "
                    + "clamping it to the window", closingCountdown, window);
            closingCountdown = window;
        }
        if (maxClosureLookaheadDays < 1) {
            maxClosureLookaheadDays = 1;
        }
        log.info("Store schedule: deliveries {}-{} {}, prep {}, closing warning {} before close",
                deliveryStart, deliveryEnd, zone, morningPreparation, closingCountdown);
    }

    public ZoneId getZone() {
        return zone;
    }

    /**
     * Accepts a zone id string from configuration.
     *
     * <p>An unknown id throws {@link ZoneRulesException} from ZoneId.of, which
     * fails startup with the offending value named - the right outcome for a
     * typo that would otherwise silently fall back to UTC and move every
     * cutoff by five and a half hours.
     */
    public void setZone(String zone) {
        this.zone = ZoneId.of(zone.trim());
    }

    public LocalTime getDeliveryStart() {
        return deliveryStart;
    }

    public void setDeliveryStart(LocalTime deliveryStart) {
        this.deliveryStart = deliveryStart;
    }

    public LocalTime getDeliveryEnd() {
        return deliveryEnd;
    }

    public void setDeliveryEnd(LocalTime deliveryEnd) {
        this.deliveryEnd = deliveryEnd;
    }

    public LocalTime getMorningPreparation() {
        return morningPreparation;
    }

    public void setMorningPreparation(LocalTime morningPreparation) {
        this.morningPreparation = morningPreparation;
    }

    public Duration getClosingCountdown() {
        return closingCountdown;
    }

    public void setClosingCountdown(Duration closingCountdown) {
        this.closingCountdown = closingCountdown;
    }

    public int getMaxClosureLookaheadDays() {
        return maxClosureLookaheadDays;
    }

    public void setMaxClosureLookaheadDays(int maxClosureLookaheadDays) {
        this.maxClosureLookaheadDays = maxClosureLookaheadDays;
    }
}
