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

    /**
     * One row per shop in the basket (§16).
     *
     * A BASKET SPANNING TWO KIRANAS IS TWO DELIVERIES. Showing one blended
     * fee would hide which half is expensive and why - and, worse, would let a
     * customer commit without knowing that one of the two shops cannot reach
     * them. The totals above are the sum of these, so a client that has never
     * heard of the breakdown still shows the right numbers.
     */
    private final java.util.List<ShopBreakdown> shops;

    /**
     * What one shop will charge for its half.
     *
     * itemCount is that shop's lines, not the basket's - it is what tells a
     * customer which shop the delivery fee they are looking at belongs to.
     */
    public record ShopBreakdown(Long shopId, String shopName, int itemCount,
                                BigDecimal subtotal, BigDecimal discountAmount,
                                BigDecimal deliveryFee, BigDecimal estimatedTotal,
                                boolean freeDeliveryApplied, boolean deliverable,
                                Integer estimatedDeliveryMinutes, String couponError) {}

    public java.util.List<ShopBreakdown> getShops() { return shops; }

    /** Kept for callers written before the basket could span shops. */
    public CheckoutPreviewResponse(BigDecimal subtotal, BigDecimal discountAmount, BigDecimal deliveryFee,
                                    BigDecimal estimatedTotal, boolean freeDeliveryApplied, boolean deliverable,
                                    Integer estimatedDeliveryMinutes, String couponError) {
        this(subtotal, discountAmount, deliveryFee, estimatedTotal, freeDeliveryApplied, deliverable,
                estimatedDeliveryMinutes, couponError, java.util.List.of());
    }

    public CheckoutPreviewResponse(BigDecimal subtotal, BigDecimal discountAmount, BigDecimal deliveryFee,
                                    BigDecimal estimatedTotal, boolean freeDeliveryApplied, boolean deliverable,
                                    Integer estimatedDeliveryMinutes, String couponError,
                                    java.util.List<ShopBreakdown> shops) {
        this.shops = shops == null ? java.util.List.of() : java.util.List.copyOf(shops);
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
