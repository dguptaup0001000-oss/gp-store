package com.gpstore.otp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtpCodeHasherTest {

    private static final String SECRET = "unit-test-secret-not-a-real-one-0123456789";
    private static final String OTHER_SECRET = "a-different-unit-test-secret-9876543210";

    private static OtpCodeHasher hasher(String secret) {
        return new OtpCodeHasher("", secret);
    }

    @Test
    void hashIsStableForTheSameCodeAndKey() {
        OtpCodeHasher hasher = hasher(SECRET);
        assertEquals(hasher.hash("654321"), hasher.hash("654321"));
        assertEquals(hasher.hash("654321"), hasher(SECRET).hash("654321"));
    }

    @Test
    void differentCodesHashDifferently() {
        OtpCodeHasher hasher = hasher(SECRET);
        assertNotEquals(hasher.hash("654321"), hasher.hash("654322"));
    }

    @Test
    @DisplayName("the hash is KEYED: the same code under a different secret is a different digest")
    void differentSecretsProduceDifferentHashes() {
        // This is the whole point. A plain digest would be identical here,
        // which is what made a stolen dump reversible.
        assertNotEquals(hasher(SECRET).hash("654321"), hasher(OTHER_SECRET).hash("654321"));
    }

    @Test
    @DisplayName("a stored hash is not the plain SHA-256 anyone can rainbow-table")
    void hashIsNotPlainSha256OfTheCode() throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        String plain = HexFormat.of().formatHex(sha256.digest("654321".getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(plain, hasher(SECRET).hash("654321"),
                "storing the unkeyed digest of a 6-digit code is reversible in under a second");
    }

    @Test
    void hashNeverLeaksThePlaintext() {
        assertFalse(hasher(SECRET).hash("654321").contains("654321"));
    }

    @Test
    void matchesAcceptsTheRightCodeAndRejectsEverythingElse() {
        OtpCodeHasher hasher = hasher(SECRET);
        String stored = hasher.hash("654321");
        assertTrue(hasher.matches("654321", stored));
        assertFalse(hasher.matches("654322", stored));
        assertFalse(hasher.matches(null, stored));
        assertFalse(hasher.matches("654321", null));
    }

    @Test
    @DisplayName("a hash minted under one secret does not verify under another")
    void matchesIsKeyBound() {
        String stored = hasher(SECRET).hash("654321");
        assertFalse(hasher(OTHER_SECRET).matches("654321", stored));
    }

    @Test
    void anExplicitOtpSecretOverridesTheJwtSecret() {
        OtpCodeHasher explicit = new OtpCodeHasher(OTHER_SECRET, SECRET);
        assertEquals(hasher(OTHER_SECRET).hash("654321"), explicit.hash("654321"));
        assertNotEquals(hasher(SECRET).hash("654321"), explicit.hash("654321"));
    }

    @Test
    @DisplayName("no secret at all is a refusal to start, not a silent unkeyed hash")
    void blankSecretFailsClosed() {
        assertThrows(IllegalStateException.class, () -> new OtpCodeHasher("", ""));
        assertThrows(IllegalStateException.class, () -> new OtpCodeHasher(null, null));
        assertThrows(IllegalStateException.class, () -> new OtpCodeHasher("  ", "  "));
    }
}
