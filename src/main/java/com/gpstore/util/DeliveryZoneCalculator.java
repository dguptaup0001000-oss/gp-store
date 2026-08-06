package com.gpstore.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Splits the delivery area into 8 directional zones (45 degrees each) radiating
 * from the shop, plus distance rings. Batching groups orders by zone+ring so a
 * partner's 20 parcels all lie along one outbound corridor.
 */
@Component
public class DeliveryZoneCalculator {

    @Value("${store.latitude:28.6139}")
    private double storeLat;

    @Value("${store.longitude:77.2090}")
    private double storeLon;

    private static final double EARTH_RADIUS_KM = 6371.0;

    /** Compass bearing from shop to point, 0-360 degrees (0 = north). */
    public double bearing(double lat, double lon) {
        double lat1 = Math.toRadians(storeLat);
        double lat2 = Math.toRadians(lat);
        double dLon = Math.toRadians(lon - storeLon);

        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2)
                 - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);

        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    /** Great-circle distance from shop in km. */
    public double distanceKm(double lat, double lon) {
        double dLat = Math.toRadians(lat - storeLat);
        double dLon = Math.toRadians(lon - storeLon);
        double lat1 = Math.toRadians(storeLat);
        double lat2 = Math.toRadians(lat);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.sin(dLon / 2) * Math.sin(dLon / 2) * Math.cos(lat1) * Math.cos(lat2);

        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Zone 1-8. Zone 1 is centred on north, each spans 45 degrees clockwise. */
    public int zone(double lat, double lon) {
        return (int) Math.floor(bearing(lat, lon) / 45.0) + 1;
    }

    /** Ring A (0-2km), B (2-5km), C (5-8km), X (outside delivery radius). */
    public String ring(double lat, double lon) {
        double d = distanceKm(lat, lon);
        if (d <= 2.0) return "A";
        if (d <= 5.0) return "B";
        if (d <= 8.0) return "C";
        return "X";
    }

    /** Combined subzone key used for batching, e.g. "3B". */
    public String subzone(double lat, double lon) {
        return zone(lat, lon) + ring(lat, lon);
    }

    public boolean isDeliverable(double lat, double lon) {
        return distanceKm(lat, lon) <= 8.0;
    }
}
