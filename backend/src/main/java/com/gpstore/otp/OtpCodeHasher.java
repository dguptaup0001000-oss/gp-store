package com.gpstore.otp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Keyed hash for OTP codes, shared by the database record (OtpService) and the
 * cross-instance Redis copy (EmailOtpProvider). BOTH MUST USE THIS. A code
 * hashed one way and verified the other simply never matches, which would
 * lock every customer out of OTP login.
 *
 * WHY KEYED, NOT A PLAIN DIGEST. A six-digit OTP has 900,000 possible values.
 * A plain SHA-256 of one is reversible by exhaustive search in well under a
 * second on a laptop, so storing SHA-256(code) protects nothing from whoever
 * reads a database dump, an off-box backup, or a Redis snapshot - which is
 * precisely the reader the hash exists to defend against. HMAC with a key
 * that lives only in the environment makes that dump useless on its own.
 *
 * THE KEY IS DERIVED, NOT THE JWT SECRET ITSELF. It comes from
 * {@code otp.hash-secret} when set, otherwise {@code jwt.secret}, which
 * production already refuses to boot without - so this needs no new operator
 * step. Either source is run through HMAC with a fixed label first, so the
 * OTP key and the token-signing key are cryptographically separate:
 * recovering one does not hand over the other.
 *
 * NO LEGACY FALLBACK, ON PURPOSE. Accepting old plain-SHA-256 hashes would
 * keep the reversible path alive forever. OTP rows live minutes, so the only
 * cost of a clean cutover is that codes issued in the few minutes before a
 * deploy stop verifying and the customer requests a new one.
 *
 * Never log a code, a digest, or the key.
 */
@Component
public class OtpCodeHasher {

    private static final String ALGORITHM = "HmacSHA256";

    /**
     * Domain separation label. Changing it invalidates every stored hash, so
     * it is versioned rather than edited.
     */
    private static final String DOMAIN = "gpstore:otp-hash:v1";

    private final byte[] key;

    public OtpCodeHasher(
            @Value("${otp.hash-secret:}") String otpHashSecret,
            @Value("${jwt.secret:}") String jwtSecret) {
        String base = present(otpHashSecret) ? otpHashSecret : jwtSecret;
        if (!present(base)) {
            throw new IllegalStateException(
                    "OTP hashing needs a secret. Set OTP_HASH_SECRET, or JWT_SECRET which "
                            + "production already requires. Refusing to hash OTPs with no key.");
        }
        this.key = mac(base.getBytes(StandardCharsets.UTF_8), DOMAIN);
    }

    /** Hex HMAC of an OTP code. Stable for a given code and key. */
    public String hash(String code) {
        return HexFormat.of().formatHex(mac(key, code));
    }

    /**
     * Constant-time comparison of a candidate code against a stored hash.
     *
     * {@code String.equals} returns at the first differing byte, and that
     * timing is measurable across enough attempts. A digest comparison must
     * not leak how much of a guess was correct.
     */
    public boolean matches(String code, String storedHash) {
        if (code == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(code).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] mac(byte[] keyBytes, String message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(keyBytes, ALGORITHM));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // No key, code, or digest in this message.
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
