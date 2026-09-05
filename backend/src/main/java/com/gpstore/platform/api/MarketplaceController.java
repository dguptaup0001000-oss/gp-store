package com.gpstore.platform.api;

import com.gpstore.platform.PlatformProperties;
import com.gpstore.platform.Shop;
import com.gpstore.platform.ShopDiscovery;
import com.gpstore.platform.ShopRepository;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
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

    public MarketplaceController(ShopDiscovery discovery, ShopRepository shops,
                                 PlatformProperties platform) {
        this.discovery = discovery;
        this.shops = shops;
        this.platform = platform;
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
                                 String supportPhone, String timeZone) {}

    /**
     * Shops that will deliver to a point, nearest first.
     *
     * The radius is each shop's own, so a kirana that goes 2 km and one that
     * goes 8 km are both answered correctly - see ShopDiscovery. Coordinates
     * with no shop in range come back as an empty list, not an error.
     */
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
                .map(nearby -> view(nearby.shop(), nearby.distanceKm()))
                .toList();
    }

    /**
     * One storefront, by id.
     *
     * Only if the marketplace shows it to customers - a draft, suspended or
     * closed shop answers 404, the same as one that does not exist. Whether a
     * particular shop is suspended is between the platform and that merchant.
     */
    @GetMapping("/shops/{shopId}")
    public StorefrontView storefront(@PathVariable Long shopId) {
        if (!discovery.isBrowsableByCustomers(shopId)) {
            throw new com.gpstore.exception.ResourceNotFoundException("Shop not found");
        }
        return view(shops.findById(shopId).orElseThrow(
                () -> new com.gpstore.exception.ResourceNotFoundException("Shop not found")), null);
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

    private static StorefrontView view(Shop shop, Double distanceKm) {
        return new StorefrontView(shop.getId(), shop.getCode(), shop.getDisplayName(),
                shop.getLatitude(), shop.getLongitude(), shop.getMaxDeliveryRadiusKm(),
                distanceKm, shop.getSupportPhone(), shop.getTimeZone());
    }
}
