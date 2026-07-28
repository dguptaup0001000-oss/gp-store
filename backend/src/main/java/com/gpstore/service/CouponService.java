package com.gpstore.service;

import com.gpstore.entity.Coupon;
import com.gpstore.enums.DiscountType;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.CouponRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

@Service
public class CouponService {

    private final CouponRepository couponRepository;
    private final AuditLogService auditLogService;

    public CouponService(CouponRepository couponRepository, AuditLogService auditLogService) {
        this.couponRepository = couponRepository;
        this.auditLogService = auditLogService;
    }

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
    public void deactivate(Long id) {
        Coupon coupon = getByIdOrThrow(id);
        coupon.setActive(false);
        couponRepository.save(coupon);
        auditLogService.log("COUPON_DEACTIVATED", "Coupon", coupon.getId(), "code=" + coupon.getCouponCode());
    }

    public List<Coupon> getAllCoupons() {
        return couponRepository.findAll();
    }

    /**
     * The public "what offers exist right now" list - genuinely active AND
     * not expired. This didn't exist before; coupons were 100% admin-only,
     * meaning a customer could never actually see what offers were available
     * without already knowing a code - no real storefront can show an
     * "Offers" banner without this.
     */
    public List<Coupon> getActiveCoupons() {
        return couponRepository.findAll().stream()
                .filter(c -> Boolean.TRUE.equals(c.getActive()))
                .filter(c -> c.getExpiryDate() == null || !c.getExpiryDate().isBefore(java.time.LocalDate.now()))
                .filter(c -> c.getUsageLimit() == null || c.getUsageLimit() <= 0
                        || c.getUsedCount() == null || c.getUsedCount() < c.getUsageLimit())
                .toList();
    }

    /** Read-only check used to preview a discount before checkout - does not consume a usage slot. */
    public BigDecimal previewDiscount(String couponCode, BigDecimal orderAmount) {
        Coupon coupon = couponRepository.findByCouponCodeIgnoreCase(couponCode)
                .orElseThrow(() -> new ResourceNotFoundException("Coupon not found"));
        validate(coupon, orderAmount);
        return calculateDiscount(coupon, orderAmount);
    }

    /**
     * Locks the coupon row, re-validates, computes the discount, and increments
     * usedCount - all inside the caller's transaction. This is what prevents two
     * concurrent checkouts from both squeezing past a limited-use coupon.
     */
    @Transactional
    public BigDecimal redeem(String couponCode, BigDecimal orderAmount) {
        Coupon coupon = couponRepository.findByCouponCodeForUpdate(couponCode)
                .orElseThrow(() -> new ResourceNotFoundException("Coupon not found"));

        validate(coupon, orderAmount);

        BigDecimal discount = calculateDiscount(coupon, orderAmount);

        coupon.setUsedCount(coupon.getUsedCount() == null ? 1 : coupon.getUsedCount() + 1);
        couponRepository.save(coupon);

        return discount;
    }

    private void validate(Coupon coupon, BigDecimal orderAmount) {
        if (Boolean.FALSE.equals(coupon.getActive())) {
            throw new BadRequestException("This coupon is no longer active");
        }

        if (coupon.getExpiryDate() != null && coupon.getExpiryDate().isBefore(LocalDate.now())) {
            throw new BadRequestException("This coupon has expired");
        }

        if (coupon.getUsageLimit() != null && coupon.getUsageLimit() > 0
                && coupon.getUsedCount() != null && coupon.getUsedCount() >= coupon.getUsageLimit()) {
            throw new BadRequestException("This coupon has reached its usage limit");
        }

        if (coupon.getMinimumOrderAmount() != null
                && orderAmount.compareTo(coupon.getMinimumOrderAmount()) < 0) {
            throw new BadRequestException(
                    "This coupon requires a minimum order of " + coupon.getMinimumOrderAmount());
        }
    }

    private BigDecimal calculateDiscount(Coupon coupon, BigDecimal orderAmount) {
        BigDecimal discount;

        if (coupon.getDiscountType() == DiscountType.FLAT) {
            discount = coupon.getDiscountValue();
        } else {
            discount = orderAmount
                    .multiply(coupon.getDiscountValue())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            if (coupon.getMaxDiscountAmount() != null
                    && discount.compareTo(coupon.getMaxDiscountAmount()) > 0) {
                discount = coupon.getMaxDiscountAmount();
            }
        }

        // Never discount more than the order is worth.
        if (discount.compareTo(orderAmount) > 0) {
            discount = orderAmount;
        }

        return discount;
    }
}
