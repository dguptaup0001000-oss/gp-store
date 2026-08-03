package com.gpstore.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * What a delivery partner's app sends every few seconds while on a run to
 * push its live GPS position.
 */
public class LocationUpdateRequest {

    @NotNull(message = "Latitude is required")
    private Double latitude;

    @NotNull(message = "Longitude is required")
    private Double longitude;

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
}
