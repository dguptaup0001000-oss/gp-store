package com.gpstore.repository;

import com.gpstore.auth.OtpPurpose;
import com.gpstore.entity.OtpVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OtpVerificationRepository extends JpaRepository<OtpVerification, Long> {

    Optional<OtpVerification> findFirstByMobileNumberAndVerifiedFalseOrderByCreatedAtDesc(String mobileNumber);

    Optional<OtpVerification> findFirstByMobileNumberAndPurposeAndVerifiedFalseAndConsumedAtIsNullOrderByCreatedAtDesc(
            String mobileNumber, OtpPurpose purpose);

    Optional<OtpVerification> findFirstByMobileNumberAndConsumedAtIsNullOrderByLastSentAtDesc(String mobileNumber);

    Optional<OtpVerification> findFirstByMobileNumberOrderByLastSentAtDesc(String mobileNumber);

    @Query("select max(o.lastSentAt) from OtpVerification o where o.mobileNumber = :mobileNumber")
    Optional<LocalDateTime> findLatestSentAt(@Param("mobileNumber") String mobileNumber);

    List<OtpVerification> findByMobileNumberAndVerifiedFalseAndConsumedAtIsNull(String mobileNumber);

    /** Used to rate-limit OTP sends per phone number - prevents SMS-cost abuse. */
    @Query("select count(o) from OtpVerification o where o.mobileNumber = :mobileNumber and o.createdAt >= :since")
    long countRecentByMobileNumber(@Param("mobileNumber") String mobileNumber, @Param("since") LocalDateTime since);

    List<OtpVerification> findByExpiresAtBefore(LocalDateTime cutoff);

    @Modifying
    @Query(value = "UPDATE otp_verifications SET consumed_at = :now "
            + "WHERE mobile_number = :mobileNumber AND purpose = :purpose "
            + "AND consumed_at IS NULL AND verified = false", nativeQuery = true)
    int consumeOpenChallenges(
            @Param("mobileNumber") String mobileNumber,
            @Param("purpose") String purpose,
            @Param("now") LocalDateTime now);

    /**
     * Batched bulk delete of long-expired OTP rows.
     *
     * The previous cleanup loaded every expired row into memory and deleted
     * them one by one. OTP volume grows with signups and login attempts, and
     * if this job is ever paused (a deploy, an outage) the backlog is
     * whatever accumulated meanwhile - exactly when loading it all at once
     * is worst.
     */
    @Modifying
    @Query(value = "DELETE FROM otp_verifications WHERE id IN ("
            + "SELECT id FROM otp_verifications WHERE expires_at < :cutoff "
            + "ORDER BY id LIMIT :batchSize)", nativeQuery = true)
    int deleteExpiredBatch(@Param("cutoff") LocalDateTime cutoff,
                           @Param("batchSize") int batchSize);
}
