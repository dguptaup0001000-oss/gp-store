package com.gpstore.territory;

import com.gpstore.service.DeliveryEstimateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A guard on the one coordinate everything else is measured from.
 *
 * WHAT WENT WRONG ONCE. store.latitude/longitude shipped as 28.6139, 77.2090 -
 * Connaught Place, Delhi - as a placeholder default, roughly 700 km from the
 * actual shop in Kushinagar. Nothing failed. The application booted, checkout
 * worked, and every delivery fee, every ETA, and every serviceable-radius
 * decision was computed from a point in another state. A wrong constant that
 * throws is a bug you fix in an hour; a wrong constant that quietly answers is
 * one you ship.
 *
 * WHAT THIS ASSERTS, and why it is not the exact coordinates. Pinning the
 * literal values would fail the day the shop legitimately moves, which trains
 * whoever hits it to edit the test rather than think. The real failure mode is
 * narrower and permanent: reverting to placeholder data. So this asserts the
 * configured point is not the known placeholder, and that it is somewhere a
 * shop in India could actually be.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class StoreLocationTest {

    /** The placeholder that shipped as a default and was measured from for real. */
    private static final double PLACEHOLDER_LAT = 28.6139;
    private static final double PLACEHOLDER_LNG = 77.2090;

    @Value("${store.latitude}") private double storeLatitude;
    @Value("${store.longitude}") private double storeLongitude;

    @Autowired private DeliveryEstimateService estimates;

    @Test
    @DisplayName("the shop is not sitting on the Delhi placeholder")
    void notThePlaceholder() {
        boolean isPlaceholder = Math.abs(storeLatitude - PLACEHOLDER_LAT) < 0.0001
                && Math.abs(storeLongitude - PLACEHOLDER_LNG) < 0.0001;

        assertFalse(isPlaceholder,
                "store.latitude/longitude are back on Connaught Place, Delhi. Every delivery fee, "
                        + "ETA and serviceable-radius check in the shop is now being measured from "
                        + "there, and nothing else will report it.");
    }

    @Test
    @DisplayName("the shop is somewhere a shop in India could be")
    void withinIndia() {
        // Deliberately loose - this is a sanity check on a transcription
        // mistake (a swapped pair, a dropped minus, a decimal in the wrong
        // place), not an attempt to validate the address.
        assertTrue(storeLatitude > 6.0 && storeLatitude < 37.5,
                "latitude " + storeLatitude + " is outside India");
        assertTrue(storeLongitude > 68.0 && storeLongitude < 97.5,
                "longitude " + storeLongitude + " is outside India");

        // The classic transcription error: latitude and longitude swapped.
        // In India longitude is always the larger of the two, so a pair where
        // it is not has almost certainly been written the wrong way round.
        assertTrue(storeLongitude > storeLatitude,
                "longitude (" + storeLongitude + ") should exceed latitude (" + storeLatitude
                        + ") everywhere in India - these look swapped");
    }

    @Test
    @DisplayName("the shop has a real position at all")
    void isSet() {
        assertNotEquals(0.0, storeLatitude, 1e-9, "0,0 is in the Atlantic Ocean");
        assertNotEquals(0.0, storeLongitude, 1e-9, "0,0 is in the Atlantic Ocean");
    }

    @Test
    @DisplayName("the delivery radius fits inside the ETA promise, so nothing is under-promised")
    void radiusDoesNotOutrunTheEtaCap() {
        // THE FAILURE THIS CATCHES, which nothing else would. estimateMinutes
        // is distance-based but hard-capped, so past a certain distance every
        // address is quoted the same maximum however far away it really is.
        // Widen the radius past that point and nothing breaks, nothing logs:
        // far customers are simply told a time the shop's own arithmetic says
        // it cannot meet, and the delivery-guarantee check starts reporting
        // breaches that were designed in.
        //
        // At the radius set here the two are in agreement, and this asserts
        // that they stay so. If the radius is deliberately widened past the
        // cap, raise MAX_MINUTES in DeliveryEstimateService in the same
        // change - do not delete this test.
        double radiusKm = estimates.getMaxDeliveryRadiusKm();

        // A point due north at exactly the edge of the serviceable area.
        double edgeLat = storeLatitude + radiusKm / 111.32;

        assertTrue(estimates.isWithinServiceableRadius(edgeLat, storeLongitude),
                "the edge of the radius must itself be deliverable, or this test is measuring "
                        + "the wrong point");

        int atTheEdge = estimates.estimateMinutes(edgeLat, storeLongitude);

        // An address with no coordinates falls back to the cap, which makes it
        // a reliable way to read the cap without reaching into a private
        // constant.
        int theCap = estimates.estimateMinutes(null, null);

        assertTrue(atTheEdge < theCap,
                "the furthest deliverable address is quoted " + atTheEdge + " minutes, which is the "
                        + theCap + "-minute ceiling rather than a real estimate. Every address past "
                        + "the point where the cap binds is being under-promised. Either bring the "
                        + "radius back inside the cap or raise the cap to match the radius.");
    }
}
