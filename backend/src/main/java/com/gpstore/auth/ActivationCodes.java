package com.gpstore.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The fifteen characters a merchant types once, and never again.
 *
 * WHAT IT IS FOR. A temporary password can be read over somebody's shoulder,
 * forwarded in a WhatsApp message, or left in a screenshot. The activation
 * code is a second factor for the ONE login that matters most - the first one,
 * where an account that has never been used is claimed. After that it is
 * spent: §28 is explicit that this must not become a permanent third password,
 * and code that asked for it every time would train merchants to keep it
 * written down beside the phone, which is the opposite of what it is for.
 *
 * WHY A PLAIN SHA-256 AND NOT BCRYPT, because the instinct is the other way.
 * bcrypt is slow on purpose to protect secrets PEOPLE chose - "gupta123" has
 * maybe 20 bits in it and must be made expensive to guess. This secret is
 * chosen by a CSPRNG from a 55-character alphabet, fifteen characters long:
 * about 87 bits. There is nothing to precompute and nothing to grind, so the
 * slow hash would buy no security and would cost the one thing a fast hash
 * gives us - a column that can carry a UNIQUE index, which is how §23's
 * uniqueness requirement is actually enforced rather than hoped for. This is
 * the ordinary treatment for a high-entropy token, and the reasoning is
 * written down here so nobody "upgrades" it later and quietly loses the
 * uniqueness guarantee with it.
 *
 * THE PLAINTEXT IS NEVER STORED and never returned by any read. It exists in
 * the response that generates it and nowhere else - same contract as the
 * one-time password beside it. Losing it before handover means reissuing
 * (§30), not recovering.
 */
public final class ActivationCodes {

    /**
     * No l/1/I/O/0.
     *
     * This is read off one screen and typed into another, by a shopkeeper,
     * once, probably standing behind a counter. An ambiguous glyph turns a
     * working credential into a support call - the same reason
     * PlatformStaffService draws its passwords from this alphabet.
     */
    private static final String ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    /** §23: exactly fifteen. */
    public static final int LENGTH = 15;

    private static final SecureRandom RANDOM = new SecureRandom();

    private ActivationCodes() {
    }

    /**
     * A new code.
     *
     * SecureRandom, so it is unpredictable and never sequential, and derived
     * from nothing - not the merchant id, not the email, not the phone, not
     * the shop (§23). Anything derived from those is guessable by whoever
     * knows them, which for a merchant's email is everybody they have ever
     * traded with.
     *
     * Re-rolled until it contains both a letter and a digit, because §23 asks
     * for both and a generator that occasionally produced fifteen letters
     * would produce a code that does not meet the stated rule perhaps one time
     * in a few thousand - which is exactly often enough to be found in
     * production and never in a test.
     */
    public static String generate() {
        for (int attempt = 0; attempt < 100; attempt++) {
            StringBuilder builder = new StringBuilder(LENGTH);
            for (int i = 0; i < LENGTH; i++) {
                builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            String candidate = builder.toString();
            if (hasLetterAndDigit(candidate)) {
                return candidate;
            }
        }
        // Unreachable with this alphabet and length. Loud rather than a
        // silent code that does not meet the rule.
        throw new IllegalStateException("Could not generate an acceptable activation code");
    }

    /** What goes in the database. Never the code itself. */
    public static String fingerprint(String code) {
        if (code == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(code.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 not available", impossible);
        }
    }

    /**
     * Whether a typed code matches a stored fingerprint.
     *
     * CONSTANT TIME. The comparison is over a hash rather than the secret, so
     * a timing leak here would reveal nothing usable - but a length-dependent
     * early return is a habit worth not having in an authentication path.
     */
    public static boolean matches(String typed, String storedFingerprint) {
        if (typed == null || typed.isBlank() || storedFingerprint == null) {
            return false;
        }
        return MessageDigest.isEqual(
                fingerprint(typed).getBytes(StandardCharsets.UTF_8),
                storedFingerprint.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean looksWellFormed(String code) {
        return code != null
                && code.trim().length() == LENGTH
                && code.trim().chars().allMatch(c -> ALPHABET.indexOf(c) >= 0)
                && hasLetterAndDigit(code.trim());
    }

    private static boolean hasLetterAndDigit(String value) {
        boolean letter = false;
        boolean digit = false;
        for (char c : value.toCharArray()) {
            if (Character.isDigit(c)) {
                digit = true;
            } else if (Character.isLetter(c)) {
                letter = true;
            }
        }
        return letter && digit;
    }
}
