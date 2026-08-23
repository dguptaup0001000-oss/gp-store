package com.gpstore.territory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
}
