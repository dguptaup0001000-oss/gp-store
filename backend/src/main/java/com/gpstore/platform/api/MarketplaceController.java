package com.gpstore.platform.api;

import com.gpstore.platform.PlatformProperties;
import com.gpstore.platform.Shop;
import com.gpstore.platform.ShopDiscovery;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.ShopScopeSwitch;
import com.gpstore.store.DeliveryScheduleService;
import com.gpstore.store.StoreStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What a customer's app asks BEFORE it has a shop: which shops are there.
 *
 * THE CHICKEN AND EGG THIS SOLVES. Every other endpoint runs inside a shop
 * scope, resolved from the credential. A customer who has just installed the
 * app, or who has moved, has no shop yet - and asking them to have one before
 * they can find out which shops exist is a 403 on the first screen. So these
 * routes run platform-wide (TenantContextFilter) and return only what a
 * customer may see anyway: a storefront's name, where it is, and how far it
 * delivers.
 *
 * AN EMPTY LIST IS A REAL ANSWER, and it is why this is a list rather than a
 * resolution. "No shop delivers to you yet" is a screen the app can draw; a
 * 403 is not, and dressing that up as an authorization failure would be
 * telling a customer they are not allowed to live where they live.
 *
 * NOTHING SHOP-OWNED IS REACHABLE FROM HERE. No prices, no stock, no orders -
 * those all need a shop scope, which the customer gets by opening one of
 * these storefronts. Standing in the street looking at signs is not the same
 * as being inside a shop.
 */
@RestController
@RequestMapping("/api/marketplace")
public class MarketplaceController {

    private final ShopDiscovery discovery;
    private final ShopRepository shops;
    private final PlatformProperties platform;
    private final ShopScopeSwitch shopScope;
    private final DeliveryScheduleService schedule;

    public MarketplaceController(ShopDiscovery discovery, ShopRepository shops,
                                 PlatformProperties platform, ShopScopeSwitch shopScope,
                                 DeliveryScheduleService schedule) {
        this.discovery = discovery;
        this.shops = shops;
        this.platform = platform;
        this.shopScope = shopScope;
        this.schedule = schedule;
    }

    /**
     * A storefront as a customer sees it.
     *
     * DELIBERATELY THIN. The merchant's legal name, their contact details and
     * their status reason are the platform's business and the shopkeeper's,
     * not a browsing customer's - a suspended shop is simply absent rather
     * than present with an explanation.
     */
    public record StorefrontView(Long shopId, String code, String displayName,
                                 Double latitude, Double longitude,
                                 BigDecimal maxDeliveryRadiusKm,
                                 Double distanceKm,
                                 Boolean deliversHere,
                                 boolean openNow,
                                 boolean acceptingOrders,
                                 boolean closedToday,
                                 String closureReason,
                                 java.time.LocalDateTime pausedUntil,
                                 LocalDate nextDeliveryDate,
                                 String supportPhone, String timeZone) {}

    /**
     * A page of discovery: what was searched, what came back, and how much
     * farther there is to look.
     *
     * THE LADDER IS THE SERVER'S. The app should not be inventing "try 10 km
     * next" in Dart - two clients would then disagree about what farther
     * means, and neither would be the marketplace's answer. nextRadiusKm is
     * null when there is nowhere farther to go, which is how the button knows
     * to stop offering.
     */
    public record DiscoveryView(BigDecimal radiusKm,
                                BigDecimal nextRadiusKm,
                                BigDecimal maxRadiusKm,
                                List<StorefrontView> shops) {}

    /**
     * Shops that will deliver to a point, nearest first.
     *
     * The radius is each shop's own, so a kirana that goes 2 km and one that
     * goes 8 km are both answered correctly - see ShopDiscovery. Coordinates
     * with no shop in range come back as an empty list, not an error.
     */
    // READ-ONLY TRANSACTIONAL, and it has to be. Each storefront's open/closed
    // answer is read inside that shop's scope (ShopScopeSwitch), which re-points
    // the Hibernate session's filter - and there is no session to re-point
    // outside a transaction, because open-in-view is off. One transaction per
    // request also means the whole list is one connection rather than one per
    // shop.
    @Transactional(readOnly = true)
    @GetMapping("/shops")
    public List<StorefrontView> shopsNear(@RequestParam(required = false) Double lat,
                                          @RequestParam(required = false) Double lng) {
        if (lat == null || lng == null) {
            // No pin, so nothing can be shown to be in range. Fails closed
            // exactly as the delivery estimate always has: an address that
            // cannot be proved deliverable is not deliverable.
            return List.of();
        }
        return discovery.shopsServing(lat, lng).stream()
                .map(this::view)
                .toList();
    }

    /**
     * The same question with a "search farther" answer attached.
     *
     * LOCAL FIRST, STILL. With no radiusKm this is exactly {@link #shopsNear}:
     * the shops that will actually deliver to this pin, which is the list a
     * customer can order from. radiusKm is the second tap - "there is nothing
     * near me" - and it widens what the customer can SEE without widening what
     * any shop has promised: each storefront still carries deliversHere, still
     * from that shop's own radius.
     *
     * WHY IT IS ITS OWN ROUTE rather than a parameter on /shops. /shops
     * answers with a bare list and an app in the field is already parsing it;
     * the ladder needs an envelope around the list, and quietly changing the
     * shape of a live response to add one is how a released app starts showing
     * an empty marketplace.
     */
    @Transactional(readOnly = true)
    @GetMapping("/discovery")
    public DiscoveryView discover(@RequestParam(required = false) Double lat,
                                  @RequestParam(required = false) Double lng,
                                  @RequestParam(required = false) BigDecimal radiusKm) {
        if (lat == null || lng == null) {
            return new DiscoveryView(null, discovery.nextSearchRadius(null).orElse(null),
                    ShopDiscovery.MAX_SEARCH_RADIUS_KM, List.of());
        }
        // Clamped by the service, not here, so the bound holds for every
        // caller rather than for this one route (§78: a client value may
        // narrow, never widen).
        BigDecimal searched = radiusKm == null ? null
                : radiusKm.min(ShopDiscovery.MAX_SEARCH_RADIUS_KM);

        List<ShopDiscovery.NearbyShop> found = searched == null
                ? discovery.shopsServing(lat, lng)
                : discovery.shopsWithin(lat, lng, searched);

        return new DiscoveryView(
                searched,
                discovery.nextSearchRadius(searched).orElse(null),
                ShopDiscovery.MAX_SEARCH_RADIUS_KM,
                found.stream().map(this::view).toList());
    }

    /**
     * One storefront, by id.
     *
     * Only if the marketplace shows it to customers - a draft, suspended or
     * closed shop answers 404, the same as one that does not exist. Whether a
     * particular shop is suspended is between the platform and that merchant.
     */
    @Transactional(readOnly = true)
    @GetMapping("/shops/{shopId}")
    public StorefrontView storefront(@PathVariable Long shopId) {
        if (!discovery.isBrowsableByCustomers(shopId)) {
            throw new com.gpstore.exception.ResourceNotFoundException("Shop not found");
        }
        return view(shops.findById(shopId).orElseThrow(
                () -> new com.gpstore.exception.ResourceNotFoundException("Shop not found")),
                null, null);
    }

    /**
     * Whether this deployment is a marketplace at all.
     *
     * The app needs to know whether to draw a shop switcher or a single
     * shop's home screen, and it must not infer that from the number of shops
     * it happens to get back - one shop in range is not the same as one shop
     * existing.
     */
    @GetMapping("/mode")
    public java.util.Map<String, Object> mode() {
        return java.util.Map.of(
                "mode", platform.getMode().name(),
                "multiShop", platform.getMode().isMultiShop());
    }

    private StorefrontView view(ShopDiscovery.NearbyShop nearby) {
        return view(nearby.shop(), nearby.distanceKm(), nearby.deliversHere());
    }

    /**
     * WHETHER THE SHOP IS OPEN COMES FROM THE SHOP'S OWN HOURS, not from a
     * second flag kept beside them.
     *
     * The answer already exists and is already per shop: the owner's
     * acceptance override and the days they have declared closed, read through
     * DeliveryScheduleService, which is what checkout itself asks before it
     * will take an order. Adding an "isOpen" column here would have been a
     * copy of that answer, free to drift from it, and the drift would show up
     * as a storefront that says OPEN and refuses the basket.
     *
     * BROWSING IS NEVER CLOSED (StoreStatus.browsingOpen). A shut kirana is
     * still worth looking at - what changes is when the order arrives, which
     * is what nextDeliveryDate says.
     *
     * pausedUntil is the difference between "back at four" and "closed": a
     * shopkeeper who stepped out for half an hour has told the customer
     * something worth drawing, and a shop that simply is not taking orders
     * has not.
     *
     * ONE SETTINGS READ AND ONE CLOSURES QUERY PER SHOP IN THE LIST. That is
     * affordable because a discovery list is a handful of shops in one town,
     * not a catalogue; if it ever stops being, this is the line to batch.
     */
    private StorefrontView view(Shop shop, Double distanceKm, Boolean deliversHere) {
        StoreStatus status = shopScope.within(shop.getId(), schedule::getStoreStatus);
        return new StorefrontView(shop.getId(), shop.getCode(), shop.getDisplayName(),
                shop.getLatitude(), shop.getLongitude(), shop.getMaxDeliveryRadiusKm(),
                distanceKm, deliversHere,
                status.browsingOpen(), status.acceptingOrders(),
                status.closedToday(), status.closureReason(), status.pausedUntil(),
                status.deliveryDate(),
                shop.getSupportPhone(), shop.getTimeZone());
    }
}
