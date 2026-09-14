package com.gpstore.discovery;

import com.gpstore.catalog.shop.ShopProductVariantRepository;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which shops sell what, in the only sense a customer cares about.
 *
 * THE QUESTION THIS ANSWERS is "who near me sells medicine?", and GP-STORE
 * has no column that says so. It does not need one: a shop sells medicine
 * when it is listing something orderable in that category, and that fact is
 * already sitting in shop_product_variants. Deriving it means a merchant who
 * stops stocking medicine leaves the Medicine screen the moment their last
 * listing goes - no tag to remember to clear, and no second truth to drift
 * out of step with the shelf.
 *
 * ONE QUERY FOR EVERY CANDIDATE SHOP, not one per shop. The obvious shape -
 * loop the shops, ask each - is O(shops in radius) round trips, which is
 * survivable at a dozen shops and is not what this marketplace is being built
 * for. The shop ids come from the discovery ladder, so the IN list is already
 * bounded by how far the customer is willing to look.
 *
 * PLATFORM SCOPE, WITH THE SHOP FILTER OFF, AND THAT IS THE THING TO READ
 * CAREFULLY. Every other customer-facing read of a shop's shelf runs inside
 * that one shop's scope, because what it reads - prices, stock, listings - is
 * that shop's business. This one deliberately does not, because the question
 * spans shops by its nature. The safety is in the projection rather than in
 * the filter: the query selects a shop id, a category id and a count, so what
 * a public caller can learn from it is "shop 6 sells groceries, and has 40
 * things in it" - which is precisely what the discovery screen exists to tell
 * them. Nothing that belongs to one merchant alone is in the result set, and
 * CategoryDiscoveryTest fails if that ever stops being true.
 */
@Service
public class ShopCategoryPresence {

    private final ShopProductVariantRepository listings;

    public ShopCategoryPresence(ShopProductVariantRepository listings) {
        this.listings = listings;
    }

    /**
     * Category ids on each of these shops' shelves, keyed by shop.
     *
     * A shop with nothing orderable is absent from the map rather than
     * present with an empty set - "no answer" and "nothing" are the same
     * thing to every caller here, and an absent key is the cheaper one.
     */
    @Transactional(readOnly = true)
    public Map<Long, Set<Long>> categoriesOnTheShelvesOf(Collection<Long> shopIds) {
        if (shopIds == null || shopIds.isEmpty()) {
            return Map.of();
        }
        List<ShopProductVariantRepository.ShelfCategory> rows =
                TenantContext.runWithin(TenantScope.platform(),
                        () -> listings.findShelfCategories(shopIds));

        Map<Long, Set<Long>> byShop = new LinkedHashMap<>();
        for (ShopProductVariantRepository.ShelfCategory row : rows) {
            if (row.getShopId() == null || row.getCategoryId() == null) {
                continue;
            }
            byShop.computeIfAbsent(row.getShopId(), id -> new LinkedHashSet<>())
                    .add(row.getCategoryId());
        }
        return byShop;
    }

    /**
     * Every category any of these shops stocks, in no particular order.
     *
     * THE HOME SCREEN'S LIST. A category nobody near this customer stocks is
     * a category that leads to an empty screen, so it is not offered - which
     * is also what keeps a national catalogue of dozens of categories from
     * being drawn over a town that has four kiranas and a chemist.
     */
    @Transactional(readOnly = true)
    public Set<Long> categoriesSoldByAnyOf(Collection<Long> shopIds) {
        Set<Long> all = new LinkedHashSet<>();
        for (Set<Long> ofOneShop : categoriesOnTheShelvesOf(shopIds).values()) {
            all.addAll(ofOneShop);
        }
        return all;
    }
}
