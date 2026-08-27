package com.gpstore.controller;

import com.gpstore.config.PageRequests;
import com.gpstore.entity.Coupon;
import com.gpstore.service.AppliedCoupon;
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

    // Admin only (enforced in SecurityConfig). Still a JSON list so the
    // existing admin UI keeps working; page/size cap the query so findAll()
    // cannot dump the table. Default size is the max so a store with a
    // normal number of offers still sees every row on the first request.
    @GetMapping
    public List<Coupon> getAllCoupons(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return couponService.getAllCoupons(PageRequests.of(page, size));
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
            @RequestParam BigDecimal orderAmount,
            @RequestParam(required = false) BigDecimal deliveryFee) {

        AppliedCoupon applied = couponService.preview(
                code, orderAmount, deliveryFee == null ? BigDecimal.ZERO : deliveryFee);
        BigDecimal merchandiseDue = orderAmount.subtract(applied.merchandiseDiscount());

        return Map.of(
                "valid", true,
                "couponCode", code.toUpperCase(),
                "discountAmount", applied.merchandiseDiscount(),
                "deliveryDiscountAmount", applied.deliveryDiscount(),
                "finalAmount", merchandiseDue.add(applied.deliveryFeeDue(
                        deliveryFee == null ? BigDecimal.ZERO : deliveryFee))
        );
    }
}
