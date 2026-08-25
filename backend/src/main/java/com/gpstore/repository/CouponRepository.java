package com.gpstore.repository;

import com.gpstore.entity.Coupon;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByCouponCodeIgnoreCase(String couponCode);

    /** Locks the coupon row so two concurrent orders can't both squeeze past usageLimit. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coupon c where lower(c.couponCode) = lower(:couponCode)")
    Optional<Coupon> findByCouponCodeForUpdate(String couponCode);

    @Query("select c from Coupon c where c.active = true "
            + "and (c.expiryDate is null or c.expiryDate >= :today) "
            + "and (c.usageLimit is null or c.usageLimit <= 0 "
            + "     or c.usedCount is null or c.usedCount < c.usageLimit)")
    List<Coupon> findCurrentlyOfferable(@Param("today") LocalDate today);
}
