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

    /**
     * A shop, how far the customer is from it, and whether it will come.
     *
     * THE TWO ARE NOT THE SAME QUESTION, which is the whole reason for the
     * second field. A shop 4 km away that delivers 2 km is nearby and will not
     * come; a shop 6 km away that delivers 8 km is farther and will. Collapsing
     * them is how a customer is shown a storefront that refuses them at
     * checkout, or is hidden one that would have delivered.
     */
    public record NearbyShop(Shop shop, double distanceKm, boolean deliversHere) {}

    /**
     * How far "search farther" looks, one tap at a time.
     *
     * A LADDER, DECIDED HERE, and deliberately not in the app. The app asking
     * for an arbitrary radius would make every client's idea of "farther"
     * different, and a client that asked for 500 km would turn a local
     * marketplace into a national one by accident. The rungs are also the
     * honest shape of the question: in a town, 3 km is a walk, 25 km is
     * another town, and there is nothing useful in between 25 and infinity.
     */
    public static final List<BigDecimal> SEARCH_RADII_KM = List.of(
            new BigDecimal("3"), new BigDecimal("5"), new BigDecimal("10"),
            new BigDecimal("15"), new BigDecimal("25"));

    /** The widest a customer may look. Beyond this, "local" has stopped meaning anything. */
    public static final BigDecimal MAX_SEARCH_RADIUS_KM = SEARCH_RADII_KM.get(SEARCH_RADII_KM.size() - 1);

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
            if (deliversTo(shop, distance)) {
                serving.add(new NearbyShop(shop, distance, true));
            }
        }
        serving.sort(Comparator.comparingDouble(NearbyShop::distanceKm));
        return List.copyOf(serving);
    }

    /**
     * SEARCH FARTHER: every visible shop within [radiusKm] of the customer,
     * whether or not it delivers to them, closest first.
     *
     * WHY THIS IS A SEPARATE METHOD AND NOT A WIDER shopsServing. The default
     * is local-first and stays local-first: a customer sees the shops that
     * will actually come to them, because that is the list they can order
     * from. This is the answer to "there is nothing near me" - it widens what
     * the customer can SEE, and it does not widen what any shop has promised.
     * Each result says which it is, and deliversHere is still each shop's own
     * radius, never the search radius.
     *
     * A SHOP THAT WILL NOT DELIVER IS STILL WORTH SHOWING. A kirana two
     * streets outside its own circle is a real shop the customer can ring, ask
     * for, or wait for - and knowing it exists is the difference between an
     * empty screen and a marketplace. What the app must not do is let them
     * fill a basket at one; that is checkout's answer, and it is unchanged.
     *
     * CLAMPED, not trusted. A radius arrives from a client, so it is bounded
     * by MAX_SEARCH_RADIUS_KM here rather than wherever it was typed. It
     * narrows what is returned and can never widen a shop's promise, which is
     * the shape §78 asks for.
     */
    @Transactional(readOnly = true)
    public List<NearbyShop> shopsWithin(Double latitude, Double longitude, BigDecimal radiusKm) {
        if (latitude == null || longitude == null || radiusKm == null) {
            return List.of();
        }
        double limit = Math.min(radiusKm.doubleValue(), MAX_SEARCH_RADIUS_KM.doubleValue());
        if (limit <= 0) {
            return List.of();
        }
        List<NearbyShop> nearby = new ArrayList<>();
        for (Shop shop : shops.findAll()) {
            if (!isOpenToCustomers(shop) || shop.getLatitude() == null || shop.getLongitude() == null) {
                continue;
            }
            double distance = distanceKm(latitude, longitude, shop.getLatitude(), shop.getLongitude());
            if (distance <= limit) {
                nearby.add(new NearbyShop(shop, distance, deliversTo(shop, distance)));
            }
        }
        nearby.sort(Comparator.comparingDouble(NearbyShop::distanceKm));
        return List.copyOf(nearby);
    }

    /**
     * The next rung of the ladder above [radiusKm], or empty at the top.
     *
     * Null means "nothing has been searched yet", whose next step is the first
     * rung - so the app can label its button without knowing the ladder.
     */
    public Optional<BigDecimal> nextSearchRadius(BigDecimal radiusKm) {
        for (BigDecimal rung : SEARCH_RADII_KM) {
            if (radiusKm == null || rung.compareTo(radiusKm) > 0) {
                return Optional.of(rung);
            }
        }
        return Optional.empty();
    }

    /** This shop's own promise, which the search radius never overrides. */
    private static boolean deliversTo(Shop shop, double distanceKm) {
        BigDecimal radius = shop.getMaxDeliveryRadiusKm();
        return radius != null && distanceKm <= radius.doubleValue();
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
