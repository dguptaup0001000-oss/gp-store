package com.gpstore.payment.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Decides whether a webhook actually came from Cashfree.
 *
 * This is the only thing standing between a public, unauthenticated endpoint
 * and anyone who can POST JSON marking orders paid. The webhook path cannot
 * require a JWT - Cashfree has no way to obtain one - so the signature IS
 * the authentication.
 *
 * THE SCHEME, per Cashfree's current documentation:
 *
 *     signedPayload = x-webhook-timestamp + rawBody
 *     expected      = base64(HMAC-SHA256(signedPayload, secret))
 *     compare against the x-webhook-signature header
 *
 * RAW BODY, NOT RE-SERIALIZED. The controller takes the body as a String and
 * hands it here untouched. Parsing to a Map and writing it back out produces
 * different bytes - different key order, different number formatting,
 * different whitespace - and the HMAC covers bytes. Every "signature check
 * always fails" report on any gateway is this mistake.
 *
 * CONSTANT-TIME COMPARISON. String.equals returns as soon as two bytes
 * differ, and that timing is measurable across enough attempts. The cost of
 * MessageDigest.isEqual is nothing; the cost of being wrong is an endpoint
 * that can be brute-forced into accepting a forged payment.
 */
@Component
public class CashfreeSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(CashfreeSignatureVerifier.class);

    /**
     * How stale a signed timestamp may be.
     *
     * Without this the signature alone permits replay: an attacker who ever
     * observes one valid (body, signature) pair could resend it forever.
     * Cashfree's own retries land well inside this window; anything older is
     * either a replay or a delivery so late that the reconciliation sweep is
     * the right way to handle it rather than the live path.
     */
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(15);

    public enum Result { VALID, BAD_SIGNATURE, STALE, MALFORMED, NOT_CONFIGURED }

    private final CashfreeProperties properties;

    public CashfreeSignatureVerifier(CashfreeProperties properties) {
        this.properties = properties;
    }

    public Result verify(String rawBody, String signatureHeader, String timestampHeader) {
        String secret = properties.getWebhookSecret().isBlank()
                ? properties.getSecretKey()
                : properties.getWebhookSecret();

        if (secret.isBlank()) {
            // Fail closed. An unconfigured secret must never mean "accept
            // everything" - that is the shape of the worst possible bug here.
            log.error("Cashfree webhook received but no webhook secret is configured - rejecting");
            return Result.NOT_CONFIGURED;
        }

        if (rawBody == null || signatureHeader == null || signatureHeader.isBlank()
                || timestampHeader == null || timestampHeader.isBlank()) {
            return Result.MALFORMED;
        }

        if (isStale(timestampHeader)) {
            return Result.STALE;
        }

        String expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestampHeader + rawBody).getBytes(StandardCharsets.UTF_8));
            expected = Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            // No secret, no signature and no body detail in this line - a log
            // that leaks the material it is protecting is worse than silence.
            log.error("Could not compute Cashfree webhook signature: {}", e.getClass().getSimpleName());
            return Result.MALFORMED;
        }

        boolean matches = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8));

        return matches ? Result.VALID : Result.BAD_SIGNATURE;
    }

    /**
     * Cashfree sends epoch SECONDS. Parsed defensively because a malformed
     * header must be a rejection, not an exception escaping into a 500 that
     * Cashfree would then retry forever.
     */
    private boolean isStale(String timestampHeader) {
        try {
            Instant sent = Instant.ofEpochSecond(Long.parseLong(timestampHeader.trim()));
            Duration age = Duration.between(sent, Instant.now()).abs();
            return age.compareTo(MAX_CLOCK_SKEW) > 0;
        } catch (NumberFormatException e) {
            return true;
        }
    }
}
