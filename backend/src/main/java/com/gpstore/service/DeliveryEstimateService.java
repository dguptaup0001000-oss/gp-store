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
    private final com.gpstore.platform.ShopRepository shops;

    public DeliveryEstimateService(
            @Value("${store.latitude}") double storeLatitude,
            @Value("${store.longitude}") double storeLongitude,
            @Value("${store.max-delivery-radius-km}") double maxDeliveryRadiusKm,
            com.gpstore.platform.ShopRepository shops) {
        this.storeLatitude = storeLatitude;
        this.storeLongitude = storeLongitude;
        this.maxDeliveryRadiusKm = maxDeliveryRadiusKm;
        this.shops = shops;
    }

    /**
     * Where "the store" is for the request in hand.
     *
     * THE SHOP IN SCOPE, FALLING BACK TO THE CONFIGURED ONE. Distance and
     * radius decide what a customer is charged to deliver and whether they can
     * order at all, so under a marketplace they have to be measured from the
     * shop that is actually packing the order - not from whichever shop the
     * deployment was originally configured around.
     *
     * IDENTICAL UNDER ONE SHOP. ShopBootstrap seeds Shop #1's coordinates and
     * radius from the same store.* properties this class falls back to, so the
     * numbers are the same ones and nothing about today's pricing moves.
     */
    private record Origin(double latitude, double longitude, double radiusKm) {}

    private Origin origin() {
        com.gpstore.platform.TenantScope scope = com.gpstore.platform.TenantContext.current();
        if (scope != null && scope.isSingleShop()) {
            java.util.Optional<com.gpstore.platform.Shop> shop = shops.findById(scope.requireShopId());
            if (shop.isPresent() && shop.get().getLatitude() != null
                    && shop.get().getLongitude() != null) {
                java.math.BigDecimal radius = shop.get().getMaxDeliveryRadiusKm();
                return new Origin(shop.get().getLatitude(), shop.get().getLongitude(),
                        radius == null ? maxDeliveryRadiusKm : radius.doubleValue());
            }
        }
        return new Origin(storeLatitude, storeLongitude, maxDeliveryRadiusKm);
    }

    /** The radius that applies to the shop in scope. */
    public double getMaxDeliveryRadiusKm() {
        return origin().radiusKm();
    }

    /**
     * Business rule: one kirana store can't realistically serve an entire city
     * the way a dark-store network can. Returns false (out of range) if the
     * address has no coordinates at all - we fail closed rather than silently
     * accepting an order we can't verify is deliverable.
     */
    public boolean isWithinServiceableRadius(Double destLat, Double destLng) {
        Origin from = origin();
        double distanceKm = distanceFrom(from, destLat, destLng);
        return !Double.isNaN(distanceKm) && distanceKm <= from.radiusKm();
    }

    /**
     * Haversine great-circle distance in km from the shop in scope to a point.
     *
     * The name is kept because forty call sites read it and "the store" is
     * still what it means - it is just that which store depends on whose order
     * this is now. See origin().
     */
    public double distanceFromStoreKm(Double destLat, Double destLng) {
        return distanceFrom(origin(), destLat, destLng);
    }

    private static double distanceFrom(Origin from, Double destLat, Double destLng) {
        if (destLat == null || destLng == null) {
            // No coordinates on the address yet - fall back to the max window
            // rather than pretending we know the distance.
            return Double.NaN;
        }
        return com.gpstore.platform.ShopDiscovery.distanceKm(
                from.latitude(), from.longitude(), destLat, destLng);
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
