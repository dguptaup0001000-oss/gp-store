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
    private final SearchRadiusLadder ladder;

    public ShopDiscovery(ShopRepository shops, SearchRadiusLadder ladder) {
        this.shops = shops;
        this.ladder = ladder;
    }

    /** The configured ladder, for callers that need to label a button. */
    public SearchRadiusLadder ladder() {
        return ladder;
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
     * by the ladder's top rung here rather than wherever it was typed. It
     * narrows what is returned and can never widen a shop's promise, which is
     * the shape §78 asks for.
     */
    @Transactional(readOnly = true)
    public List<NearbyShop> shopsWithin(Double latitude, Double longitude, BigDecimal radiusKm) {
        if (latitude == null || longitude == null || radiusKm == null) {
            return List.of();
        }
        double limit = Math.min(radiusKm.doubleValue(), ladder.max().doubleValue());
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

    /** Delegates, so the ladder cannot be described two different ways. */
    public Optional<BigDecimal> nextSearchRadius(BigDecimal radiusKm) {
        return ladder.next(radiusKm);
    }

    /**
     * What a progressive search actually found, and how far it had to go (§6).
     *
     * @param askedKm    the rung the customer asked for, or null for
     *                   "whoever will deliver to me"
     * @param searchedKm the rung that produced {@code shops} - larger than
     *                   askedKm when the search had to widen
     * @param nextKm     the rung above, or null at the top of the ladder
     * @param widened    whether the customer is being shown a wider circle
     *                   than they asked for
     */
    public record RadiusSearch(BigDecimal askedKm, BigDecimal searchedKm,
                               BigDecimal nextKm, BigDecimal maxKm,
                               boolean widened, List<NearbyShop> shops) {

        /**
         * §6's sentence, built once here rather than in each client.
         *
         * <p>WHY THE SERVER WRITES IT. "No shops within 8 km. Showing shops
         * within 20 km." is a statement about what the server did, and a
         * client reconstructing it from two numbers will eventually
         * reconstruct it wrongly - most likely by saying "no shops nearby"
         * when there are twelve, three rungs out.
         */
        public String message() {
            if (!widened) {
                return null;
            }
            return "No shops within " + plain(askedKm) + " km. Showing shops within "
                    + plain(searchedKm) + " km.";
        }

        private static String plain(BigDecimal km) {
            return km == null ? "?" : km.stripTrailingZeros().toPlainString();
        }
    }

    /**
     * LOCAL-FIRST, BUT NOT LOCAL-ONLY (§6): starts at the rung asked for and
     * climbs until something is there.
     *
     * <p>WHY THE SERVER CLIMBS RATHER THAN THE APP. An app that widens for
     * itself needs the ladder, needs to know when to stop, and needs to get
     * the "no shops within 8 km" sentence right - three chances for two
     * clients to behave differently. It also turns one empty screen into four
     * round trips before the customer sees anything.
     *
     * <p>IT STOPS AT THE FIRST RUNG THAT HAS ANYTHING. Climbing to the top
     * regardless would answer "the nearest hardware shop is 400 km away" to a
     * customer who had a perfectly good one at 12 km, because the top rung's
     * list is longer.
     *
     * @param fromKm where to start, or null to start at the bottom
     */
    @Transactional(readOnly = true)
    public RadiusSearch searchOutwards(Double latitude, Double longitude, BigDecimal fromKm) {
        return searchOutwards(latitude, longitude, fromKm, rung -> rung);
    }

    /**
     * The same ladder, climbed until a rung has something the caller wants.
     *
     * <p>WHY THE NARROWING HAPPENS INSIDE THE LOOP rather than to the result.
     * Filtering afterwards asks "are there shops within 8 km?" and then throws
     * away the ones that do not sell medicine - so a customer looking for a
     * chemist is told "no shops found" while the ladder sits on a rung full of
     * kiranas, and "search farther" cannot help because the search already
     * succeeded. Narrowing each rung as it is drawn means the ladder keeps
     * climbing until it finds a rung with a chemist on it, which is what §6
     * promises and what the customer asked for.
     *
     * <p>ONE CALL PER RUNG, not one per shop: the operator is handed the whole
     * rung so a caller that needs a database round trip to decide can make one
     * of them instead of twenty.
     *
     * @param narrow keeps the shops this caller cares about, in the order it
     *               was given them - the distance ordering is the ladder's and
     *               must not be rewritten here (§4: the ranking is the
     *               marketplace's, not the screen's)
     */
    @Transactional(readOnly = true)
    public RadiusSearch searchOutwards(Double latitude, Double longitude, BigDecimal fromKm,
                                       java.util.function.UnaryOperator<List<NearbyShop>> narrow) {
        BigDecimal asked = fromKm == null ? ladder.first() : ladder.clamp(fromKm);
        if (latitude == null || longitude == null) {
            return new RadiusSearch(asked, asked, ladder.next(asked).orElse(null),
                    ladder.max(), false, List.of());
        }

        BigDecimal at = asked;
        while (true) {
            List<NearbyShop> found = narrow.apply(shopsWithin(latitude, longitude, at));
            boolean widened = at.compareTo(asked) > 0;
            if (!found.isEmpty()) {
                return new RadiusSearch(asked, at, ladder.next(at).orElse(null),
                        ladder.max(), widened, found);
            }
            Optional<BigDecimal> next = ladder.next(at);
            if (next.isEmpty()) {
                // The top of the ladder with nothing on it. Said plainly,
                // with the widest circle actually searched, rather than as
                // "no shops near you" - which would be true of the first rung
                // and misleading about the rest.
                return new RadiusSearch(asked, at, null, ladder.max(),
                        at.compareTo(asked) > 0, List.of());
            }
            at = next.get();
        }
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
