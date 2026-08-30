package com.gpstore.otp;

import com.gpstore.auth.OtpPurpose;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmailOtpProviderTest {

    @Test
    void sendUsesTheLocalCodeAndDoesNotPutItInTheSubject() throws Exception {
        List<MimeMessage> sent = new ArrayList<>();
        EmailOtpProvider provider = providerThatRecords(sent);
        provider.send("shop@example.com", OtpPurpose.LOGIN, Duration.ofMinutes(5), "654321");

        assertEquals(1, sent.size());
        assertEquals("Your GP Store verification code", sent.getFirst().getSubject());
        String body = (String) sent.getFirst().getContent();
        assertTrue(body.contains("654321"));
        assertFalse(sent.getFirst().getSubject().contains("654321"));
        assertTrue(provider.peekIssuedOtpForTests("shop@example.com", OtpPurpose.LOGIN).orElseThrow().equals("654321"));
    }

    @Test
    @DisplayName("successful verify removes the plaintext entry")
    void successfulVerifyRemovesTheIssuedEntry() {
        EmailOtpProvider provider = new EmailOtpProvider(null, "noreply@gpstore.co.in");
        provider.send("shop@example.com", OtpPurpose.LOGIN, Duration.ofMinutes(5), "654321");
        assertEquals(1, provider.issuedCacheSize());

        assertTrue(provider.verify("shop@example.com", "654321", OtpPurpose.LOGIN));
        assertTrue(provider.peekIssuedOtpForTests("shop@example.com", OtpPurpose.LOGIN).isEmpty());
        assertEquals(0, provider.issuedCacheSize());
        assertFalse(provider.verify("shop@example.com", "654321", OtpPurpose.LOGIN));
    }

    @Test
    @DisplayName("failed verify leaves the entry so a later correct attempt can still match")
    void failedVerifyKeepsTheIssuedEntry() {
        EmailOtpProvider provider = new EmailOtpProvider(null, "noreply@gpstore.co.in");
        provider.send("shop@example.com", OtpPurpose.LOGIN, Duration.ofMinutes(5), "654321");

        assertFalse(provider.verify("shop@example.com", "000000", OtpPurpose.LOGIN));
        assertEquals("654321", provider.peekIssuedOtpForTests("shop@example.com", OtpPurpose.LOGIN).orElseThrow());
    }

    @Test
    @DisplayName("an issued entry is gone after the send() expiry, not a hardcoded TTL")
    void issuedEntryExpiresAfterTheSendTtl() throws Exception {
        EmailOtpProvider provider = new EmailOtpProvider(null, "noreply@gpstore.co.in");
        provider.send("shop@example.com", OtpPurpose.LOGIN, Duration.ofMillis(80), "654321");
        assertTrue(provider.peekIssuedOtpForTests("shop@example.com", OtpPurpose.LOGIN).isPresent());

        Thread.sleep(120);
        provider.evictExpiredIssuedCodes();

        assertTrue(provider.peekIssuedOtpForTests("shop@example.com", OtpPurpose.LOGIN).isEmpty());
        assertFalse(provider.verify("shop@example.com", "654321", OtpPurpose.LOGIN));
    }

    @Test
    @DisplayName("maximumSize evicts older codes so the cache cannot grow without bound")
    void maximumSizeEvictsOlderCodes() {
        EmailOtpProvider provider = new EmailOtpProvider(null, "noreply@gpstore.co.in", 1);
        provider.send("one@example.com", OtpPurpose.LOGIN, Duration.ofMinutes(5), "111111");
        provider.send("two@example.com", OtpPurpose.LOGIN, Duration.ofMinutes(5), "222222");
        provider.evictExpiredIssuedCodes();

        assertEquals(1, provider.issuedCacheSize());
        assertTrue(provider.peekIssuedOtpForTests("one@example.com", OtpPurpose.LOGIN).isEmpty()
                || provider.peekIssuedOtpForTests("two@example.com", OtpPurpose.LOGIN).isEmpty());
    }

    @Test
    @DisplayName("resend refuses instead of logging success and sending nothing")
    void resendIsNotASilentSuccess() {
        EmailOtpProvider provider = new EmailOtpProvider(null, "noreply@gpstore.co.in");
        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> provider.resend("shop@example.com"));
        assertTrue(ex.getMessage().contains("requestOtp"));
    }

    @Test
    @DisplayName("Redis stores a hash, never the plaintext code")
    void redisStoresHashNotPlaintext() {
        Map<String, String> store = new ConcurrentHashMap<>();
        Map<String, Duration> ttls = new ConcurrentHashMap<>();
        StringRedisTemplate redis = fakeRedis(store, ttls);
        EmailOtpProvider provider = new EmailOtpProvider(null, "noreply@gpstore.co.in", redis);

        provider.send("shop@example.com", OtpPurpose.LOGIN, Duration.ofMinutes(5), "654321");

        assertEquals(1, store.size());
        String stored = store.values().iterator().next();
        assertNotEquals("654321", stored);
        assertEquals(OtpCodeHashes.sha256Hex("654321"), stored);
        assertFalse(store.values().stream().anyMatch(v -> v.contains("654321")));
        assertEquals(Duration.ofMinutes(5), ttls.values().iterator().next());
        assertTrue(store.keySet().iterator().next().startsWith(EmailOtpProvider.REDIS_KEY_PREFIX));
    }

    @Test
    @DisplayName("an OTP issued on one instance verifies on another via Redis")
    void otherInstanceVerifiesAgainstRedisHash() {
        Map<String, String> store = new ConcurrentHashMap<>();
        StringRedisTemplate redis = fakeRedis(store, new ConcurrentHashMap<>());
        EmailOtpProvider issuer = new EmailOtpProvider(null, "noreply@gpstore.co.in", redis);
        EmailOtpProvider other = new EmailOtpProvider(null, "noreply@gpstore.co.in", redis);

        issuer.send("shop@example.com", OtpPurpose.LOGIN, Duration.ofMinutes(5), "654321");
        assertTrue(other.peekIssuedOtpForTests("shop@example.com", OtpPurpose.LOGIN).isEmpty(),
                "other JVM must not see plaintext");
        assertTrue(other.verify("shop@example.com", "654321", OtpPurpose.LOGIN));
        assertTrue(store.isEmpty(), "successful verify must delete the Redis hash");

        EmailOtpProvider third = new EmailOtpProvider(null, "noreply@gpstore.co.in", redis);
        assertFalse(third.verify("shop@example.com", "654321", OtpPurpose.LOGIN),
                "a third instance has no Redis hash and no local entry");
    }

    private static StringRedisTemplate fakeRedis(Map<String, String> store, Map<String, Duration> ttls) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        org.mockito.Mockito.doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            ttls.put(inv.getArgument(0), inv.getArgument(2));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(inv -> store.remove(inv.getArgument(0)) != null);
        return redis;
    }

    private static EmailOtpProvider providerThatRecords(List<MimeMessage> sent) throws Exception {
        JavaMailSender mail = mock(JavaMailSender.class);
        when(mail.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        org.mockito.Mockito.doAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return null;
        }).when(mail).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
        return new EmailOtpProvider(mail, "noreply@gpstore.co.in");
    }
}
