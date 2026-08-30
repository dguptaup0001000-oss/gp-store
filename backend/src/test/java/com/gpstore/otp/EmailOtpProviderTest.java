package com.gpstore.otp;

import com.gpstore.auth.OtpPurpose;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
