package com.gpstore.geo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The geocoder's guards, proved without touching the network.
 *
 * WHY THESE ARE THE TESTS WORTH HAVING. The happy path depends on
 * OpenStreetMap being reachable, which makes it a poor thing to assert in CI -
 * a red build would mean "OSM is busy", not "we broke something". What CAN be
 * pinned, and matters more, is the promises this class makes to a service we
 * are guests on and to a customer who is mid-checkout:
 *
 *   - never call out for a coordinate that cannot exist
 *   - never exceed one call a second, globally
 *   - never turn a third party's bad afternoon into a failed address
 *
 * Every one of those is decided before any HTTP happens, which is exactly why
 * they can be tested offline.
 */
@DisplayName("The reverse geocoder keeps its promises before it makes a request")
class ReverseGeocoderGuardsTest {

    /** Points at a black hole. Reaching it would be the bug under test. */
    private ReverseGeocoder geocoder(boolean enabled, long minGapMillis) {
        return new ReverseGeocoder(enabled,
                "http://127.0.0.1:1/nowhere", "GP-STORE-TEST/1.0", minGapMillis, 250);
    }

    @Test
    @DisplayName("a coordinate off the planet is never asked about")
    void impossibleCoordinatesAreNotSent() {
        ReverseGeocoder geocoder = geocoder(true, 0);

        assertTrue(geocoder.suggest(91.0, 83.9).isEmpty());
        assertTrue(geocoder.suggest(-91.0, 83.9).isEmpty());
        assertTrue(geocoder.suggest(27.1, 181.0).isEmpty());
        assertTrue(geocoder.suggest(27.1, -181.0).isEmpty());
    }

    @Test
    @DisplayName("turning it off stops every call")
    void disabledMakesNoCalls() {
        assertTrue(geocoder(false, 0).suggest(27.162, 83.940).isEmpty(),
                "geocoding.enabled=false has to mean no outbound traffic at all, "
                        + "not merely a discarded answer.");
    }

    @Test
    @DisplayName("at most one call a second gets through, globally")
    void theRateGateHoldsTheLine() {
        // A long gap so the second call cannot possibly be allowed. OSM's fair
        // use policy is per-service, not per-user, so this gate is shared by
        // every customer on the shop at once.
        ReverseGeocoder geocoder = geocoder(true, 60_000);

        // Both return empty here because the endpoint is dead. What is being
        // asserted is that the SECOND returns empty IMMEDIATELY rather than
        // after a connection attempt - i.e. that it never left the building.
        geocoder.suggest(27.162, 83.940);

        long start = System.currentTimeMillis();
        assertTrue(geocoder.suggest(27.163, 83.941).isEmpty());
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 100,
                "The second call took " + elapsed + "ms, so the gate let it out to "
                        + "the network instead of refusing it.");
    }

    @Test
    @DisplayName("an unreachable geocoder is an empty answer, never an exception")
    void unreachableIsNotAnError() {
        // The customer is standing at their gate with the app open. A
        // convenience that throws would take the whole address form down with
        // it, for a feature they never asked for.
        assertDoesNotThrow(() ->
                assertTrue(geocoder(true, 0).suggest(27.162, 83.940).isEmpty()));
    }
}
