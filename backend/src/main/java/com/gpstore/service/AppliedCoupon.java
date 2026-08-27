package com.gpstore.service;

import java.math.BigDecimal;

/**
 * What a coupon does to checkout: either money off merchandise, or money
 * off the quoted delivery fee, never both for one code today.
 */
public record AppliedCoupon(BigDecimal merchandiseDiscount, BigDecimal deliveryDiscount) {

    public AppliedCoupon {
        if (merchandiseDiscount == null) {
            merchandiseDiscount = BigDecimal.ZERO;
        }
        if (deliveryDiscount == null) {
            deliveryDiscount = BigDecimal.ZERO;
        }
        if (merchandiseDiscount.signum() < 0) {
            merchandiseDiscount = BigDecimal.ZERO;
        }
        if (deliveryDiscount.signum() < 0) {
            deliveryDiscount = BigDecimal.ZERO;
        }
    }

    public static AppliedCoupon none() {
        return new AppliedCoupon(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** What the customer pays for delivery after this coupon. */
    public BigDecimal deliveryFeeDue(BigDecimal quotedFee) {
        BigDecimal quoted = quotedFee == null ? BigDecimal.ZERO : quotedFee;
        BigDecimal due = quoted.subtract(deliveryDiscount);
        return due.signum() < 0 ? BigDecimal.ZERO : due;
    }
}
