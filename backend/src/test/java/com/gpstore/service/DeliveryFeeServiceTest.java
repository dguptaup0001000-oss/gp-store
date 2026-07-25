package com.gpstore.service;

import com.gpstore.entity.Cart;
import com.gpstore.entity.CartItem;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryFeeServiceTest {

    // base=15 up to 2km, then 3/km beyond, free-delivery factor K=3 - the
    // exact rule you specified.
    private final DeliveryFeeService service =
            new DeliveryFeeService("15", "2", "3", "3");

    @Test
    void feeIsFlatBaseChargeWithinFreeDistance() {
        assertEquals(new BigDecimal("15"), service.calculateDeliveryFee(1.5));
        assertEquals(new BigDecimal("15"), service.calculateDeliveryFee(2.0));
    }

    @Test
    void feeAddsPerKmChargeBeyondBaseDistance() {
        // 6km: 15 + (6-2)*3 = 27 - matches the handwritten example exactly.
        assertEquals(new BigDecimal("27.00"), service.calculateDeliveryFee(6.0));
    }

    @Test
    void feeForThreeKm() {
        // 3km: 15 + (3-2)*3 = 18
        assertEquals(new BigDecimal("18.00"), service.calculateDeliveryFee(3.0));
    }

    @Test
    void missingCostPriceContributesZeroProfitNotAGuess() {
        CartItem item = itemWithPrices(new BigDecimal("100"), null, 1);
        BigDecimal profit = service.calculateGrossProfit(List.of(item));
        assertEquals(BigDecimal.ZERO, profit);
    }

    @Test
    void grossProfitIsSumOfPerUnitMargins() {
        CartItem item = itemWithPrices(new BigDecimal("120"), new BigDecimal("90"), 2);
        // (120-90) * 2 = 60
        assertEquals(new BigDecimal("60"), service.calculateGrossProfit(List.of(item)));
    }

    @Test
    void freeDeliveryEligibleWhenProfitAtLeastTripleFee() {
        // profit=120, fee=35 -> needs profit >= 105 -> eligible (matches your Example 1)
        assertTrue(service.isFreeDeliveryEligible(new BigDecimal("120"), new BigDecimal("35")));
    }

    @Test
    void freeDeliveryNotEligibleWhenProfitBelowThreshold() {
        // profit=90, fee=35 -> needs profit >= 105 -> NOT eligible (matches your Example 2)
        assertFalse(service.isFreeDeliveryEligible(new BigDecimal("90"), new BigDecimal("35")));
    }

    private CartItem itemWithPrices(BigDecimal sellingPrice, BigDecimal costPrice, int quantity) {
        ProductVariant variant = new ProductVariant();
        variant.setSellingPrice(sellingPrice);
        variant.setCostPrice(costPrice);
        variant.setProduct(new Product());

        CartItem item = new CartItem();
        item.setProductVariant(variant);
        item.setQuantity(quantity);
        item.setCart(new Cart());
        return item;
    }
}
