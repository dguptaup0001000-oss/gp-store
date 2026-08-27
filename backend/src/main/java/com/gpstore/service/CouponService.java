package com.gpstore.service;

import com.gpstore.entity.Coupon;
import com.gpstore.enums.DiscountType;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.CouponRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

@Service
public class CouponService {

    /**
     * Customer-facing validation must not distinguish "this code does not
     * exist" from "this code exists but cannot be used". That distinction is
     * how an attacker harvests live offer codes. Admin getByIdOrThrow keeps
     * the specific not-found message because staff already know the ids.
     */
    static final String GENERIC_INVALID_COUPON = "This coupon cannot be applied.";

    private final CouponRepository couponRepository;
    private final AuditLogService auditLogService;

    public CouponService(CouponRepository couponRepository, AuditLogService auditLogService) {
        this.couponRepository = couponRepository;
        this.auditLogService = auditLogService;
    }

    @CacheEvict(value = "activeCoupons", allEntries = true)
    public Coupon saveCoupon(Coupon coupon) {
        if (coupon.getUsedCount() == null) {
            coupon.setUsedCount(0);
        }
        if (coupon.getActive() == null) {
            coupon.setActive(true);
        }
        Coupon saved = couponRepository.save(coupon);
        auditLogService.log("COUPON_SAVED", "Coupon", saved.getId(),
                "code=" + saved.getCouponCode() + ", type=" + saved.getDiscountType() + ", value=" + saved.getDiscountValue());
        return saved;
    }

    public Coupon getByIdOrThrow(Long id) {
        return couponRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Coupon not found"));
    }

    /** Didn't exist before - a coupon could be created but never edited afterward. */
    @CacheEvict(value = "activeCoupons", allEntries = true)
    public Coupon update(Long id, Coupon updated) {
        Coupon existing = getByIdOrThrow(id);

        existing.setDiscountType(updated.getDiscountType());
        existing.setDiscountValue(updated.getDiscountValue());
        existing.setMaxDiscountAmount(updated.getMaxDiscountAmount());
        existing.setMinimumOrderAmount(updated.getMinimumOrderAmount());
        existing.setExpiryDate(updated.getExpiryDate());
        existing.setUsageLimit(updated.getUsageLimit());
        existing.setActive(updated.getActive());
        // Deliberately NOT copying couponCode or usedCount - the code is
        // effectively the coupon's identity (customers may already have it
        // written down/bookmarked), and usedCount is a system-tracked
        // counter that must never be reset by an edit form.

        Coupon saved = couponRepository.save(existing);
        auditLogService.log("COUPON_UPDATED", "Coupon", saved.getId(), "code=" + saved.getCouponCode());
        return saved;
    }

    /** Soft-delete only - a hard delete would break every past order that references this coupon by code. */
    @CacheEvict(value = "activeCoupons", allEntries = true)
    public void deactivate(Long id) {
        Coupon coupon = getByIdOrThrow(id);
        coupon.setActive(false);
        couponRepository.save(coupon);
        auditLogService.log("COUPON_DEACTIVATED", "Coupon", coupon.getId(), "code=" + coupon.getCouponCode());
    }

    public List<Coupon> getAllCoupons(Pageable pageable) {
        return couponRepository.findAll(pageable).getContent();
    }

    /**
     * The public "what offers exist right now" list - genuinely active AND
     * not expired. This didn't exist before; coupons were 100% admin-only,
     * meaning a customer could never actually see what offers were available
     * without already knowing a code - no real storefront can show an
     * "Offers" banner without this.
     */
    @Cacheable(value = "activeCoupons", sync = true)
    public List<Coupon> getActiveCoupons() {
        return couponRepository.findCurrentlyOfferable(LocalDate.now());
    }

    /** Read-only check used to preview a discount before checkout - does not consume a usage slot. */
    public BigDecimal previewDiscount(String couponCode, BigDecimal orderAmount) {
        return preview(couponCode, orderAmount, BigDecimal.ZERO).merchandiseDiscount();
    }

    /**
     * Preview merchandise and/or delivery reduction. {@code deliveryFee} is
     * the quoted charge before this coupon; DELIVERY_FLAT knocks up to
     * {@code discountValue} off that number and leaves the cart subtotal
     * alone.
     */
    public AppliedCoupon preview(String couponCode, BigDecimal merchandiseSubtotal, BigDecimal deliveryFee) {
        Coupon coupon = couponRepository.findByCouponCodeIgnoreCase(couponCode)
                .orElseThrow(() -> new BadRequestException(GENERIC_INVALID_COUPON));
        validate(coupon, merchandiseSubtotal);
        return calculate(coupon, merchandiseSubtotal, deliveryFee);
    }

    /**
     * Locks the coupon row, re-validates, computes the discount, and increments
     * usedCount - all inside the caller's transaction. This is what prevents two
     * concurrent checkouts from both squeezing past a limited-use coupon.
     */
    @CacheEvict(value = "activeCoupons", allEntries = true)
    @Transactional
    public BigDecimal redeem(String couponCode, BigDecimal orderAmount) {
        return redeem(couponCode, orderAmount, BigDecimal.ZERO).merchandiseDiscount();
    }

    @CacheEvict(value = "activeCoupons", allEntries = true)
    @Transactional
    public AppliedCoupon redeem(String couponCode, BigDecimal merchandiseSubtotal, BigDecimal deliveryFee) {
        Coupon coupon = couponRepository.findByCouponCodeForUpdate(couponCode)
                .orElseThrow(() -> new BadRequestException(GENERIC_INVALID_COUPON));

        validate(coupon, merchandiseSubtotal);

        AppliedCoupon applied = calculate(coupon, merchandiseSubtotal, deliveryFee);

        coupon.setUsedCount(coupon.getUsedCount() == null ? 1 : coupon.getUsedCount() + 1);
        couponRepository.save(coupon);

        return applied;
    }

    private void validate(Coupon coupon, BigDecimal orderAmount) {
        if (Boolean.FALSE.equals(coupon.getActive())) {
            throw new BadRequestException(GENERIC_INVALID_COUPON);
        }

        if (coupon.getExpiryDate() != null && coupon.getExpiryDate().isBefore(LocalDate.now())) {
            throw new BadRequestException(GENERIC_INVALID_COUPON);
        }

        if (coupon.getUsageLimit() != null && coupon.getUsageLimit() > 0
                && coupon.getUsedCount() != null && coupon.getUsedCount() >= coupon.getUsageLimit()) {
            throw new BadRequestException(GENERIC_INVALID_COUPON);
        }

        // Minimum-order is the one failure that is useful to a shopper who
        // already has a real code, and it does not confirm existence of an
        // unknown code (that path already returned GENERIC_INVALID_COUPON).
        if (coupon.getMinimumOrderAmount() != null
                && orderAmount.compareTo(coupon.getMinimumOrderAmount()) < 0) {
            throw new BadRequestException(
                    "This coupon requires a minimum order of " + coupon.getMinimumOrderAmount());
        }
    }

    private AppliedCoupon calculate(Coupon coupon, BigDecimal merchandiseSubtotal, BigDecimal deliveryFee) {
        BigDecimal subtotal = merchandiseSubtotal == null ? BigDecimal.ZERO : merchandiseSubtotal;
        BigDecimal quotedDelivery = deliveryFee == null ? BigDecimal.ZERO : deliveryFee;
        if (quotedDelivery.signum() < 0) {
            quotedDelivery = BigDecimal.ZERO;
        }

        if (coupon.getDiscountType() == DiscountType.DELIVERY_FLAT) {
            BigDecimal cap = coupon.getDiscountValue() == null ? BigDecimal.ZERO : coupon.getDiscountValue();
            if (cap.signum() < 0) {
                cap = BigDecimal.ZERO;
            }
            return new AppliedCoupon(BigDecimal.ZERO, cap.min(quotedDelivery));
        }

        BigDecimal discount;
        if (coupon.getDiscountType() == DiscountType.FLAT) {
            discount = coupon.getDiscountValue();
        } else {
            discount = subtotal
                    .multiply(coupon.getDiscountValue())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            if (coupon.getMaxDiscountAmount() != null
                    && discount.compareTo(coupon.getMaxDiscountAmount()) > 0) {
                discount = coupon.getMaxDiscountAmount().setScale(2, RoundingMode.HALF_UP);
            }
        }

        // Never discount more than the merchandise is worth.
        if (discount.compareTo(subtotal) > 0) {
            discount = subtotal;
        }

        return new AppliedCoupon(discount, BigDecimal.ZERO);
    }
}
