package com.gpstore.repository;

import com.gpstore.entity.OtpVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OtpVerificationRepository extends JpaRepository<OtpVerification, Long> {

    Optional<OtpVerification> findFirstByMobileNumberAndVerifiedFalseOrderByCreatedAtDesc(String mobileNumber);

    /** Used to rate-limit OTP sends per phone number - prevents SMS-cost abuse. */
    @Query("select count(o) from OtpVerification o where o.mobileNumber = :mobileNumber and o.createdAt >= :since")
    long countRecentByMobileNumber(@Param("mobileNumber") String mobileNumber, @Param("since") LocalDateTime since);

    List<OtpVerification> findByExpiresAtBefore(LocalDateTime cutoff);
}
