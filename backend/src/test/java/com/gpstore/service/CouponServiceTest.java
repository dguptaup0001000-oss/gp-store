package com.gpstore.service;

import com.gpstore.entity.Coupon;
import com.gpstore.enums.DiscountType;
import com.gpstore.exception.BadRequestException;
import com.gpstore.repository.CouponRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private AuditLogService auditLogService;

    private CouponService couponService;

    @BeforeEach
    void setUp() {
        couponService = new CouponService(couponRepository, auditLogService);
    }

    @Test
    void flatDiscountAppliesDirectly() {
        Coupon coupon = flatCoupon("SAVE50", new BigDecimal("50"));
        when(couponRepository.findByCouponCodeIgnoreCase("SAVE50")).thenReturn(Optional.of(coupon));

        BigDecimal discount = couponService.previewDiscount("SAVE50", new BigDecimal("500"));

        assertEquals(new BigDecimal("50"), discount);
    }

    @Test
    void flatCouponDoesNotChangeTheQuotedDeliveryFee() {
        Coupon coupon = flatCoupon("SAVE50", new BigDecimal("50"));
        when(couponRepository.findByCouponCodeIgnoreCase("SAVE50")).thenReturn(Optional.of(coupon));

        AppliedCoupon applied = couponService.preview("SAVE50", new BigDecimal("500"), new BigDecimal("20"));

        assertEquals(new BigDecimal("50"), applied.merchandiseDiscount());
        assertEquals(0, applied.deliveryDiscount().compareTo(BigDecimal.ZERO));
        assertEquals(0, applied.deliveryFeeDue(new BigDecimal("20")).compareTo(new BigDecimal("20")));
    }

    @Test
    void percentageDiscountIsCappedByMaxDiscountAmount() {
        Coupon coupon = new Coupon();
        coupon.setCouponCode("BIG50");
        coupon.setDiscountType(DiscountType.PERCENTAGE);
        coupon.setDiscountValue(new BigDecimal("50")); // 50% off
        coupon.setMaxDiscountAmount(new BigDecimal("40")); // but capped at 40
        coupon.setActive(true);
        when(couponRepository.findByCouponCodeIgnoreCase("BIG50")).thenReturn(Optional.of(coupon));

        // 50% of 200 = 100, but the cap of 40 should win.
        BigDecimal discount = couponService.previewDiscount("BIG50", new BigDecimal("200"));

        assertEquals(new BigDecimal("40.00"), discount);
    }

    @Test
    void discountNeverExceedsOrderAmount() {
        Coupon coupon = flatCoupon("HUGE", new BigDecimal("1000"));
        when(couponRepository.findByCouponCodeIgnoreCase("HUGE")).thenReturn(Optional.of(coupon));

        // Order is only worth 50 - a 1000 flat discount should never make the order negative.
        BigDecimal discount = couponService.previewDiscount("HUGE", new BigDecimal("50"));

        assertEquals(new BigDecimal("50"), discount);
    }

    @Test
    void unknownCouponDoesNotRevealWhetherTheCodeExists() {
        when(couponRepository.findByCouponCodeIgnoreCase("NOPE")).thenReturn(Optional.empty());

        BadRequestException thrown = assertThrows(BadRequestException.class,
                () -> couponService.previewDiscount("NOPE", new BigDecimal("100")));

        assertEquals(CouponService.GENERIC_INVALID_COUPON, thrown.getMessage());
        assertFalse(thrown.getMessage().toLowerCase().contains("not found"));
    }

    @Test
    void expiredCouponIsRejected() {
        Coupon coupon = flatCoupon("OLD10", new BigDecimal("10"));
        coupon.setExpiryDate(LocalDate.now().minusDays(1));
        when(couponRepository.findByCouponCodeIgnoreCase("OLD10")).thenReturn(Optional.of(coupon));

        assertThrows(BadRequestException.class,
                () -> couponService.previewDiscount("OLD10", new BigDecimal("100")));
    }

    @Test
    void inactiveCouponIsRejected() {
        Coupon coupon = flatCoupon("DEAD", new BigDecimal("10"));
        coupon.setActive(false);
        when(couponRepository.findByCouponCodeIgnoreCase("DEAD")).thenReturn(Optional.of(coupon));

        assertThrows(BadRequestException.class,
                () -> couponService.previewDiscount("DEAD", new BigDecimal("100")));
    }

    @Test
    void belowMinimumOrderAmountIsRejected() {
        Coupon coupon = flatCoupon("MIN200", new BigDecimal("20"));
        coupon.setMinimumOrderAmount(new BigDecimal("200"));
        when(couponRepository.findByCouponCodeIgnoreCase("MIN200")).thenReturn(Optional.of(coupon));

        assertThrows(BadRequestException.class,
                () -> couponService.previewDiscount("MIN200", new BigDecimal("100")));
    }

    @Test
    void usageLimitReachedIsRejected() {
        Coupon coupon = flatCoupon("LIMIT1", new BigDecimal("10"));
        coupon.setUsageLimit(1);
        coupon.setUsedCount(1);
        when(couponRepository.findByCouponCodeIgnoreCase("LIMIT1")).thenReturn(Optional.of(coupon));

        assertThrows(BadRequestException.class,
                () -> couponService.previewDiscount("LIMIT1", new BigDecimal("100")));
    }

    @Test
    void deliveryFlatTakesUpToTenRupeesOffDelivery() {
        Coupon coupon = deliveryCoupon("FREEDEL10", new BigDecimal("10"));
        when(couponRepository.findByCouponCodeIgnoreCase("FREEDEL10")).thenReturn(Optional.of(coupon));

        AppliedCoupon ten = couponService.preview("FREEDEL10", new BigDecimal("500"), new BigDecimal("10"));
        assertEquals(0, ten.merchandiseDiscount().compareTo(BigDecimal.ZERO));
        assertEquals(0, ten.deliveryDiscount().compareTo(new BigDecimal("10")));
        assertEquals(0, ten.deliveryFeeDue(new BigDecimal("10")).compareTo(BigDecimal.ZERO));

        AppliedCoupon twenty = couponService.preview("FREEDEL10", new BigDecimal("500"), new BigDecimal("20"));
        assertEquals(0, twenty.deliveryDiscount().compareTo(new BigDecimal("10")));
        assertEquals(0, twenty.deliveryFeeDue(new BigDecimal("20")).compareTo(new BigDecimal("10")));
    }

    @Test
    void deliveryFlatDoesNotReduceMerchandise() {
        Coupon coupon = deliveryCoupon("FREEDEL10", new BigDecimal("10"));
        when(couponRepository.findByCouponCodeIgnoreCase("FREEDEL10")).thenReturn(Optional.of(coupon));

        AppliedCoupon applied = couponService.preview("FREEDEL10", new BigDecimal("500"), new BigDecimal("20"));

        assertEquals(0, applied.merchandiseDiscount().compareTo(BigDecimal.ZERO));
        assertEquals(0, applied.deliveryFeeDue(new BigDecimal("20")).compareTo(new BigDecimal("10")));
    }

    @Test
    void deliveryFlatOnAlreadyFreeDeliveryIsANoOp() {
        Coupon coupon = deliveryCoupon("FREEDEL10", new BigDecimal("10"));
        when(couponRepository.findByCouponCodeIgnoreCase("FREEDEL10")).thenReturn(Optional.of(coupon));

        AppliedCoupon applied = couponService.preview("FREEDEL10", new BigDecimal("500"), BigDecimal.ZERO);

        assertEquals(0, applied.deliveryDiscount().compareTo(BigDecimal.ZERO));
        assertEquals(0, applied.deliveryFeeDue(BigDecimal.ZERO).compareTo(BigDecimal.ZERO));
    }

    @Test
    void deliveryFlatStillRequiresTheMinimumCartTotal() {
        Coupon coupon = deliveryCoupon("FREEDEL10", new BigDecimal("10"));
        coupon.setMinimumOrderAmount(new BigDecimal("200"));
        when(couponRepository.findByCouponCodeIgnoreCase("FREEDEL10")).thenReturn(Optional.of(coupon));

        assertThrows(BadRequestException.class,
                () -> couponService.preview("FREEDEL10", new BigDecimal("100"), new BigDecimal("20")));
    }

    @Test
    void activeListUsesTheOfferQueryNotFindAll() {
        when(couponRepository.findCurrentlyOfferable(LocalDate.now())).thenReturn(List.of());

        assertTrue(couponService.getActiveCoupons().isEmpty());

        verify(couponRepository).findCurrentlyOfferable(LocalDate.now());
        verify(couponRepository, never()).findAll();
    }

    private Coupon flatCoupon(String code, BigDecimal value) {
        Coupon coupon = new Coupon();
        coupon.setCouponCode(code);
        coupon.setDiscountType(DiscountType.FLAT);
        coupon.setDiscountValue(value);
        coupon.setActive(true);
        coupon.setUsedCount(0);
        return coupon;
    }

    private Coupon deliveryCoupon(String code, BigDecimal value) {
        Coupon coupon = new Coupon();
        coupon.setCouponCode(code);
        coupon.setDiscountType(DiscountType.DELIVERY_FLAT);
        coupon.setDiscountValue(value);
        coupon.setActive(true);
        coupon.setUsedCount(0);
        return coupon;
    }
}
