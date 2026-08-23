package com.gpstore.service;

import com.gpstore.entity.OtpVerification;
import com.gpstore.exception.BadRequestException;
import com.gpstore.repository.OtpVerificationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
public class OtpService {

    private static final int OTP_CLEANUP_BATCH_SIZE = 500;
    private static final int OTP_CLEANUP_MAX_BATCHES = 40;

    private static final String INVALID_OTP_MESSAGE = "Invalid or expired OTP";

    private final OtpVerificationRepository repository;
    private final SmsService smsService;
    private final TransactionTemplate transactionTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    private final int expiryMinutes;
    private final int maxVerifyAttempts;
    private final int maxSendsPerWindow;
    private final int sendWindowMinutes;

    public OtpService(
            OtpVerificationRepository repository,
            SmsService smsService,
            PlatformTransactionManager transactionManager,
            @Value("${otp.expiry-minutes}") int expiryMinutes,
            @Value("${otp.max-verify-attempts}") int maxVerifyAttempts,
            @Value("${otp.max-sends-per-window}") int maxSendsPerWindow,
            @Value("${otp.send-window-minutes}") int sendWindowMinutes) {
        this.repository = repository;
        this.smsService = smsService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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
     *
     * DELIBERATELY NOT @Transactional, and the SMS call is deliberately
     * outside the transaction this opens. MSG91 is a third party reached
     * over the public internet with a 10 s connect timeout and a 10 s
     * request timeout; sending inside the transaction meant one slow
     * provider held a pooled database connection for up to twenty seconds
     * per request. The pool is ten connections wide, so ten people tapping
     * "send code" during an MSG91 slowdown took the whole shop down - not
     * just login, everything, because there were no connections left for
     * anyone. The database work is short and stays transactional; the
     * network call now happens after it has committed.
     *
     * The order also matters for correctness, not only for latency: the row
     * commits first, so a code that reaches a customer's phone is always a
     * code this service can verify. The reverse order can text somebody a
     * code the database then rolls back.
     */
    public void sendOtp(String mobileNumber) {
        String otpCode = transactionTemplate.execute(status -> recordOtp(mobileNumber));

        // Outside the transaction on purpose - see above. SmsService never
        // throws; a provider outage is logged and the customer can retry.
        smsService.sendOtp(mobileNumber, otpCode);
    }

    /**
     * The database half of {@link #sendOtp}: rate-limit check and the row.
     *
     * Runs inside the caller's TransactionTemplate rather than carrying its
     * own @Transactional, because a self-invocation would not pass through
     * the proxy and would silently run with no transaction at all.
     */
    private String recordOtp(String mobileNumber) {
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

        return otpCode;
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

    /**
     * Housekeeping - deletes OTP rows that expired over a day ago. Nothing
     * security-sensitive here, just table growth.
     *
     * Batched: the previous version loaded every expired row into memory and
     * deleted them one at a time. OTP volume grows with signups and login
     * attempts, and if this job is ever paused (a deploy, an outage) the
     * backlog is whatever built up meanwhile - precisely the moment loading
     * it all at once behaves worst. Each batch is its own statement, so a
     * large backlog drains steadily instead of in one long transaction.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${otp.cleanup-interval-ms:3600000}", initialDelayString = "${otp.cleanup-initial-delay-ms:60000}")
    @Transactional
    public void cleanUpExpiredOtps() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(1);
        for (int batch = 0; batch < OTP_CLEANUP_MAX_BATCHES; batch++) {
            int deleted = repository.deleteExpiredBatch(cutoff, OTP_CLEANUP_BATCH_SIZE);
            if (deleted < OTP_CLEANUP_BATCH_SIZE) {
                break;
            }
        }
    }
}
