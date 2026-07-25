package com.gpstore.controller;

import com.gpstore.entity.Coupon;
import com.gpstore.service.CouponService;

import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/coupons")
public class CouponController {

    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    // Admin only (enforced in SecurityConfig).
    @PostMapping
    public Coupon createCoupon(@RequestBody Coupon coupon) {
        return couponService.saveCoupon(coupon);
    }

    // Admin only (enforced in SecurityConfig).
    @GetMapping
    public List<Coupon> getAllCoupons() {
        return couponService.getAllCoupons();
    }

    // Any logged-in customer can preview a discount before checkout.
    // This does NOT consume a usage slot - only placeOrder() does that.
    @GetMapping("/validate")
    public Map<String, Object> validateCoupon(
            @RequestParam String code,
            @RequestParam BigDecimal orderAmount) {

        BigDecimal discount = couponService.previewDiscount(code, orderAmount);

        return Map.of(
                "valid", true,
                "couponCode", code.toUpperCase(),
                "discountAmount", discount,
                "finalAmount", orderAmount.subtract(discount)
        );
    }
}
