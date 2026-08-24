package com.gpstore.service;

import com.gpstore.auth.IndianPhoneNumbers;
import com.gpstore.auth.OtpPurpose;
import com.gpstore.entity.OtpVerification;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.TooManyRequestsException;
import com.gpstore.otp.MockOtpProvider;
import com.gpstore.otp.OtpProvider;
import com.gpstore.otp.OtpProviderException;
import com.gpstore.repository.OtpVerificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);

    private static final int OTP_CLEANUP_BATCH_SIZE = 500;
    private static final int OTP_CLEANUP_MAX_BATCHES = 40;

    public static final String INVALID_OTP_MESSAGE = "Invalid or expired OTP";
    public static final String GENERIC_REQUEST_MESSAGE = "If this number is eligible, an OTP has been sent.";
    public static final String GENERIC_SEND_FAILURE = "Unable to send OTP right now. Please try again.";
    public static final String TOO_MANY_SENDS_MESSAGE = "Too many OTP requests. Please try again later.";

    private final OtpVerificationRepository repository;
    private final OtpProvider otpProvider;
    private final TransactionTemplate transactionTemplate;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Clock clock;

    private final int expiryMinutes;
    private final int maxVerifyAttempts;
    private final int maxSendsPerWindow;
    private final int sendWindowMinutes;
    private final int resendCooldownSeconds;

    public OtpService(
            OtpVerificationRepository repository,
            OtpProvider otpProvider,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${otp.expiry-minutes}") int expiryMinutes,
            @Value("${otp.max-verify-attempts}") int maxVerifyAttempts,
            @Value("${otp.max-sends-per-window}") int maxSendsPerWindow,
            @Value("${otp.send-window-minutes}") int sendWindowMinutes,
            @Value("${otp.resend-cooldown-seconds:45}") int resendCooldownSeconds) {
        this.repository = repository;
        this.otpProvider = otpProvider;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.expiryMinutes = Math.min(5, Math.max(1, expiryMinutes));
        this.maxVerifyAttempts = maxVerifyAttempts;
        this.maxSendsPerWindow = maxSendsPerWindow;
        this.sendWindowMinutes = sendWindowMinutes;
        this.resendCooldownSeconds = Math.max(0, resendCooldownSeconds);
    }

    /**
     * Rate-limited: at most maxSendsPerWindow OTPs per phone number per
     * window - each send costs real money, so this stops one number being
     * SMS-bombed (deliberately or by a buggy retry loop) from running up
     * your bill.
     *
     * DELIBERATELY NOT @Transactional, and the SMS call is deliberately
     * outside the transaction this opens. MSG91 is a third party reached
     * over the public internet with a connect timeout and a request
     * timeout; sending inside the transaction meant one slow provider held
     * a pooled database connection for the whole wait. The pool is ten
     * connections wide, so ten people tapping "send code" during an MSG91
     * slowdown took the whole shop down - not just login, everything,
     * because there were no connections left for anyone. The database work
     * is short and stays transactional; the network call now happens after
     * it has committed.
     *
     * The order also matters for correctness, not only for latency: the row
     * commits first, so a code that reaches a customer's phone is always a
     * code this service can verify. The reverse order can text somebody a
     * code the database then rolls back.
     */
    public void sendOtp(String mobileNumber) {
        requestOtp(mobileNumber, OtpPurpose.LOGIN, true);
    }

    /**
     * Always returns the generic request message. Does not reveal whether the
     * phone is registered. {@code deliverSms=false} still records the rate-limit
     * row so unknown password-reset numbers cannot be enumerated by quota.
     */
    public String requestOtp(String rawPhone, OtpPurpose purpose, boolean deliverSms) {
        String local10 = IndianPhoneNumbers.toLocal10(rawPhone);
        String mobileE164 = IndianPhoneNumbers.normalizeTo91(rawPhone);
        log.info("OTP_REQUESTED phone={} purpose={}", IndianPhoneNumbers.mask(local10), purpose);

        if (isWithinResendCooldown(local10)) {
            log.info("OTP_RATE_LIMITED phone={} reason=cooldown", IndianPhoneNumbers.mask(local10));
            return GENERIC_REQUEST_MESSAGE;
        }

        PreparedChallenge prepared;
        try {
            prepared = transactionTemplate.execute(status -> recordChallenge(local10, purpose, deliverSms));
        } catch (TooManyRequestsException ex) {
            log.info("OTP_RATE_LIMITED phone={} reason=window", IndianPhoneNumbers.mask(local10));
            throw ex;
        }

        if (prepared == null || !prepared.deliverSms()) {
            return GENERIC_REQUEST_MESSAGE;
        }

        try {
            OtpProvider.SendResult result = otpProvider.send(
                    mobileE164, purpose, Duration.ofMinutes(expiryMinutes), prepared.plaintextOtp());
            transactionTemplate.executeWithoutResult(status -> {
                repository.findById(prepared.id()).ifPresent(row -> {
                    row.setProviderReference(result.providerReference());
                    row.setLastSentAt(now());
                    repository.save(row);
                });
            });
        } catch (OtpProviderException ex) {
            log.info("OTP_SEND_FAILURE phone={} purpose={}", IndianPhoneNumbers.mask(local10), purpose);
            transactionTemplate.executeWithoutResult(status -> repository.deleteById(prepared.id()));
            throw new BadRequestException(GENERIC_SEND_FAILURE);
        }

        return GENERIC_REQUEST_MESSAGE;
    }

    private boolean isWithinResendCooldown(String local10) {
        if (resendCooldownSeconds <= 0) {
            return false;
        }
        return repository.findLatestSentAt(local10)
                .filter(sent -> sent.isAfter(now().minusSeconds(resendCooldownSeconds)))
                .isPresent();
    }

    private PreparedChallenge recordChallenge(String local10, OtpPurpose purpose, boolean deliverSms) {
        LocalDateTime windowStart = now().minusMinutes(sendWindowMinutes);
        long recentSends = repository.countRecentByMobileNumber(local10, windowStart);
        if (recentSends >= maxSendsPerWindow) {
            throw new TooManyRequestsException(TOO_MANY_SENDS_MESSAGE);
        }

        List<OtpVerification> open = repository.findByMobileNumberAndVerifiedFalseAndConsumedAtIsNull(local10);
        LocalDateTime consumedAt = now();
        for (OtpVerification previous : open) {
            previous.setConsumedAt(consumedAt);
            repository.save(previous);
        }

        String plaintext = null;
        String hash;
        if (!deliverSms) {
            hash = "UNDELIVERED";
        } else if (otpProvider instanceof MockOtpProvider) {
            plaintext = generateSixDigitCode();
            hash = hash(plaintext);
        } else {
            hash = OtpVerification.PROVIDER_MANAGED_HASH;
        }

        OtpVerification entry = new OtpVerification();
        entry.setMobileNumber(local10);
        entry.setOtpHash(hash);
        entry.setExpiresAt(now().plusMinutes(expiryMinutes));
        entry.setAttempts(0);
        entry.setVerified(false);
        entry.setCreatedAt(now());
        entry.setPurpose(purpose);
        entry.setResendCount(0);
        entry.setLastSentAt(now());
        if (!deliverSms) {
            entry.setConsumedAt(now());
        }
        entry = repository.save(entry);
        return new PreparedChallenge(entry.getId(), plaintext, deliverSms);
    }

    /**
     * Verifies against the MOST RECENT open OTP for this number AND purpose.
     * A LOGIN code cannot satisfy PASSWORD_RESET and vice versa. Wrong
     * attempts are counted and capped independently of the send rate limit.
     */
    @Transactional
    public void verifyOtp(String mobileNumber, String otpCode) {
        verifyOtp(mobileNumber, otpCode, OtpPurpose.LOGIN);
    }

    @Transactional
    public void verifyOtp(String mobileNumber, String otpCode, OtpPurpose purpose) {
        String local10 = IndianPhoneNumbers.toLocal10(mobileNumber);
        String mobileE164 = IndianPhoneNumbers.normalizeTo91(mobileNumber);

        OtpVerification entry = repository
                .findFirstByMobileNumberAndPurposeAndVerifiedFalseAndConsumedAtIsNullOrderByCreatedAtDesc(
                        local10, purpose)
                .orElseThrow(() -> {
                    log.info("OTP_VERIFY_FAILURE phone={} purpose={} reason=no_challenge",
                            IndianPhoneNumbers.mask(local10), purpose);
                    return new BadRequestException(INVALID_OTP_MESSAGE);
                });

        if (entry.getExpiresAt().isBefore(now())) {
            log.info("OTP_VERIFY_FAILURE phone={} purpose={} reason=expired",
                    IndianPhoneNumbers.mask(local10), purpose);
            throw new BadRequestException(INVALID_OTP_MESSAGE);
        }

        if (entry.getAttempts() != null && entry.getAttempts() >= maxVerifyAttempts) {
            log.info("OTP_VERIFY_FAILURE phone={} purpose={} reason=max_attempts",
                    IndianPhoneNumbers.mask(local10), purpose);
            throw new BadRequestException("Too many incorrect attempts - please request a new OTP");
        }

        boolean matches;
        try {
            if (OtpVerification.PROVIDER_MANAGED_HASH.equals(entry.getOtpHash())) {
                matches = otpProvider.verify(mobileE164, otpCode, purpose);
            } else if ("UNDELIVERED".equals(entry.getOtpHash())) {
                matches = false;
            } else {
                matches = entry.getOtpHash().equals(hash(otpCode));
            }
        } catch (OtpProviderException ex) {
            log.info("OTP_VERIFY_FAILURE phone={} purpose={} reason=provider",
                    IndianPhoneNumbers.mask(local10), purpose);
            throw new BadRequestException(GENERIC_SEND_FAILURE);
        }

        if (!matches) {
            entry.setAttempts(entry.getAttempts() == null ? 1 : entry.getAttempts() + 1);
            repository.save(entry);
            log.info("OTP_VERIFY_FAILURE phone={} purpose={} reason=mismatch",
                    IndianPhoneNumbers.mask(local10), purpose);
            throw new BadRequestException(INVALID_OTP_MESSAGE);
        }

        entry.setVerified(true);
        entry.setConsumedAt(now());
        repository.save(entry);
        log.info("OTP_VERIFY_SUCCESS phone={} purpose={}", IndianPhoneNumbers.mask(local10), purpose);
    }

    @Transactional
    public void consumeOpenChallenges(String rawPhone, OtpPurpose purpose) {
        String local10 = IndianPhoneNumbers.toLocal10(rawPhone);
        repository.consumeOpenChallenges(local10, purpose.name(), now());
    }

    private String generateSixDigitCode() {
        int code = 100000 + secureRandom.nextInt(900000);
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

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
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
        LocalDateTime cutoff = now().minusDays(1);
        for (int batch = 0; batch < OTP_CLEANUP_MAX_BATCHES; batch++) {
            int deleted = repository.deleteExpiredBatch(cutoff, OTP_CLEANUP_BATCH_SIZE);
            if (deleted < OTP_CLEANUP_BATCH_SIZE) {
                break;
            }
        }
    }

    private record PreparedChallenge(Long id, String plaintextOtp, boolean deliverSms) {
    }
}
