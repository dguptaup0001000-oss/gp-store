package com.gpstore.otp;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.gpstore.auth.EmailIdentities;
import com.gpstore.auth.OtpPurpose;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;

/**
 * Email OTP transport. Same {@link OtpProvider} shape as SMS. Never logs
 * OTP values or SMTP passwords.
 *
 * Issued codes: a bounded Caffeine cache (plaintext, same JVM, for tests
 * and local verify) plus Redis when configured. Redis stores only a SHA-256
 * hex of the code, with the same TTL as {@code send}'s {@code expiry}.
 * {@link #verify} on another instance reads that hash. A successful verify
 * removes both entries.
 *
 * {@link com.gpstore.service.OtpService} remains the authority for
 * consumedAt, single-use, and the attempt cap (database hash). This store
 * exists so {@link #verify} is multi-instance-safe if a caller uses the
 * provider path.
 */
public class EmailOtpProvider implements OtpProvider {

    private static final Logger log = LoggerFactory.getLogger(EmailOtpProvider.class);

    static final long DEFAULT_MAX_ENTRIES = 50_000L;
    static final String REDIS_KEY_PREFIX = "gpstore:otp:issued:";

    private final JavaMailSender mailSender;
    private final String from;
    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();
    private final Cache<String, IssuedCode> issued;

    public EmailOtpProvider(JavaMailSender mailSender, String from) {
        this(mailSender, from, null, DEFAULT_MAX_ENTRIES);
    }

    EmailOtpProvider(JavaMailSender mailSender, String from, long maximumSize) {
        this(mailSender, from, null, maximumSize);
    }

    EmailOtpProvider(JavaMailSender mailSender, String from, StringRedisTemplate redis) {
        this(mailSender, from, redis, DEFAULT_MAX_ENTRIES);
    }

    EmailOtpProvider(JavaMailSender mailSender, String from, StringRedisTemplate redis, long maximumSize) {
        this.mailSender = mailSender;
        this.from = from == null ? "" : from.trim();
        this.redis = redis;
        if (redis == null) {
            log.warn("Email OTP issued-code store is JVM-local; verify() will not see codes issued by another instance");
        }
        this.issued = Caffeine.newBuilder()
                .expireAfter(new Expiry<String, IssuedCode>() {
                    @Override
                    public long expireAfterCreate(String key, IssuedCode value, long currentTime) {
                        return value.ttlNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, IssuedCode value, long currentTime,
                                                  long currentDuration) {
                        return value.ttlNanos();
                    }

                    @Override
                    public long expireAfterRead(String key, IssuedCode value, long currentTime,
                                                long currentDuration) {
                        return currentDuration;
                    }
                })
                .maximumSize(maximumSize)
                .executor(Runnable::run)
                .build();
    }

    @Override
    public boolean issuesLocalCode() {
        return true;
    }

    @Override
    public SendResult send(String destination, OtpPurpose purpose, Duration expiry, String optionalOtp) {
        String code = optionalOtp != null && !optionalOtp.isBlank()
                ? optionalOtp
                : generateSixDigitCode();
        Duration ttl = requireExpiry(expiry);
        String cacheKey = key(destination, purpose);
        issued.put(cacheKey, new IssuedCode(code, ttl.toNanos()));
        rememberHash(destination, purpose, code, ttl);
        if (mailSender != null && !from.isBlank()) {
            try {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
                helper.setFrom(from);
                helper.setTo(destination);
                helper.setSubject("Your GP Store verification code");
                long minutes = Math.max(1, ttl.toMinutes());
                helper.setText(
                        "Your GP Store verification code is " + code + ".\n"
                                + "It expires in " + minutes + " minutes.\n\n"
                                + "Do not share this code.",
                        false);
                mailSender.send(message);
            } catch (Exception ex) {
                forget(destination, purpose);
                log.info("OTP_SEND_FAILURE dest={} purpose={} provider=email",
                        EmailIdentities.mask(destination), purpose);
                throw new OtpProviderException("Unable to send OTP right now. Please try again.");
            }
        }
        log.info("OTP_SEND_SUCCESS dest={} purpose={} provider=email",
                EmailIdentities.mask(destination), purpose);
        return SendResult.ok("email");
    }

    @Override
    public boolean verify(String destination, String otp, OtpPurpose purpose) {
        if (otp == null || otp.isBlank() || purpose == null) {
            return false;
        }
        IssuedCode stored = issued.getIfPresent(key(destination, purpose));
        boolean localMatch = stored != null && otp.equals(stored.code());
        boolean redisMatch = hashMatches(destination, purpose, otp);
        if (!localMatch && !redisMatch) {
            return false;
        }
        forget(destination, purpose);
        return true;
    }

    @Override
    public SendResult resend(String destination) {
        throw new UnsupportedOperationException(
                "EmailOtpProvider.resend is not implemented. Request a new code through OtpService.requestOtp.");
    }

    @Override
    public Optional<String> peekIssuedOtpForTests(String destination, OtpPurpose purpose) {
        IssuedCode stored = issued.getIfPresent(key(destination, purpose));
        return Optional.ofNullable(stored).map(IssuedCode::code);
    }

    void evictExpiredIssuedCodes() {
        issued.cleanUp();
    }

    long issuedCacheSize() {
        issued.cleanUp();
        return issued.asMap().size();
    }

    private String generateSixDigitCode() {
        return String.valueOf(100000 + random.nextInt(900000));
    }

    private void rememberHash(String destination, OtpPurpose purpose, String code, Duration ttl) {
        if (redis == null) {
            return;
        }
        try {
            redis.opsForValue().set(redisKey(destination, purpose), OtpCodeHashes.sha256Hex(code), ttl);
        } catch (RuntimeException ex) {
            log.warn("OTP_ISSUED_STORE_REDIS_FAILURE dest={} purpose={} op=set",
                    EmailIdentities.mask(destination), purpose);
        }
    }

    private boolean hashMatches(String destination, OtpPurpose purpose, String otp) {
        if (redis == null) {
            return false;
        }
        try {
            String stored = redis.opsForValue().get(redisKey(destination, purpose));
            return stored != null && stored.equals(OtpCodeHashes.sha256Hex(otp));
        } catch (RuntimeException ex) {
            log.warn("OTP_ISSUED_STORE_REDIS_FAILURE dest={} purpose={} op=get",
                    EmailIdentities.mask(destination), purpose);
            return false;
        }
    }

    private void forget(String destination, OtpPurpose purpose) {
        issued.invalidate(key(destination, purpose));
        if (redis == null) {
            return;
        }
        try {
            redis.delete(redisKey(destination, purpose));
        } catch (RuntimeException ex) {
            log.warn("OTP_ISSUED_STORE_REDIS_FAILURE dest={} purpose={} op=del",
                    EmailIdentities.mask(destination), purpose);
        }
    }

    static String redisKey(String destination, OtpPurpose purpose) {
        return REDIS_KEY_PREFIX + OtpCodeHashes.sha256Hex(key(destination, purpose));
    }

    static Duration requireExpiry(Duration expiry) {
        if (expiry == null || expiry.isZero() || expiry.isNegative()) {
            throw new IllegalArgumentException("OTP expiry must be a positive duration");
        }
        return expiry;
    }

    private static String key(String destination, OtpPurpose purpose) {
        return destination + ":" + purpose.name();
    }

    private record IssuedCode(String code, long ttlNanos) {
    }
}
