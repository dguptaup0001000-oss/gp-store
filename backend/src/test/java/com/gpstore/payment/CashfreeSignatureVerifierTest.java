package com.gpstore.payment;

import com.gpstore.payment.gateway.CashfreeProperties;
import com.gpstore.payment.gateway.CashfreeSignatureVerifier;
import com.gpstore.payment.gateway.CashfreeSignatureVerifier.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The webhook endpoint is public - Cashfree cannot present a JWT - so this
 * signature check is the ONLY thing between the internet and an endpoint
 * that marks orders paid. These are the tests that matter most in the whole
 * integration.
 */
class CashfreeSignatureVerifierTest {

    private static final String SECRET = "test_secret_key_do_not_use";
    private static final String BODY = "{\"type\":\"PAYMENT_SUCCESS_WEBHOOK\",\"data\":{\"order\":{\"order_id\":\"GP-1-abc\"}}}";

    private CashfreeProperties properties;
    private CashfreeSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        properties = new CashfreeProperties();
        properties.setSecretKey(SECRET);
        properties.setWebhookSecret(SECRET);
        verifier = new CashfreeSignatureVerifier(properties);
    }

    private static String sign(String timestamp, String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(
                mac.doFinal((timestamp + body).getBytes(StandardCharsets.UTF_8)));
    }

    private static String now() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    @Test
    @DisplayName("A genuine Cashfree signature is accepted")
    void validSignaturePasses() throws Exception {
        String ts = now();
        assertEquals(Result.VALID, verifier.verify(BODY, sign(ts, BODY, SECRET), ts));
    }

    @Test
    @DisplayName("A forged signature is rejected")
    void forgedSignatureRejected() {
        assertEquals(Result.BAD_SIGNATURE,
                verifier.verify(BODY, "ZmFrZSBzaWduYXR1cmU=", now()));
    }

    @Test
    @DisplayName("A signature made with the wrong secret is rejected")
    void wrongSecretRejected() throws Exception {
        String ts = now();
        assertEquals(Result.BAD_SIGNATURE,
                verifier.verify(BODY, sign(ts, BODY, "someone_elses_secret"), ts));
    }

    @Test
    @DisplayName("A tampered body invalidates the signature")
    void tamperedBodyRejected() throws Exception {
        // The attack this stops: intercept a real webhook, change the order
        // id to one you want marked paid, replay it.
        String ts = now();
        String signature = sign(ts, BODY, SECRET);
        String tampered = BODY.replace("GP-1-abc", "GP-999-xyz");

        assertEquals(Result.BAD_SIGNATURE, verifier.verify(tampered, signature, ts));
    }

    @Test
    @DisplayName("A signature valid for one timestamp does not work for another")
    void timestampIsCoveredBySignature() throws Exception {
        String signature = sign(now(), BODY, SECRET);
        String differentTimestamp = String.valueOf(Instant.now().getEpochSecond() - 60);

        assertEquals(Result.BAD_SIGNATURE, verifier.verify(BODY, signature, differentTimestamp));
    }

    @Test
    @DisplayName("An old but correctly signed event is rejected as stale")
    void replayOutsideTheWindowRejected() throws Exception {
        // Without the freshness window, a signature never expires: anyone who
        // ever captured one valid delivery could replay it forever.
        String oldTs = String.valueOf(Instant.now().minusSeconds(60 * 60).getEpochSecond());
        assertEquals(Result.STALE, verifier.verify(BODY, sign(oldTs, BODY, SECRET), oldTs));
    }

    @Test
    @DisplayName("A future-dated timestamp is rejected too")
    void futureTimestampRejected() throws Exception {
        String futureTs = String.valueOf(Instant.now().plusSeconds(60 * 60).getEpochSecond());
        assertEquals(Result.STALE, verifier.verify(BODY, sign(futureTs, BODY, SECRET), futureTs));
    }

    @Test
    @DisplayName("Missing headers are rejected, not treated as unsigned-but-fine")
    void missingHeadersRejected() {
        assertEquals(Result.MALFORMED, verifier.verify(BODY, null, now()));
        assertEquals(Result.MALFORMED, verifier.verify(BODY, "sig", null));
        assertEquals(Result.MALFORMED, verifier.verify(null, "sig", now()));
        assertEquals(Result.MALFORMED, verifier.verify(BODY, "   ", now()));
    }

    @Test
    @DisplayName("A non-numeric timestamp is rejected rather than throwing")
    void malformedTimestampRejected() {
        // Must not escape as an exception: a 500 makes Cashfree retry a
        // request that can never succeed.
        assertEquals(Result.STALE, verifier.verify(BODY, "sig", "not-a-timestamp"));
    }

    @Test
    @DisplayName("With no secret configured, everything is rejected - never accepted")
    void unconfiguredFailsClosed() {
        // The worst possible bug in this class would be treating "no secret"
        // as "no verification required".
        CashfreeProperties empty = new CashfreeProperties();
        CashfreeSignatureVerifier unconfigured = new CashfreeSignatureVerifier(empty);

        assertEquals(Result.NOT_CONFIGURED, unconfigured.verify(BODY, "anything", now()));
    }

    @Test
    @DisplayName("Falls back to the API secret when no separate webhook secret is set")
    void fallsBackToApiSecret() throws Exception {
        CashfreeProperties onlyApiSecret = new CashfreeProperties();
        onlyApiSecret.setSecretKey(SECRET);
        CashfreeSignatureVerifier v = new CashfreeSignatureVerifier(onlyApiSecret);

        String ts = now();
        assertEquals(Result.VALID, v.verify(BODY, sign(ts, BODY, SECRET), ts));
    }

    @Test
    @DisplayName("Whitespace differences in the body break the signature")
    void reserializedBodyFails() throws Exception {
        // This is why the controller takes @RequestBody String and not a DTO.
        // Parsing to an object and writing it back produces different bytes,
        // and the HMAC covers bytes - the classic "signature always fails"
        // integration bug.
        String ts = now();
        String signature = sign(ts, BODY, SECRET);
        String reserialized = BODY.replace("\":", "\": ");

        assertEquals(Result.BAD_SIGNATURE, verifier.verify(reserialized, signature, ts));
    }
}
