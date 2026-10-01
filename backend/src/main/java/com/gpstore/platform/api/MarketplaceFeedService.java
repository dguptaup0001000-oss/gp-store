package com.gpstore.platform.api;

import com.gpstore.catalog.shop.CommerceMode;
import com.gpstore.catalog.shop.ListingPriceMode;
import com.gpstore.catalog.shop.MarketplaceFeedRepository;
import com.gpstore.catalog.shop.OfflineAvailability;
import com.gpstore.platform.ShopDiscovery;

import org.springframework.stereotype.Service;

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
    private final com.gpstore.intelligence.MarketplaceSignals signals;
    private final com.gpstore.search.SynonymDictionary synonyms;

    public MarketplaceFeedService(ShopDiscovery discovery, MarketplaceFeedRepository feed,
                                  com.gpstore.intelligence.MarketplaceSignals signals,
                                  com.gpstore.search.SynonymDictionary synonyms) {
        this.discovery = discovery;
        this.feed = feed;
        this.signals = signals;
        this.synonyms = synonyms;
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
    public List<MarketplaceFeedView> page(Double lat, Double lng, Set<CommerceMode> modes,
                                          Long categoryId, int page, int size) {
        return page(lat, lng, modes, categoryId, null, page, size);
    }

    public List<MarketplaceFeedView> page(Double lat, Double lng, Set<CommerceMode> modes,
                                          Long categoryId, Long selectedShopId, int page, int size) {
        if (lat == null || lng == null) {
            return List.of();
        }
        List<ShopDiscovery.NearbyShop> nearby = discovery.shopsServing(lat, lng);
        if (nearby.isEmpty()) {
            return List.of();
        }

        int limit = Math.min(Math.max(size, 1), MAX_PAGE);
        int offset = Math.max(page, 0) * limit;

        Map<Long, Double> distanceByShop = new HashMap<>();
        if (selectedShopId != null) {
            for (ShopDiscovery.NearbyShop near : nearby) {
                distanceByShop.put(near.shop().getId(), near.distanceKm());
            }
            Double selectedDistance = distanceByShop.get(selectedShopId);
            if (selectedDistance == null) return List.of();
            distanceByShop.clear();
            distanceByShop.put(selectedShopId, selectedDistance);
        } else {
            // The repository's fair walk orders shop_row first and distance
            // second. A 20-card first page can therefore be decided by the
            // nearest 52 shops; sending another 2,064 shops into PostgreSQL
            // cannot change that prefix, but did keep every DB connection
            // ranking data the client could not receive. Grow monotonically
            // with the requested offset so infinite-scroll pages stay stable.
            int considered = Math.min(nearby.size(), offset + limit + 32);
            for (ShopDiscovery.NearbyShop near : nearby.subList(0, considered)) {
                distanceByShop.put(near.shop().getId(), near.distanceKm());
            }
        }

        List<MarketplaceFeedView> cards = new ArrayList<>();
        for (Object[] row : feed.page(distanceByShop.keySet(),
                modes == null || modes.isEmpty() ? Set.of(CommerceMode.values()) : modes,
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
    public List<MarketplaceFeedView> search(String keyword, Double lat, Double lng,
                                            Set<CommerceMode> modes, int page, int size) {
        return search(keyword, lat, lng, modes, null, page, size);
    }

    public List<MarketplaceFeedView> search(String keyword, Double lat, Double lng,
                                            Set<CommerceMode> modes, Long selectedShopId,
                                            int page, int size) {
        if (lat == null || lng == null || keyword == null || keyword.isBlank()) {
            return List.of();
        }
        Set<CommerceMode> requestedModes =
                modes == null || modes.isEmpty() ? Set.of(CommerceMode.values()) : modes;
        String interpreted = normalizeSearch(keyword);
        List<ShopDiscovery.NearbyShop> nearby = discovery.shopsServing(lat, lng);
        Map<Long, Double> distanceByShop = new HashMap<>();
        for (ShopDiscovery.NearbyShop near : nearby) {
            distanceByShop.put(near.shop().getId(), near.distanceKm());
        }
        if (selectedShopId != null) {
            Double selectedDistance = distanceByShop.get(selectedShopId);
            if (selectedDistance == null) {
                if (page <= 0) signals.search(keyword, lat, lng, modes, 0);
                return List.of();
            }
            distanceByShop.clear();
            distanceByShop.put(selectedShopId, selectedDistance);
        }

        int limit = Math.min(Math.max(size, 1), MAX_PAGE);
        int offset = Math.max(page, 0) * limit;

        List<MarketplaceFeedView> results = new ArrayList<>();
        if (!distanceByShop.isEmpty()) {
            for (Object[] row : searchWithOriginalFallback(
                    interpreted, keyword, distanceByShop, requestedModes, limit, offset)) {
                results.add(toCard(row));
            }
        }
        // Local-first expansion uses the same radius ladder as shop discovery.
        // It runs only for the first unscoped page and only when local supply
        // produced no result; a caller-selected shop is never silently changed.
        if (results.isEmpty() && page <= 0 && selectedShopId == null) {
            ShopDiscovery.RadiusSearch expanded = discovery.searchOutwards(
                    lat, lng, null, rung -> {
                        if (rung.isEmpty()) return List.of();
                        Map<Long, Double> rungDistances = distances(rung);
                        return searchWithOriginalFallback(
                                interpreted, keyword, rungDistances, requestedModes, 1, 0)
                                .isEmpty() ? List.of() : rung;
                    });
            Map<Long, Double> expandedDistances = distances(expanded.shops());
            if (!expandedDistances.isEmpty()
                    && !expandedDistances.keySet().equals(distanceByShop.keySet())) {
                for (Object[] row : searchWithOriginalFallback(
                        interpreted, keyword, expandedDistances, requestedModes, limit, offset)) {
                    results.add(toCard(row));
                }
            }
        }
        // One event per customer search, not per card or shop. Page two is
        // continuation traffic and must not inflate demand.
        if (page <= 0) {
            signals.search(keyword, lat, lng, modes, results.size());
        }
        return results;
    }

    private List<Object[]> searchWithOriginalFallback(
            String interpreted, String original, Map<Long, Double> distances,
            Set<CommerceMode> modes, int limit, int offset) {
        List<Object[]> rows = feed.search(
                interpreted, distances.keySet(), modes, distances, limit, offset);
        // Phonetic synonym keys are deliberately lossy: for example, an
        // English catalogue word can sound like a Hindi vocabulary term.
        // Translation gets first chance, but it must never erase a literal
        // town-wide match such as "gold chain".
        if (rows.isEmpty() && !interpreted.equalsIgnoreCase(original.trim())) {
            return feed.search(original, distances.keySet(), modes, distances, limit, offset);
        }
        return rows;
    }

    private String normalizeSearch(String keyword) {
        List<String> tokens = com.gpstore.search.SearchNormalizer.tokenize(keyword);
        if (tokens.isEmpty()) return keyword.trim();
        return tokens.stream()
                .map(token -> synonyms.canonicalFor(token).orElse(token))
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private static Map<Long, Double> distances(List<ShopDiscovery.NearbyShop> nearby) {
        Map<Long, Double> result = new HashMap<>();
        for (ShopDiscovery.NearbyShop near : nearby) {
            result.put(near.shop().getId(), near.distanceKm());
        }
        return result;
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
    public List<MarketplaceOfferView> offersOf(Long productId, Double lat, Double lng) {
        return offersOf(productId, null, lat, lng);
    }

    /**
     * Variant-safe offer comparison. New clients always provide variantId so
     * 500 g and 1 kg packs can never appear as equivalent offers.
     */
    public List<MarketplaceOfferView> offersOf(Long productId, Long variantId,
                                               Double lat, Double lng) {
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
        for (Object[] row : feed.offersOf(
                productId, variantId, distanceByShop.keySet(), distanceByShop)) {
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
                (String) r[19],
                (Boolean) r[20],
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
