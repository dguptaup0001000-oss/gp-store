package com.gpstore.catalog.shop;

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

    public ShopCatalog(ShopProductVariantRepository listings, PlatformProperties platform) {
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
     */
    @Transactional
    public ShopProductVariant list(ProductVariant variant) {
        if (variant == null || variant.getId() == null) {
            throw new IllegalArgumentException("A listing needs a catalogue variant.");
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
        return listings.save(listing);
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
        });
    }
}
