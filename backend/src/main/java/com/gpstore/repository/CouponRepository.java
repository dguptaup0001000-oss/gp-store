package com.gpstore.repository;

import com.gpstore.entity.Coupon;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByCouponCodeIgnoreCase(String couponCode);

    /** Locks the coupon row so two concurrent orders can't both squeeze past usageLimit. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coupon c where lower(c.couponCode) = lower(:couponCode)")
    Optional<Coupon> findByCouponCodeForUpdate(String couponCode);
}
