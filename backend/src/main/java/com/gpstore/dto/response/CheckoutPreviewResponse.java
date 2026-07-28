package com.gpstore.dto.response;

import java.math.BigDecimal;

/**
 * Read-only preview of what an order WOULD cost - computed from the same
 * services placeOrder() uses (DeliveryFeeService, CouponService,
 * DeliveryEstimateService), but nothing here is persisted: no coupon usage
 * consumed, no inventory touched, no order created. A real checkout should
 * never surprise someone with delivery fees only after they've committed.
 */
public class CheckoutPreviewResponse {

    private final BigDecimal subtotal;
    private final BigDecimal discountAmount;
    private final BigDecimal deliveryFee;
    private final BigDecimal estimatedTotal;
    private final boolean freeDeliveryApplied;
    private final boolean deliverable;
    private final Integer estimatedDeliveryMinutes;
    private final String couponError;

    public CheckoutPreviewResponse(BigDecimal subtotal, BigDecimal discountAmount, BigDecimal deliveryFee,
                                    BigDecimal estimatedTotal, boolean freeDeliveryApplied, boolean deliverable,
                                    Integer estimatedDeliveryMinutes, String couponError) {
        this.subtotal = subtotal;
        this.discountAmount = discountAmount;
        this.deliveryFee = deliveryFee;
        this.estimatedTotal = estimatedTotal;
        this.freeDeliveryApplied = freeDeliveryApplied;
        this.deliverable = deliverable;
        this.estimatedDeliveryMinutes = estimatedDeliveryMinutes;
        this.couponError = couponError;
    }

    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public BigDecimal getDeliveryFee() { return deliveryFee; }
    public BigDecimal getEstimatedTotal() { return estimatedTotal; }
    public boolean isFreeDeliveryApplied() { return freeDeliveryApplied; }
    public boolean isDeliverable() { return deliverable; }
    public Integer getEstimatedDeliveryMinutes() { return estimatedDeliveryMinutes; }
    public String getCouponError() { return couponError; }
}
