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

    private MarketplaceFeedView toCard(Object[] r) {
        CommerceMode mode = enumOf(CommerceMode.class, (String) r[12], CommerceMode.ONLINE_PURCHASE);
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

    /**
     * An unrecognised stored value falls back rather than throwing.
     *
     * <p>A row written by a newer deployment during a rolling release must not
     * take the whole home screen down for everybody still on the old one. The
     * card renders as the safe thing instead, which for a commerce mode means
     * the mode every existing listing already had.
     */
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
