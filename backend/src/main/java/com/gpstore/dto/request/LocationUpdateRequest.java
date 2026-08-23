package com.gpstore.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * A worker's phone reporting where it is.
 *
 * EVERY FIELD HERE IS UNTRUSTED. It arrives from a device the shop does not
 * control, over a network anybody can sit on, and it ends up on an
 * administrator's screen as "where this rider is right now" - which is a claim
 * worth being careful about. Authentication answers WHO is reporting (resolved
 * from the caller's own account, never from this body); these constraints
 * answer whether what they reported is a coordinate at all.
 *
 * WHAT THE BOUNDS CATCH, and they are not hypothetical: a phone with no fix
 * yet happily reports (0, 0), and a unit-conversion slip reports latitudes in
 * the thousands. Neither is rejected by @NotNull, which is all this class used
 * to have, so both went straight into the partner row and onto the map.
 */
public class LocationUpdateRequest {

    @NotNull(message = "Latitude is required")
    @DecimalMin(value = "-90.0", message = "Latitude must be between -90 and 90")
    @DecimalMax(value = "90.0", message = "Latitude must be between -90 and 90")
    private Double latitude;

    @NotNull(message = "Longitude is required")
    @DecimalMin(value = "-180.0", message = "Longitude must be between -180 and 180")
    @DecimalMax(value = "180.0", message = "Longitude must be between -180 and 180")
    private Double longitude;

    /**
     * How good the phone thinks this fix is, in metres. Optional.
     *
     * Sent so the server can throw away a fix too vague to be worth showing.
     * A 2 km accuracy radius is a cell-tower guess, not a position, and
     * drawing it on a map as a rider's location is worse than drawing nothing:
     * it looks exactly as confident as a real fix.
     */
    private Double accuracyMeters;

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Double getAccuracyMeters() { return accuracyMeters; }
    public void setAccuracyMeters(Double accuracyMeters) { this.accuracyMeters = accuracyMeters; }
}
