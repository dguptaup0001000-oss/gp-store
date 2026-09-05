package com.gpstore.platform;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Which shops will deliver to a customer, nearest first.
 *
 * THE RADIUS BELONGS TO THE SHOP, NOT TO THE PLATFORM. Each storefront
 * declares how far it is willing to go (Shop.maxDeliveryRadiusKm, seeded for
 * Shop #1 from the STORE_* configuration it has always run on), and a shop is
 * offered to a customer only when they are inside that shop's own circle. A
 * kirana that delivers 2 km and one that delivers 8 km are both correct, and
 * neither is the platform's business to override.
 *
 * NEAREST FIRST, which is the progressive part. A customer's default is the
 * closest shop that will serve them; the rest are offered in order of
 * distance, so "local" means local rather than "whoever registered first".
 *
 * A CUSTOMER NEEDS NO STAFF MEMBERSHIP TO BE HERE. Browsing a storefront is
 * not an authorization - the shops returned are the ones any customer may see
 * (ShopStatus.isVisibleToCustomers). What a customer may not do is act as a
 * shop, and that is a different question answered by ShopMembership.
 */
@Service
public class ShopDiscovery {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private final ShopRepository shops;

    public ShopDiscovery(ShopRepository shops) {
        this.shops = shops;
    }

    /** A shop, and how far the customer is from it. */
    public record NearbyShop(Shop shop, double distanceKm) {}

    /**
     * Every visible shop whose own delivery radius covers this point,
     * closest first.
     *
     * FAILS CLOSED ON A MISSING PIN. An address with no coordinates cannot be
     * shown to be inside anybody's radius, so it matches no shop rather than
     * every shop - the same rule DeliveryEstimateService has always applied to
     * the single shop.
     */
    @Transactional(readOnly = true)
    public List<NearbyShop> shopsServing(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            return List.of();
        }
        List<NearbyShop> serving = new ArrayList<>();
        for (Shop shop : shops.findAll()) {
            if (!isOpenToCustomers(shop) || shop.getLatitude() == null || shop.getLongitude() == null) {
                continue;
            }
            double distance = distanceKm(latitude, longitude, shop.getLatitude(), shop.getLongitude());
            BigDecimal radius = shop.getMaxDeliveryRadiusKm();
            if (radius != null && distance <= radius.doubleValue()) {
                serving.add(new NearbyShop(shop, distance));
            }
        }
        serving.sort(Comparator.comparingDouble(NearbyShop::distanceKm));
        return List.copyOf(serving);
    }

    /** The closest shop that will deliver here, if any. */
    @Transactional(readOnly = true)
    public Optional<Shop> nearestServing(Double latitude, Double longitude) {
        return shopsServing(latitude, longitude).stream().findFirst().map(NearbyShop::shop);
    }

    /**
     * Shops a customer may browse at all.
     *
     * Distinct from {@link #shopsServing}: a customer with no address yet can
     * still look at what is on the marketplace. Whether a given shop will
     * deliver to them is answered at checkout, where there is an address to
     * answer it with.
     */
    @Transactional(readOnly = true)
    public boolean isBrowsableByCustomers(Long shopId) {
        return shopId != null && shops.findById(shopId)
                .map(ShopDiscovery::isOpenToCustomers)
                .orElse(false);
    }

    private static boolean isOpenToCustomers(Shop shop) {
        return shop != null
                && shop.getStatus() != null
                && shop.getStatus().isVisibleToCustomers()
                && Boolean.TRUE.equals(shop.getActive());
    }

    /** Haversine, the same formula DeliveryEstimateService has always used. */
    public static double distanceKm(double fromLat, double fromLng, double toLat, double toLng) {
        double latDistance = Math.toRadians(toLat - fromLat);
        double lngDistance = Math.toRadians(toLng - fromLng);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(fromLat)) * Math.cos(Math.toRadians(toLat))
                * Math.sin(lngDistance / 2) * Math.sin(lngDistance / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
