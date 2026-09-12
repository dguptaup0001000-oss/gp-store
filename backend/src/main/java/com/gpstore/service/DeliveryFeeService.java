package com.gpstore.service;

import com.gpstore.entity.CartItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class DeliveryFeeService {

    private final BigDecimal baseCharge;
    private final BigDecimal baseDistanceKm;
    private final BigDecimal perKmCharge;
    private final BigDecimal freeDeliveryProfitFactor;
    private final com.gpstore.catalog.shop.ShopCatalog shopCatalog;

    public DeliveryFeeService(
            @Value("${delivery.base-charge}") String baseCharge,
            @Value("${delivery.base-distance-km}") String baseDistanceKm,
            @Value("${delivery.per-km-charge}") String perKmCharge,
            @Value("${delivery.free-delivery-profit-factor}") String freeDeliveryProfitFactor,
            com.gpstore.catalog.shop.ShopCatalog shopCatalog) {
        this.baseCharge = new BigDecimal(baseCharge);
        this.baseDistanceKm = new BigDecimal(baseDistanceKm);
        this.perKmCharge = new BigDecimal(perKmCharge);
        this.freeDeliveryProfitFactor = new BigDecimal(freeDeliveryProfitFactor);
        this.shopCatalog = shopCatalog;
    }

    /**
     * Delivery fee = base charge (covers up to baseDistanceKm) + per-km charge
     * for every km beyond that. E.g. base=15 up to 2km, then 3/km: a 6km
     * delivery = 15 + (6-2)*3 = 27.
     */
    public BigDecimal calculateDeliveryFee(double distanceKm) {
        BigDecimal distance = BigDecimal.valueOf(distanceKm);

        if (distance.compareTo(baseDistanceKm) <= 0) {
            return baseCharge;
        }

        BigDecimal extraKm = distance.subtract(baseDistanceKm);
        BigDecimal extraCharge = extraKm.multiply(perKmCharge).setScale(2, RoundingMode.HALF_UP);

        return baseCharge.add(extraCharge);
    }

    /**
     * Gross profit for this cart = sum of (sellingPrice - costPrice) * quantity.
     * A variant with no costPrice set contributes ZERO profit here rather than
     * being skipped or assumed profitable - missing cost data should never
     * accidentally qualify an order for free delivery.
     */
    public BigDecimal calculateGrossProfit(List<CartItem> cartItems) {
        BigDecimal totalProfit = BigDecimal.ZERO;

        // PROFIT IS THIS SHOP'S PROFIT. Free delivery is funded out of the
        // margin the shop actually made, so both halves come from the shop's
        // own listing when it has one, and from the catalogue only as the
        // single-shop fallback ShopCatalog documents.
        java.util.Map<Long, com.gpstore.catalog.shop.ShopProductVariant> listings =
                shopCatalog.listingsFor(cartItems.stream()
                        .map(i -> i.getProductVariant() == null ? null : i.getProductVariant().getId())
                        .toList());

        for (CartItem item : cartItems) {
            com.gpstore.catalog.shop.ShopProductVariant listing =
                    item.getProductVariant() == null ? null : listings.get(item.getProductVariant().getId());
            BigDecimal sellingPrice = listing != null
                    ? listing.getSellingPrice() : item.getProductVariant().getSellingPrice();
            BigDecimal costPrice = listing != null && listing.getCostPrice() != null
                    ? listing.getCostPrice() : item.getProductVariant().getCostPrice();

            if (sellingPrice == null || costPrice == null) {
                continue; // unknown cost -> treat as zero profit for this item, not a guess
            }

            BigDecimal perUnitProfit = sellingPrice.subtract(costPrice);
            totalProfit = totalProfit.add(perUnitProfit.multiply(BigDecimal.valueOf(item.getQuantity())));
        }

        return totalProfit;
    }

    /**
     * Business rule: only waive the delivery fee if gross profit is at least
     * K times the delivery cost (K = free-delivery-profit-factor, default 3).
     * This guarantees the business keeps a fixed minimum share of the profit
     * on every free-delivery order instead of it all going to delivery cost.
     */
    public boolean isFreeDeliveryEligible(BigDecimal grossProfit, BigDecimal deliveryFee) {
        BigDecimal requiredProfit = deliveryFee.multiply(freeDeliveryProfitFactor);
        return grossProfit.compareTo(requiredProfit) >= 0;
    }
}
