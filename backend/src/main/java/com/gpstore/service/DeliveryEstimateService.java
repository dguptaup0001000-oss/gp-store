package com.gpstore.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DeliveryEstimateService {

    private static final int MIN_MINUTES = 10;
    private static final int MAX_MINUTES = 120;

    // Assumed effective delivery speed accounting for traffic/stops, not raw travel speed.
    private static final double MINUTES_PER_KM = 5.0;

    private final double storeLatitude;
    private final double storeLongitude;
    private final double maxDeliveryRadiusKm;

    public DeliveryEstimateService(
            @Value("${store.latitude}") double storeLatitude,
            @Value("${store.longitude}") double storeLongitude,
            @Value("${store.max-delivery-radius-km}") double maxDeliveryRadiusKm) {
        this.storeLatitude = storeLatitude;
        this.storeLongitude = storeLongitude;
        this.maxDeliveryRadiusKm = maxDeliveryRadiusKm;
    }

    public double getMaxDeliveryRadiusKm() {
        return maxDeliveryRadiusKm;
    }

    /**
     * Business rule: one kirana store can't realistically serve an entire city
     * the way a dark-store network can. Returns false (out of range) if the
     * address has no coordinates at all - we fail closed rather than silently
     * accepting an order we can't verify is deliverable.
     */
    public boolean isWithinServiceableRadius(Double destLat, Double destLng) {
        double distanceKm = distanceFromStoreKm(destLat, destLng);
        return !Double.isNaN(distanceKm) && distanceKm <= maxDeliveryRadiusKm;
    }

    /** Haversine great-circle distance in km between the store and a given point. */
    public double distanceFromStoreKm(Double destLat, Double destLng) {
        if (destLat == null || destLng == null) {
            // No coordinates on the address yet - fall back to the max window
            // rather than pretending we know the distance.
            return Double.NaN;
        }

        double earthRadiusKm = 6371.0;

        double latDistance = Math.toRadians(destLat - storeLatitude);
        double lngDistance = Math.toRadians(destLng - storeLongitude);

        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(storeLatitude)) * Math.cos(Math.toRadians(destLat))
                * Math.sin(lngDistance / 2) * Math.sin(lngDistance / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return earthRadiusKm * c;
    }

    /**
     * Business rule: delivery estimate is distance-based, never faster than
     * 10 minutes and never promised beyond 2 hours - this is intentionally NOT
     * a "10-minute delivery" model.
     */
    public int estimateMinutes(Double destLat, Double destLng) {
        double distanceKm = distanceFromStoreKm(destLat, destLng);

        if (Double.isNaN(distanceKm)) {
            return MAX_MINUTES; // unknown location -> safest (widest) promise
        }

        int minutes = (int) Math.round(MIN_MINUTES + distanceKm * MINUTES_PER_KM);
        return Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, minutes));
    }
}
