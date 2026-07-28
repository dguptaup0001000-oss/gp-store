package com.gpstore.service;

import com.gpstore.entity.OtpVerification;
import com.gpstore.exception.BadRequestException;
import com.gpstore.repository.OtpVerificationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
public class OtpService {

    private static final String INVALID_OTP_MESSAGE = "Invalid or expired OTP";

    private final OtpVerificationRepository repository;
    private final SmsService smsService;
    private final SecureRandom secureRandom = new SecureRandom();

    private final int expiryMinutes;
    private final int maxVerifyAttempts;
    private final int maxSendsPerWindow;
    private final int sendWindowMinutes;

    public OtpService(
            OtpVerificationRepository repository,
            SmsService smsService,
            @Value("${otp.expiry-minutes}") int expiryMinutes,
            @Value("${otp.max-verify-attempts}") int maxVerifyAttempts,
            @Value("${otp.max-sends-per-window}") int maxSendsPerWindow,
            @Value("${otp.send-window-minutes}") int sendWindowMinutes) {
        this.repository = repository;
        this.smsService = smsService;
        this.expiryMinutes = expiryMinutes;
        this.maxVerifyAttempts = maxVerifyAttempts;
        this.maxSendsPerWindow = maxSendsPerWindow;
        this.sendWindowMinutes = sendWindowMinutes;
    }

    /**
     * Rate-limited: at most maxSendsPerWindow OTPs per phone number per
     * window - each send costs real money, so this stops one number being
     * SMS-bombed (deliberately or by a buggy retry loop) from running up
     * your bill.
     */
    @Transactional
    public void sendOtp(String mobileNumber) {
        LocalDateTime windowStart = LocalDateTime.now().minusMinutes(sendWindowMinutes);
        long recentSends = repository.countRecentByMobileNumber(mobileNumber, windowStart);

        if (recentSends >= maxSendsPerWindow) {
            throw new BadRequestException(
                    "Too many OTP requests for this number - please wait a few minutes and try again");
        }

        String otpCode = generateSixDigitCode();

        OtpVerification entry = new OtpVerification();
        entry.setMobileNumber(mobileNumber);
        entry.setOtpHash(hash(otpCode));
        entry.setExpiresAt(LocalDateTime.now().plusMinutes(expiryMinutes));
        entry.setAttempts(0);
        entry.setVerified(false);
        entry.setCreatedAt(LocalDateTime.now());

        repository.save(entry);

        smsService.sendOtp(mobileNumber, otpCode);
    }

    /**
     * Verifies against the MOST RECENT unverified OTP for this number only -
     * an old code from an earlier request can never be replayed. Wrong
     * attempts are counted and capped (maxVerifyAttempts) independently of
     * the send rate limit above - stops brute-forcing the 6-digit code
     * within its validity window.
     */
    @Transactional
    public void verifyOtp(String mobileNumber, String otpCode) {
        OtpVerification entry = repository
                .findFirstByMobileNumberAndVerifiedFalseOrderByCreatedAtDesc(mobileNumber)
                .orElseThrow(() -> new BadRequestException(INVALID_OTP_MESSAGE));

        if (entry.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BadRequestException(INVALID_OTP_MESSAGE);
        }

        if (entry.getAttempts() >= maxVerifyAttempts) {
            throw new BadRequestException("Too many incorrect attempts - please request a new OTP");
        }

        if (!entry.getOtpHash().equals(hash(otpCode))) {
            entry.setAttempts(entry.getAttempts() + 1);
            repository.save(entry);
            throw new BadRequestException(INVALID_OTP_MESSAGE);
        }

        entry.setVerified(true);
        repository.save(entry);
    }

    private String generateSixDigitCode() {
        int code = 100000 + secureRandom.nextInt(900000); // always exactly 6 digits
        return String.valueOf(code);
    }

    private String hash(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Housekeeping - deletes OTP rows that expired over a day ago. Nothing security-sensitive here, just table growth. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60 * 60 * 1000)
    @Transactional
    public void cleanUpExpiredOtps() {
        var stale = repository.findByExpiresAtBefore(LocalDateTime.now().minusDays(1));
        if (!stale.isEmpty()) {
            repository.deleteAll(stale);
        }
    }
}
