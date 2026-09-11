package com.gpstore.discovery;

import com.gpstore.catalog.shop.ShopCatalog;
import com.gpstore.catalog.shop.ShopProductVariant;
import com.gpstore.entity.ProductVariant;
import com.gpstore.platform.Shop;
import com.gpstore.platform.ShopDiscovery;
import com.gpstore.platform.ShopReliability;
import com.gpstore.platform.ShopScopeSwitch;
import com.gpstore.rating.ShopRatingService;
import com.gpstore.rating.ShopRatingSummary;
import com.gpstore.repository.InventoryRepository;
import com.gpstore.repository.ProductVariantRepository;
import com.gpstore.service.DeliveryFeeService;
import com.gpstore.store.DeliveryScheduleService;
import com.gpstore.store.StoreStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The same item, priced by every shop that could bring it (§8).
 *
 * <p>WHAT MAKES THIS DIFFERENT FROM A PRODUCT LIST. A storefront shows one
 * shop's shelf. This crosses the counter on purpose - it is the "Compare
 * other shops" screen §8 asks for - so every per-shop read below happens
 * inside {@link ShopScopeSwitch#within}, one shop at a time. Nothing here
 * reads across shops in a single query, because a query that could would be
 * a query that could leak.
 *
 * <p>THE FINAL PAYABLE IS BUILT HERE AND NOWHERE ELSE (§7). Price, minus
 * whatever the shop is discounting, plus that shop's own delivery charge for
 * this customer's distance. Three numbers from three different places, which
 * is precisely why a screen must not be left to add them up itself - two
 * screens would eventually add them up differently.
 *
 * <p>COST: one scope switch and a handful of reads per shop. That is
 * affordable for a comparison of one item across the shops in one town, and
 * it is not affordable for a catalogue page - which is why the storefront
 * browse path does not come through here.
 */
@Service
public class ShopOffers {

    private final ShopDiscovery discovery;
    private final ShopScopeSwitch shopScope;
    private final ShopCatalog catalog;
    private final InventoryRepository inventory;
    private final ProductVariantRepository variants;
    private final DeliveryFeeService deliveryFees;
    private final DeliveryScheduleService schedule;
    private final ShopRatingService ratings;
    private final ShopReliability reliability;

    public ShopOffers(ShopDiscovery discovery, ShopScopeSwitch shopScope, ShopCatalog catalog,
                      InventoryRepository inventory, ProductVariantRepository variants,
                      DeliveryFeeService deliveryFees, DeliveryScheduleService schedule,
                      ShopRatingService ratings, ShopReliability reliability) {
        this.discovery = discovery;
        this.shopScope = shopScope;
        this.catalog = catalog;
        this.inventory = inventory;
        this.variants = variants;
        this.deliveryFees = deliveryFees;
        this.schedule = schedule;
        this.ratings = ratings;
        this.reliability = reliability;
    }

    /**
     * Every shop within reach that lists this variant, with its final price.
     *
     * <p>SHOPS THAT DO NOT LIST IT ARE LEFT OUT ENTIRELY, which is §10's
     * other half: "if the product is not sold by the shop, do not show it in
     * that shop's storefront". A comparison row saying "Shop C: not sold
     * here" is a row of noise on a screen whose job is to compare prices.
     *
     * <p>A SHOP THAT LISTS IT AND HAS NONE IS KEPT, with inStock false and
     * no price - because "they normally have it, they are out today" is
     * information a customer wants, and it is exactly what §10 asks the
     * storefront to show.
     */
    @Transactional(readOnly = true)
    public List<ShopOffer> forVariant(Long variantId, Double lat, Double lng, BigDecimal radiusKm) {
        ProductVariant variant = variantId == null ? null
                : variants.findById(variantId).orElse(null);
        if (variant == null) {
            return List.of();
        }
        Long productId = variant.getProduct() == null ? null : variant.getProduct().getId();

        List<ShopDiscovery.NearbyShop> nearby = radiusKm == null
                ? discovery.shopsServing(lat, lng)
                : discovery.searchOutwards(lat, lng, radiusKm).shops();

        List<ShopOffer> offers = new ArrayList<>();
        for (ShopDiscovery.NearbyShop near : nearby) {
            ShopOffer offer = offerFrom(near, variant, productId);
            if (offer != null) {
                offers.add(offer);
            }
        }
        return List.copyOf(offers);
    }

    /** Null when this shop does not list the variant at all. */
    private ShopOffer offerFrom(ShopDiscovery.NearbyShop near, ProductVariant variant, Long productId) {
        Shop shop = near.shop();
        return shopScope.within(shop.getId(), () -> {

            ShopProductVariant listing = catalog.listingFor(variant.getId()).orElse(null);
            if (listing == null) {
                return null;
            }

            int held = stockOf(variant.getId());
            boolean inStock = held > 0;
            boolean orderable = listing.isOrderable();

            StoreStatus status = schedule.getStoreStatus();
            ShopRatingSummary rating = ratings.summary();

            // §10: NO PRICE ON AN EMPTY SHELF. The number is withheld here,
            // in the response, rather than by a screen choosing not to draw
            // it - so every client gets the same answer and none of them can
            // show a price for something that cannot be bought.
            BigDecimal price = inStock && orderable ? listing.getSellingPrice() : null;
            BigDecimal mrp = inStock && orderable ? listing.getMrp() : null;
            BigDecimal discount = price != null && mrp != null && mrp.compareTo(price) > 0
                    ? mrp.subtract(price) : BigDecimal.ZERO;

            BigDecimal delivery = null;
            boolean deliveryKnown = false;
            if (near.deliversHere()) {
                try {
                    delivery = deliveryFees.calculateDeliveryFee(near.distanceKm());
                    deliveryKnown = delivery != null;
                } catch (RuntimeException cannotPrice) {
                    // A SHOP THAT CANNOT QUOTE IS NOT A FREE ONE. Swallowing
                    // this to zero would put it top of a Best Deal list on a
                    // number nobody gave; the flag is what keeps it honest.
                    deliveryKnown = false;
                    delivery = null;
                }
            }

            BigDecimal finalPayable = price == null || !deliveryKnown
                    ? null
                    : price.add(delivery);

            return new ShopOffer(
                    shop.getId(), shop.getDisplayName(), shop.getLogoUrl(),
                    near.distanceKm(), near.deliversHere(),
                    status.browsingOpen(), status.acceptingOrders(),
                    shop.getVerificationLevel(), shop.getVerificationLevel().badge(),
                    reliability.isTrusted(),
                    rating.average(), rating.count(),
                    productId, variant.getId(),
                    true, inStock,
                    price, mrp, discount,
                    delivery, deliveryKnown,
                    finalPayable,
                    status.deliveryDate());
        });
    }

    private int stockOf(Long variantId) {
        for (Object[] row : inventory.findStockByProductVariantIds(List.of(variantId))) {
            return row[1] == null ? 0 : ((Number) row[1]).intValue();
        }
        return 0;
    }
}
