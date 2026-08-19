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

    /**
     * Batched bulk delete of long-expired OTP rows.
     *
     * The previous cleanup loaded every expired row into memory and deleted
     * them one by one. OTP volume grows with signups and login attempts, and
     * if this job is ever paused (a deploy, an outage) the backlog is
     * whatever accumulated meanwhile - exactly when loading it all at once
     * is worst.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            value = "DELETE FROM otp_verifications WHERE id IN ("
                    + "SELECT id FROM otp_verifications WHERE expires_at < :cutoff "
                    + "ORDER BY id LIMIT :batchSize)", nativeQuery = true)
    int deleteExpiredBatch(@org.springframework.data.repository.query.Param("cutoff") java.time.LocalDateTime cutoff,
                           @org.springframework.data.repository.query.Param("batchSize") int batchSize);
}
