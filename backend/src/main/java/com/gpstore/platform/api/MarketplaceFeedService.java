package com.gpstore.platform.api;

import com.gpstore.catalog.shop.CommerceMode;
import com.gpstore.catalog.shop.ListingPriceMode;
import com.gpstore.catalog.shop.MarketplaceFeedRepository;
import com.gpstore.catalog.shop.OfflineAvailability;
import com.gpstore.platform.ShopDiscovery;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What is for sale near this customer, without them having chosen a shop first.
 *
 * <p>The default customer experience is now: open GP-STORE, see the
 * marketplace. Choosing a storefront stays available and stays useful - a
 * customer who wants to see what the hardware shop on the corner has can - but
 * it is no longer the toll gate in front of every product in the town.
 */
@Service
public class MarketplaceFeedService {

    /** Bounded so a crafted query string cannot ask for the whole marketplace in one page. */
    private static final int MAX_PAGE = 50;

    private final ShopDiscovery discovery;
    private final MarketplaceFeedRepository feed;

    public MarketplaceFeedService(ShopDiscovery discovery, MarketplaceFeedRepository feed) {
        this.discovery = discovery;
        this.feed = feed;
    }

    /**
     * One page of the marketplace at this pin.
     *
     * <p>TWO QUERIES, WHATEVER THE SIZE OF THE TOWN. One asks ShopDiscovery
     * which shops serve this customer - already a single bounded query since
     * the discovery work - and one asks the database for a page of products
     * across exactly those shops. Nothing here loops over shops, and nothing
     * here asks a question per product. That is not a style preference: the
     * per-shop version of this endpoint is what put the application's ceiling
     * at about a hundred concurrent browsers.
     *
     * <p>NO PIN, NO MARKETPLACE. An address that cannot be placed cannot be
     * shown which shops deliver to it, and inventing a location would show a
     * customer in one town the shops of another. Empty is the honest answer
     * and it is the same rule /api/marketplace/shops has always applied.
     */
    @Transactional(readOnly = true)
    public List<MarketplaceFeedView> page(Double lat, Double lng, Set<CommerceMode> modes,
                                          Long categoryId, int page, int size) {
        return page(lat, lng, modes, categoryId, null, page, size);
    }

    @Transactional(readOnly = true)
    public List<MarketplaceFeedView> page(Double lat, Double lng, Set<CommerceMode> modes,
                                          Long categoryId, Long selectedShopId, int page, int size) {
        if (lat == null || lng == null) {
            return List.of();
        }
        List<ShopDiscovery.NearbyShop> nearby = discovery.shopsServing(lat, lng);
        if (nearby.isEmpty()) {
            return List.of();
        }

        Map<Long, Double> distanceByShop = new HashMap<>();
        for (ShopDiscovery.NearbyShop near : nearby) {
            distanceByShop.put(near.shop().getId(), near.distanceKm());
        }
        if (selectedShopId != null) {
            Double selectedDistance = distanceByShop.get(selectedShopId);
            if (selectedDistance == null) return List.of();
            distanceByShop.clear();
            distanceByShop.put(selectedShopId, selectedDistance);
        }

        int limit = Math.min(Math.max(size, 1), MAX_PAGE);
        int offset = Math.max(page, 0) * limit;

        List<MarketplaceFeedView> cards = new ArrayList<>();
        for (Object[] row : feed.page(distanceByShop.keySet(),
                modes == null || modes.isEmpty() ? Set.of(CommerceMode.ONLINE_PURCHASE) : modes,
                categoryId, distanceByShop, limit, offset)) {
            cards.add(toCard(row));
        }
        return cards;
    }

    /**
     * A search of the whole marketplace, across every mode asked for.
     *
     * <p>SEARCH HAD THE SAME BUG THE HOME FEED DID: every existing search
     * route is shop-scoped, so a customer who had chosen no shop was
     * searching Shop #1's shelf and being told the town does not stock what
     * they asked for. This looks where the customer is standing instead.
     *
     * <p>A customer typing "haircut" wants the barber and one typing "gold
     * chain" wants the jeweller they have to visit, so the default here is
     * ALL THREE MODES rather than what a cart can hold. The mode is on every
     * result, so the screen can label them.
     */
    @Transactional(readOnly = true)
    public List<MarketplaceFeedView> search(String keyword, Double lat, Double lng,
                                            Set<CommerceMode> modes, int page, int size) {
        return search(keyword, lat, lng, modes, null, page, size);
    }

    @Transactional(readOnly = true)
    public List<MarketplaceFeedView> search(String keyword, Double lat, Double lng,
                                            Set<CommerceMode> modes, Long selectedShopId,
                                            int page, int size) {
        if (lat == null || lng == null || keyword == null || keyword.isBlank()) {
            return List.of();
        }
        List<ShopDiscovery.NearbyShop> nearby = discovery.shopsServing(lat, lng);
        if (nearby.isEmpty()) {
            return List.of();
        }
        Map<Long, Double> distanceByShop = new HashMap<>();
        for (ShopDiscovery.NearbyShop near : nearby) {
            distanceByShop.put(near.shop().getId(), near.distanceKm());
        }
        if (selectedShopId != null) {
            Double selectedDistance = distanceByShop.get(selectedShopId);
            if (selectedDistance == null) return List.of();
            distanceByShop.clear();
            distanceByShop.put(selectedShopId, selectedDistance);
        }

        int limit = Math.min(Math.max(size, 1), MAX_PAGE);
        int offset = Math.max(page, 0) * limit;

        List<MarketplaceFeedView> results = new ArrayList<>();
        for (Object[] row : feed.search(keyword, distanceByShop.keySet(),
                modes == null || modes.isEmpty() ? Set.of(CommerceMode.values()) : modes,
                distanceByShop, limit, offset)) {
            results.add(toCard(row));
        }
        return results;
    }

    /**
     * Every nearby shop offering this product, nearest first.
     *
     * <p>ALL THREE MODES, ALWAYS. The customer tapped a Visit-to-Buy card and
     * a shop two streets further sells the same thing online - telling them
     * only about the first is filtering away the better answer to the question
     * they actually have, which is "how do I get this?". The mode is on every
     * row, so the screen can group them; it is not a reason to drop one.
     *
     * <p>Same two queries as the feed: one to ShopDiscovery for who serves
     * this pin, one to the database for the offers.
     */
    @Transactional(readOnly = true)
    public List<MarketplaceOfferView> offersOf(Long productId, Double lat, Double lng) {
        if (productId == null || lat == null || lng == null) {
            return List.of();
        }
        List<ShopDiscovery.NearbyShop> nearby = discovery.shopsServing(lat, lng);
        if (nearby.isEmpty()) {
            return List.of();
        }
        Map<Long, Double> distanceByShop = new HashMap<>();
        for (ShopDiscovery.NearbyShop near : nearby) {
            distanceByShop.put(near.shop().getId(), near.distanceKm());
        }

        List<MarketplaceOfferView> offers = new ArrayList<>();
        for (Object[] row : feed.offersOf(productId, distanceByShop.keySet(), distanceByShop)) {
            offers.add(toOffer(row));
        }
        return offers;
    }

    private MarketplaceOfferView toOffer(Object[] r) {
        CommerceMode mode = requiredEnum(CommerceMode.class, (String) r[10], "commerce mode");
        return new MarketplaceOfferView(
                (Long) r[3], (String) r[4], (String) r[5],
                (Long) r[0], asDouble(r[1]), (String) r[2],
                (BigDecimal) r[6], (BigDecimal) r[7], (BigDecimal) r[8],
                enumOf(ListingPriceMode.class, (String) r[9], ListingPriceMode.EXACT_PRICE),
                mode, mode.customerLabel(),
                enumOf(OfflineAvailability.class, (String) r[11], null),
                asInteger(r[12]),
                (Long) r[13], (String) r[14],
                (String) r[15], (String) r[16], (String) r[17], (String) r[18],
                asDouble(r[19]), asDouble(r[20]),
                (String) r[21], (Double) r[22]);
    }

    private MarketplaceFeedView toCard(Object[] r) {
        CommerceMode mode = requiredEnum(CommerceMode.class, (String) r[12], "commerce mode");
        return new MarketplaceFeedView(
                (Long) r[0], (String) r[1], (String) r[2],
                asLong(r[3]), (String) r[4],
                null,
                (Long) r[5], asDouble(r[6]), (String) r[7],
                (BigDecimal) r[8], (BigDecimal) r[9], (BigDecimal) r[10],
                enumOf(ListingPriceMode.class, (String) r[11], ListingPriceMode.EXACT_PRICE),
                mode, mode.customerLabel(),
                enumOf(OfflineAvailability.class, (String) r[13], null),
                asInteger(r[14]),
                (Long) r[15], (String) r[16], (Double) r[17], (Integer) r[18]);
    }

    /** Optional display metadata may retain a backwards-compatible fallback. */
    private static <E extends Enum<E>> E enumOf(Class<E> type, String raw, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }

    /**
     * Commerce mode decides whether an item may enter a cart. Missing or
     * unknown stored data must be observable rather than reclassified as
     * ONLINE_PURCHASE. V74 made the column NOT NULL and constrained its
     * values, so reaching this means schema/data drift that must stop the
     * response instead of changing the merchant's business rule.
     */
    private static <E extends Enum<E>> E requiredEnum(
            Class<E> type, String raw, String what) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Missing " + what + " in marketplace listing");
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalStateException("Unsupported " + what + " in marketplace listing");
        }
    }

    private static Long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    private static Double asDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static Integer asInteger(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }
}
