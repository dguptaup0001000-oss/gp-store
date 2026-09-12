package com.gpstore.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryEstimateServiceTest {

    // Store at (0,0) for easy, exact distance math in tests.
    private final DeliveryEstimateService service = new DeliveryEstimateService(0.0, 0.0, 8.0,
            // No shop in scope in a plain unit test, so the configured
            // store coordinates are the origin - which is exactly the
            // fallback this asserts.
            org.mockito.Mockito.mock(com.gpstore.platform.ShopRepository.class));

    @Test
    void distanceToSamePointIsZero() {
        assertEquals(0.0, service.distanceFromStoreKm(0.0, 0.0), 0.01);
    }

    @Test
    void distanceIsNaNWhenCoordinatesMissing() {
        assertTrue(Double.isNaN(service.distanceFromStoreKm(null, null)));
    }

    @Test
    void estimateNeverGoesBelowTenMinutes() {
        // Same location as the store - distance is 0, should still floor at 10 min.
        int minutes = service.estimateMinutes(0.0, 0.0);
        assertEquals(10, minutes);
    }

    @Test
    void estimateNeverExceedsTwoHours() {
        // Very far away (roughly 1000km+) - should cap at 120, not scale forever.
        int minutes = service.estimateMinutes(10.0, 10.0);
        assertEquals(120, minutes);
    }

    @Test
    void unknownLocationFallsBackToMaxWindow() {
        // No coordinates -> can't verify distance, so we assume the widest
        // window rather than promising something we can't back up.
        assertEquals(120, service.estimateMinutes(null, null));
    }

    @Test
    void withinRadiusIsDeliverable() {
        // ~0.01 degrees is well under 8km at the equator.
        assertTrue(service.isWithinServiceableRadius(0.01, 0.01));
    }

    @Test
    void beyondRadiusIsNotDeliverable() {
        assertFalse(service.isWithinServiceableRadius(10.0, 10.0));
    }

    @Test
    void missingCoordinatesAreNotDeliverable() {
        // Fails closed - we never assume something is deliverable without proof.
        assertFalse(service.isWithinServiceableRadius(null, null));
    }
}
