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

    // Admin only - couponCode and usedCount are intentionally not editable
    // here (see CouponService.update's doc comment).
    @PutMapping("/{id}")
    public Coupon updateCoupon(@PathVariable Long id, @RequestBody Coupon coupon) {
        return couponService.update(id, coupon);
    }

    // Admin only - soft delete, same reasoning as Category/Product.
    @DeleteMapping("/{id}")
    public String deactivateCoupon(@PathVariable Long id) {
        couponService.deactivate(id);
        return "Coupon deactivated";
    }

    // Public - the actual "Offers" banner data. Only ever returns coupons
    // that are genuinely usable right now (active, not expired, not
    // exhausted) - never a stale or dead offer.
    @GetMapping("/active")
    public List<Coupon> getActiveCoupons() {
        return couponService.getActiveCoupons();
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
