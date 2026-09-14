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
    private final com.gpstore.platform.ShopReliability reliability;
    private final com.gpstore.platform.shopinfo.ShopPolicyRepository policies;
    private final com.gpstore.rating.ShopRatingService ratings;
    private final com.gpstore.discovery.ShopCategoryPresence shelves;
    private final com.gpstore.discovery.PublicShopStars stars;
    private final com.gpstore.repository.CategoryRepository categories;

    public MarketplaceController(ShopDiscovery discovery, ShopRepository shops,
                                 PlatformProperties platform, ShopScopeSwitch shopScope,
                                 DeliveryScheduleService schedule,
                                 com.gpstore.platform.ShopReliability reliability,
                                 com.gpstore.platform.shopinfo.ShopPolicyRepository policies,
                                 com.gpstore.rating.ShopRatingService ratings,
                                 com.gpstore.discovery.ShopCategoryPresence shelves,
                                 com.gpstore.discovery.PublicShopStars stars,
                                 com.gpstore.repository.CategoryRepository categories) {
        this.shelves = shelves;
        this.stars = stars;
        this.categories = categories;
        this.ratings = ratings;
        this.reliability = reliability;
        this.policies = policies;
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
                                 String logoUrl,
                                 // WHAT GP-STORE HAS CHECKED, and nothing more.
                                 // Both come off the shops row, so they cost
                                 // the discovery list nothing; TRUSTED does
                                 // not, and is on the detail below for that
                                 // reason.
                                 com.gpstore.platform.ShopVerificationLevel verificationLevel,
                                 String verificationBadge,
                                 BigDecimal maxDeliveryRadiusKm,
                                 Double distanceKm,
                                 Boolean deliversHere,
                                 boolean openNow,
                                 boolean acceptingOrders,
                                 boolean closedToday,
                                 String closureReason,
                                 java.time.LocalDateTime pausedUntil,
                                 LocalDate nextDeliveryDate,
                                 String supportPhone, String timeZone,
                                 // WHAT CUSTOMERS SAID, on the list rather
                                 // than only on the page. Choosing between
                                 // four kiranas without their ratings is
                                 // choosing on distance alone. Batched in one
                                 // query for the whole list (PublicShopStars),
                                 // so this costs the list less than the
                                 // settings read already above it.
                                 //
                                 // ZERO COUNT MEANS UNRATED, NOT NOUGHT STARS,
                                 // and every screen must draw it that way.
                                 Double ratingAverage,
                                 int ratingCount) {}

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
                                List<StorefrontView> shops,
                                // §6: WHAT THE SERVER ACTUALLY DID. When the
                                // rung the customer asked for was empty, the
                                // search widened - and saying so is the
                                // difference between "no shops near you" and
                                // "no shops within 8 km, showing 20 km".
                                // Null when nothing widened.
                                BigDecimal askedRadiusKm,
                                boolean widened,
                                String message) {

        /** The shape this route answered with before the ladder could widen. */
        static DiscoveryView of(BigDecimal radiusKm, BigDecimal nextKm, BigDecimal maxKm,
                                List<StorefrontView> shops) {
            return new DiscoveryView(radiusKm, nextKm, maxKm, shops, radiusKm, false, null);
        }
    }

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
        return viewAll(discovery.shopsServing(lat, lng));
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
                                  @RequestParam(required = false) BigDecimal radiusKm,
                                  @RequestParam(required = false) Long categoryId) {
        if (lat == null || lng == null) {
            return DiscoveryView.of(null, discovery.nextSearchRadius(null).orElse(null),
                    discovery.ladder().max(), List.of());
        }

        // NO RADIUS IS STILL LOCAL-FIRST AND UNCHANGED: the shops that will
        // actually deliver to this pin, which is the list a customer can order
        // from. A released app parsing this route must keep getting that.
        if (radiusKm == null) {
            return DiscoveryView.of(null, discovery.nextSearchRadius(null).orElse(null),
                    discovery.ladder().max(),
                    viewAll(sellersOf(categoryId, discovery.shopsServing(lat, lng))));
        }

        // A RADIUS MEANS "SEARCH FARTHER", and §6 says farther keeps going
        // until it finds something rather than answering an empty screen at
        // each rung in turn. Clamping happens in the ladder, not here, so the
        // bound holds for every caller (§78: a client value may narrow it,
        // never widen it).
        //
        // THE CATEGORY NARROWS EACH RUNG AS THE LADDER DRAWS IT, which is why
        // it is handed in rather than applied to the answer. Filtering the
        // result would tell a customer looking for a chemist "no shops found"
        // while the ladder sat on a rung full of kiranas, and "search farther"
        // could not help them because the search had already succeeded.
        ShopDiscovery.RadiusSearch search =
                discovery.searchOutwards(lat, lng, radiusKm, rung -> sellersOf(categoryId, rung));
        return new DiscoveryView(
                search.searchedKm(), search.nextKm(), search.maxKm(),
                viewAll(search.shops()),
                search.askedKm(), search.widened(), search.message());
    }

    /**
     * The shops in this list that actually sell the category, in the order
     * they arrived.
     *
     * ORDER IS NOT TOUCHED. The ranking is the marketplace's - nearest first,
     * off the ladder - and re-sorting here by how many listings a shop has in
     * the category would be a second ranking algorithm competing with the one
     * the rest of the app uses (§4: reuse the existing ranking, do not invent
     * one).
     *
     * A NULL CATEGORY IS NOT A FILTER, so the plain discovery call does not
     * pay for a query it does not need.
     */
    private List<ShopDiscovery.NearbyShop> sellersOf(Long categoryId,
                                                     List<ShopDiscovery.NearbyShop> found) {
        if (categoryId == null || found.isEmpty()) {
            return found;
        }
        java.util.Map<Long, java.util.Set<Long>> shelves = this.shelves.categoriesOnTheShelvesOf(
                found.stream().map(near -> near.shop().getId()).toList());
        return found.stream()
                .filter(near -> shelves.getOrDefault(near.shop().getId(), java.util.Set.of())
                        .contains(categoryId))
                .toList();
    }

    /**
     * The categories a customer at this pin can actually buy from, and how
     * many shops near them sell each.
     *
     * WHY NOT JUST /api/categories. That route is the CATALOGUE - every
     * category the platform has ever defined, which is the right answer for a
     * Super Admin and the wrong one for a customer in a town with four
     * kiranas and a chemist. Drawing the full catalogue on their home screen
     * offers twenty doors, eighteen of which open onto "no shops found". This
     * route answers the customer's question instead: what can I buy here.
     *
     * NO RADIUS, SO THIS IS THE LOCAL LIST. The deliberate asymmetry with
     * /discovery is that a customer is shown the categories somebody will
     * actually deliver to them; searching farther is a decision they make
     * inside a category, not before choosing one.
     *
     * EMPTY IS AN ANSWER. A pin with no shop serving it has nothing to buy,
     * and the screen says so rather than drawing a catalogue that leads
     * nowhere.
     */
    @Transactional(readOnly = true)
    @GetMapping("/categories")
    public List<MarketCategoryView> categoriesNear(@RequestParam(required = false) Double lat,
                                                   @RequestParam(required = false) Double lng) {
        if (lat == null || lng == null) {
            return List.of();
        }
        List<ShopDiscovery.NearbyShop> serving = discovery.shopsServing(lat, lng);
        if (serving.isEmpty()) {
            return List.of();
        }
        java.util.Map<Long, java.util.Set<Long>> byShop = shelves.categoriesOnTheShelvesOf(
                serving.stream().map(near -> near.shop().getId()).toList());

        java.util.Map<Long, Integer> shopCounts = new java.util.LinkedHashMap<>();
        for (java.util.Set<Long> ofOneShop : byShop.values()) {
            for (Long categoryId : ofOneShop) {
                shopCounts.merge(categoryId, 1, Integer::sum);
            }
        }
        if (shopCounts.isEmpty()) {
            return List.of();
        }

        // MOST-SERVED FIRST, and that IS a ranking - but it is a ranking of
        // categories rather than of shops, and it is the only sensible one: a
        // category twelve nearby shops stock is more use to this customer than
        // one a single shop stocks. Ties break on the catalogue's own order so
        // the list does not shuffle between two identical requests.
        return categories.findAllById(shopCounts.keySet()).stream()
                .filter(category -> Boolean.TRUE.equals(category.getActive()))
                .map(category -> new MarketCategoryView(category.getId(), category.getName(),
                        category.getImageUrl(), shopCounts.getOrDefault(category.getId(), 0)))
                .sorted(java.util.Comparator
                        .comparingInt(MarketCategoryView::shopCount).reversed()
                        .thenComparing(MarketCategoryView::categoryId))
                .toList();
    }

    /**
     * One category, named, with how many nearby shops sell it.
     *
     * CARRIES THE CATALOGUE'S OWN id, name and image rather than a second copy
     * of them - the Super Admin edits a category in one place and every screen
     * that draws it follows, which is what §2 asks for.
     */
    public record MarketCategoryView(Long categoryId, String name, String imageUrl,
                                     int shopCount) {}

    /**
     * One storefront, by id.
     *
     * Only if the marketplace shows it to customers - a draft, suspended or
     * closed shop answers 404, the same as one that does not exist. Whether a
     * particular shop is suspended is between the platform and that merchant.
     */
    @Transactional(readOnly = true)
    @GetMapping("/shops/{shopId}")
    public StorefrontDetail storefront(@PathVariable Long shopId) {
        if (!discovery.isBrowsableByCustomers(shopId)) {
            throw new com.gpstore.exception.ResourceNotFoundException("Shop not found");
        }
        Shop shop = shops.findById(shopId).orElseThrow(
                () -> new com.gpstore.exception.ResourceNotFoundException("Shop not found"));

        // TRUSTED AND THE POLICIES ARE HERE AND NOT ON THE LIST, deliberately.
        // Both need queries inside the shop's own scope - the trading record
        // is counted from its orders - and a discovery list of a dozen shops
        // would pay for all of them to draw one badge each. This is the screen
        // a customer opens when they are deciding whether to buy from a shop,
        // which is where the answer is worth a query.
        boolean trusted = shopScope.within(shopId, reliability::isTrusted);

        // THE RATING IS READ IN THIS SHOP'S SCOPE, like the trading record
        // above it, because shop_ratings is shop-owned - which is also what
        // makes it impossible for this to return a competitor's stars.
        com.gpstore.rating.ShopRatingSummary rating =
                shopScope.within(shopId, ratings::summary);
        List<PolicyView> promises = shopScope.within(shopId,
                () -> policies.findAllByOrderByKindAsc().stream()
                        .map(p -> new PolicyView(p.getKindName(), p.getBody()))
                        .toList());

        return new StorefrontDetail(
                view(shop, null, null, stars.forShops(List.of(shopId)).get(shopId)),
                trusted, promises,
                shop.getBusinessName(), rating);
    }

    /**
     * One storefront, as a customer deciding whether to buy from it sees it.
     *
     * @param trusted §10's EARNED badge, computed from this shop's own trading
     *                record every time it is asked. There is no column behind
     *                it, which is what makes it unpurchasable rather than
     *                merely expensive.
     */
    public record StorefrontDetail(StorefrontView shop, boolean trusted,
                                   List<PolicyView> policies, String businessName,
                                   // PART 2 §2: A SHOP PROFILE CARRIES ITS
                                   // RATING, and §19 of Part 3 says one
                                   // number is not enough - lifetime, recent,
                                   // and how many verified orders are behind
                                   // them. All three come from one summary
                                   // rather than three fields a screen has to
                                   // reassemble.
                                   com.gpstore.rating.ShopRatingSummary rating) {}

    /** A promise this shop makes, in its own words. */
    public record PolicyView(String kind, String body) {}

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

    private StorefrontView view(ShopDiscovery.NearbyShop nearby,
                                java.util.Map<Long, com.gpstore.discovery.PublicShopStars.Stars> stars) {
        return view(nearby.shop(), nearby.distanceKm(), nearby.deliversHere(),
                stars.get(nearby.shop().getId()));
    }

    /**
     * The same list, with every shop's stars read in one query rather than one
     * each.
     *
     * ONE PLACE, so a list endpoint cannot be added later that draws shops
     * without their ratings - or worse, draws them by asking per shop.
     */
    private List<StorefrontView> viewAll(List<ShopDiscovery.NearbyShop> nearby) {
        java.util.Map<Long, com.gpstore.discovery.PublicShopStars.Stars> theirStars =
                stars.forShops(nearby.stream().map(near -> near.shop().getId()).toList());
        return nearby.stream().map(near -> view(near, theirStars)).toList();
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
    private StorefrontView view(Shop shop, Double distanceKm, Boolean deliversHere,
                                com.gpstore.discovery.PublicShopStars.Stars theirStars) {
        StoreStatus status = shopScope.within(shop.getId(), schedule::getStoreStatus);
        return new StorefrontView(shop.getId(), shop.getCode(), shop.getDisplayName(),
                shop.getLatitude(), shop.getLongitude(),
                shop.getLogoUrl(),
                shop.getVerificationLevel(), shop.getVerificationLevel().badge(),
                shop.getMaxDeliveryRadiusKm(),
                distanceKm, deliversHere,
                status.browsingOpen(), status.acceptingOrders(),
                status.closedToday(), status.closureReason(), status.pausedUntil(),
                status.deliveryDate(),
                shop.getSupportPhone(), shop.getTimeZone(),
                theirStars == null ? null : theirStars.average(),
                theirStars == null ? 0 : (int) theirStars.count());
    }
}
