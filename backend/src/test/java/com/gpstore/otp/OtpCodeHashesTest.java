package com.gpstore.otp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class OtpCodeHashesTest {

    @Test
    void sha256HexIsStableAndNotThePlaintext() {
        String hash = OtpCodeHashes.sha256Hex("654321");
        assertEquals(64, hash.length());
        assertEquals(hash, OtpCodeHashes.sha256Hex("654321"));
        assertNotEquals("654321", hash);
        assertNotEquals(hash, OtpCodeHashes.sha256Hex("654322"));
    }
}
