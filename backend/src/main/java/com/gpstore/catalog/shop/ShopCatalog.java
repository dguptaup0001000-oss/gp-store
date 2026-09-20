package com.gpstore.catalog.shop;

import com.gpstore.exception.ConflictException;
import com.gpstore.entity.ProductVariant;
import com.gpstore.platform.PlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The one place that answers "what does THIS shop charge for that item".
 *
 * Every price a customer sees or is charged goes through here, and here reads
 * shop_product_variants - which the Slice 1 filter has already restricted to
 * the shop in scope. No caller passes a shop id, because no caller is trusted
 * to know one: the scope comes from the credential, and a method that accepted
 * a shop id would be a method somebody could pass another shop's.
 *
 * THE CATALOGUE IS THE DEFAULT, NOT THE PRICE. product_variants.selling_price
 * is what a shop STARTS from when it begins stocking an item. Once it is
 * listed, the shop's own row is the answer and the catalogue's number is not
 * consulted - which is what lets two shops charge differently for the same
 * atta without either of them touching the other's row.
 */
@Service
public class ShopCatalog {

    private static final Logger log = LoggerFactory.getLogger(ShopCatalog.class);

    private final ShopProductVariantRepository listings;
    private final PlatformProperties platform;
    private final ShopShelfCache shelfCache;

    public ShopCatalog(ShopProductVariantRepository listings, PlatformProperties platform,
                       ShopShelfCache shelfCache) {
        this.shelfCache = shelfCache;
        this.listings = listings;
        this.platform = platform;
    }

    /** This shop's listing for one catalogue item, or empty when it does not sell it. */
    @Transactional(readOnly = true)
    public Optional<ShopProductVariant> listingFor(Long productVariantId) {
        if (productVariantId == null) {
            return Optional.empty();
        }
        return listings.findByProductVariantId(productVariantId);
    }

    /**
     * Listings for many items in one query.
     *
     * Used by the cart and the product grid, where the alternative is one
     * SELECT per line - the N+1 that this method exists to prevent.
     */
    @Transactional(readOnly = true)
    public Map<Long, ShopProductVariant> listingsFor(Collection<Long> productVariantIds) {
        if (productVariantIds == null || productVariantIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = new LinkedHashSet<>(productVariantIds);
        ids.remove(null);
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, ShopProductVariant> byVariant = new HashMap<>();
        for (ShopProductVariant listing : listings.findByProductVariantIdIn(ids)) {
            byVariant.put(listing.getProductVariantId(), listing);
        }
        return byVariant;
    }

    /**
     * What this shop charges, falling back to the catalogue only under SINGLE_SHOP.
     *
     * THE FALLBACK IS A SAFETY NET, NOT A DESIGN. Every path that creates a
     * variant also lists it (see ProductVariantService and the catalogue
     * importer), and ShopCatalogReconciliation lists anything those missed at
     * startup. If a listing is still absent under one shop, the honest thing is
     * to keep selling at the catalogue price and say so in the log, rather than
     * to make a shopkeeper's product vanish because of a wiring gap.
     *
     * THERE IS NO FALLBACK IN A MARKETPLACE. With more than one shop, "not
     * listed" means "this shop does not sell it" - which is a real answer, and
     * quietly pricing it from the catalogue would put an item on a shelf its
     * owner never stocked. Note that the fallback reads the CENTRAL catalogue
     * either way; it can never reach another shop's price.
     */
    /**
     * Refuses anything this shop does not actually sell over the internet.
     *
     * <h2>Why this is in the backend and not in the app</h2>
     *
     * <p>A Visit-to-Buy listing has no ADD button and a service card has no
     * cart, but a button is a suggestion. The only thing that decides whether
     * a ring can be put in a basket is the server, because the request that
     * matters is the one somebody makes with curl after reading the network
     * tab. A UI-only rule is not a rule; it is a preference the client is
     * free to ignore.
     *
     * <p>ASKED WHERE THE PRICE IS ASKED FOR, which is the one road every
     * purchase already travels. Add-to-cart, checkout and re-pricing all have
     * to learn what a shop charges before they can proceed, so a refusal here
     * cannot be walked around by finding a different entry point - there is
     * not one.
     *
     * <p>THE MESSAGE SAYS WHICH THING HAPPENED. "Unavailable" would be a lie
     * of the most annoying kind: the item is there, the shop has it, and the
     * customer is perfectly able to buy it - just not like this. A customer
     * told to visit the shop can act on that. A customer told "unavailable"
     * goes and buys it somewhere else.
     *
     * @throws ConflictException if the listing exists but is not sold online
     */
    @Transactional(readOnly = true)
    public void refuseIfNotBuyableOnline(ProductVariant variant) {
        if (variant == null) {
            return;
        }
        listingFor(variant.getId()).ifPresent(listing -> refuseListingIfNotBuyableOnline(variant, listing));
    }

    /** The same refusal for a listing already loaded - checkout prices in bulk. */
    public void refuseListingIfNotBuyableOnline(ProductVariant variant, ShopProductVariant listing) {
        if (listing == null) {
            return;
        }
        CommerceMode mode = listing.getCommerceMode();
        if (mode == null || mode.isBuyableOnline()) {
            return;
        }
        String what = variant != null && variant.getProduct() != null
                ? variant.getProduct().getName() : "This item";
        if (mode == CommerceMode.SERVICE_AT_SHOP) {
            throw new ConflictException(what
                    + " is a service carried out at the shop, so it cannot be added to a basket. "
                    + "Visit the shop to have it done.");
        }
        throw new ConflictException(what
                + " is sold at the shop rather than online. Visit the shop to buy it.");
    }

    @Transactional(readOnly = true)
    public Optional<BigDecimal> priceOf(ProductVariant variant) {
        if (variant == null) {
            return Optional.empty();
        }
        Optional<ShopProductVariant> listing = listingFor(variant.getId());
        if (listing.isPresent()) {
            return Optional.ofNullable(listing.get().getSellingPrice());
        }
        return catalogueFallback(variant);
    }

    /**
     * Listings for one NAMED shop, whatever scope the thread is in.
     *
     * FOR CHECKOUT, WHICH VISITS SEVERAL SHOPS IN ONE TRANSACTION. The
     * Hibernate filter is enabled when a persistence session is opened, so it
     * cannot change part-way through a transaction - which means a checkout
     * that splits a basket across shops cannot lean on it to price each half.
     * Naming the shop is the honest alternative, and it is the same pattern
     * Slice 5 used for the background paths.
     *
     * THE SHOP ID IS NOT A CALLER'S OPINION. It comes off the cart line, which
     * CartService stamped from the shop that add-to-cart resolved to.
     */
    @Transactional(readOnly = true)
    public Map<Long, ShopProductVariant> listingsForShop(Long shopId, Collection<Long> productVariantIds) {
        if (shopId == null || productVariantIds == null || productVariantIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = new LinkedHashSet<>(productVariantIds);
        ids.remove(null);
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, ShopProductVariant> byVariant = new HashMap<>();
        for (ShopProductVariant listing : listings.findByShopIdAndProductVariantIdIn(shopId, ids)) {
            byVariant.put(listing.getProductVariantId(), listing);
        }
        return byVariant;
    }

    /** As {@link #priceOf}, for a listing already loaded in bulk. */
    public Optional<BigDecimal> priceOf(ProductVariant variant, Map<Long, ShopProductVariant> loaded) {
        if (variant == null) {
            return Optional.empty();
        }
        ShopProductVariant listing = loaded.get(variant.getId());
        if (listing != null) {
            return Optional.ofNullable(listing.getSellingPrice());
        }
        return catalogueFallback(variant);
    }

    private Optional<BigDecimal> catalogueFallback(ProductVariant variant) {
        if (platform.getMode().isMultiShop()) {
            return Optional.empty();
        }
        log.warn("Variant {} is not listed by this shop; falling back to the catalogue price. "
                + "Under one shop that is safe, but it means a creation path did not list it.",
                variant.getId());
        return Optional.ofNullable(variant.getSellingPrice());
    }

    /** Whether this shop lists the item at all, at a price a customer can be charged. */
    @Transactional(readOnly = true)
    public boolean isOrderable(ProductVariant variant) {
        if (variant == null) {
            return false;
        }
        Optional<ShopProductVariant> listing = listingFor(variant.getId());
        if (listing.isPresent()) {
            return listing.get().isOrderable();
        }
        return catalogueFallback(variant).map(p -> p.compareTo(BigDecimal.ZERO) > 0).orElse(false);
    }

    /**
     * Puts an item on this shop's shelf, or updates what it says.
     *
     * NOTHING NAMES A SHOP. The row is stamped by TenantEntityListener from the
     * scope on the thread, so this cannot be called "for" another shop even by
     * a caller that wants to - which is why there is no shopId parameter to
     * leave out of a validation.
     *
     * A CALLER WITH NO SHOP LISTS NOTHING, and that is a real caller rather
     * than a defensive branch. A platform admin defining a catalogue variant
     * (§10: one central PRODUCT, per-shop SHOP_PRODUCT) is acting for the
     * marketplace, not for a shelf - so there is no shop to put the item on,
     * and each shop lists it for itself through /api/shop/listings.
     *
     * Without this, creating any catalogue variant under
     * MULTI_SHOP_PRODUCTION answered 500: TenantDefaults correctly refuses to
     * insert a shop-owned row with no shop, and this was asking it to. Found
     * by building a real second shop; under one shop every caller has a scope
     * and the branch never ran.
     */
    @Transactional
    /**
     * Persists a listing this class handed out.
     *
     * <p>NARROW ON PURPOSE. The repository stays private so nothing outside
     * can query across shops through it; this only writes back a row a caller
     * already legitimately holds, and the tenant listener stamps the shop on
     * insert either way.
     */
    public ShopProductVariant save(ShopProductVariant listing) {
        return listings.save(listing);
    }

    public ShopProductVariant list(ProductVariant variant) {
        if (variant == null || variant.getId() == null) {
            throw new IllegalArgumentException("A listing needs a catalogue variant.");
        }
        com.gpstore.platform.TenantScope scope = com.gpstore.platform.TenantContext.current();
        if (scope == null || scope.isPlatform()) {
            log.debug("Catalogue variant {} defined without a shop in scope - "
                    + "no shelf to list it on.", variant.getId());
            return null;
        }
        BigDecimal price = variant.getSellingPrice();
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            // The catalogue has no price to start from. Not an error: the
            // variant simply stays unlisted until somebody prices it, which is
            // what the pre-marketplace code did with a null price too.
            return null;
        }

        ShopProductVariant listing = listings.findByProductVariantId(variant.getId())
                .orElseGet(ShopProductVariant::new);
        listing.setProductVariantId(variant.getId());
        listing.setSellingPrice(price);
        listing.setCostPrice(variant.getCostPrice());
        listing.setMrp(variant.getMrp());
        listing.setAvailable(variant.getAvailable() == null ? Boolean.TRUE : variant.getAvailable());
        listing.setActive(variant.getActive() == null ? Boolean.TRUE : variant.getActive());
        listing.setDisplayOrder(variant.getDisplayOrder());
        ShopProductVariant saved = listings.save(listing);
        shelfCache.changed();
        return saved;
    }

    /**
     * Takes an item off this shop's shelf.
     *
     * DELISTS RATHER THAN DELETES the catalogue entry, and does nothing to any
     * other shop: one kirana dropping a line does not remove it from the
     * marketplace.
     */
    @Transactional
    public void delist(Long productVariantId) {
        listings.findByProductVariantId(productVariantId).ifPresent(listing -> {
            listing.setActive(Boolean.FALSE);
            listing.setAvailable(Boolean.FALSE);
            listings.save(listing);
            shelfCache.changed();
        });
    }
}
