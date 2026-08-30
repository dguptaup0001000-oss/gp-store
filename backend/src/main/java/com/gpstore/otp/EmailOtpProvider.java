package com.gpstore.otp;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.gpstore.auth.EmailIdentities;
import com.gpstore.auth.OtpPurpose;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Issued codes live in a bounded Caffeine cache whose per-entry write TTL
 * is the {@code expiry} passed to {@link #send}. A successful
 * {@link #verify} removes the entry. {@link com.gpstore.service.OtpService}
 * remains the authority for consumedAt, single-use, and the attempt cap;
 * this cache is only so {@link #peekIssuedOtpForTests} and {@link #verify}
 * can see a locally issued code.
 */
public class EmailOtpProvider implements OtpProvider {

    private static final Logger log = LoggerFactory.getLogger(EmailOtpProvider.class);

    static final long DEFAULT_MAX_ENTRIES = 50_000L;

    private final JavaMailSender mailSender;
    private final String from;
    private final SecureRandom random = new SecureRandom();
    private final Cache<String, IssuedCode> issued;

    public EmailOtpProvider(JavaMailSender mailSender, String from) {
        this(mailSender, from, DEFAULT_MAX_ENTRIES);
    }

    EmailOtpProvider(JavaMailSender mailSender, String from, long maximumSize) {
        this.mailSender = mailSender;
        this.from = from == null ? "" : from.trim();
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
        issued.put(key(destination, purpose), new IssuedCode(code, ttl.toNanos()));
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
                issued.invalidate(key(destination, purpose));
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
        String cacheKey = key(destination, purpose);
        IssuedCode stored = issued.getIfPresent(cacheKey);
        if (stored == null || !otp.equals(stored.code())) {
            return false;
        }
        issued.invalidate(cacheKey);
        return true;
    }

    @Override
    public SendResult resend(String destination) {
        log.info("OTP_SEND_SUCCESS dest={} purpose=resend provider=email",
                EmailIdentities.mask(destination));
        return SendResult.ok("email-resend");
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
