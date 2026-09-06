package com.gpstore.catalog.shop;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Component;

/**
 * "This shop's shelf changed" - said once, so every cached answer that was
 * built from the old shelf goes.
 *
 * FOUND BY DRIVING THE REAL APP. A shopkeeper repriced an item through the
 * merchant screen, and the customer app went on showing the old price - not
 * for a moment, but until the ten-minute cache TTL drained. Under one shop
 * this was already possible; under a marketplace it is worse, because the
 * price a customer sees IS the shop's listing, so a price edit that does not
 * reach the storefront is a shop quoting a number it has stopped charging.
 * Delisting had the same shape: the item stayed on the shelf until the TTL.
 *
 * WHY EVERY BROWSE CACHE AND NOT JUST THE PRICE ONE. The listing decides
 * three separate things a customer sees - whether the item appears at all,
 * what it costs, and where it sorts - and those are spread across the feed,
 * search, the brand counts, the collage and the product page. Evicting the
 * one that was obviously wrong would leave the other six wrong for ten
 * minutes each. It is the same list ProductService.saveProduct evicts, for
 * the same reason.
 *
 * ALL SHOPS' ENTRIES, and that is a deliberate trade. Cache keys are
 * shop-aware (CacheConfig.keyGenerator), but @CacheEvict clears a region
 * rather than a key prefix, so one kirana editing a price re-warms every
 * shop's browse caches. On a hyperlocal marketplace of a few dozen shops
 * that is a handful of extra queries; a price a customer cannot be charged
 * is not a trade at all. If the shop count ever makes this hurt, the fix is
 * a keyed eviction here, not a longer-lived wrong price.
 */
@Component
public class ShopShelfCache {

    /**
     * Call AFTER the listing row is saved.
     *
     * Named as an event rather than as an action because the caller's job is
     * to report what happened, not to know which caches exist - that list
     * belongs here, where it can be kept in step with the ones the browse
     * endpoints actually use.
     */
    @CacheEvict(value = {"products", "brands", "newArrivals", "categoryProducts",
            "productDetail", "productSearch", "productFeed", "bestsellerTiles",
            "trending", "frequentlyBought"}, allEntries = true)
    public void changed() {
        // The annotation is the whole method.
    }
}
