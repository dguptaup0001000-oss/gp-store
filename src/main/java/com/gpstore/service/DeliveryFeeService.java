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

    public DeliveryFeeService(
            @Value("${delivery.base-charge}") String baseCharge,
            @Value("${delivery.base-distance-km}") String baseDistanceKm,
            @Value("${delivery.per-km-charge}") String perKmCharge,
            @Value("${delivery.free-delivery-profit-factor}") String freeDeliveryProfitFactor) {
        this.baseCharge = new BigDecimal(baseCharge);
        this.baseDistanceKm = new BigDecimal(baseDistanceKm);
        this.perKmCharge = new BigDecimal(perKmCharge);
        this.freeDeliveryProfitFactor = new BigDecimal(freeDeliveryProfitFactor);
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

        for (CartItem item : cartItems) {
            BigDecimal sellingPrice = item.getProductVariant().getSellingPrice();
            BigDecimal costPrice = item.getProductVariant().getCostPrice();

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
