package com.gpstore.otp;

import com.gpstore.auth.OtpPurpose;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
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
        JavaMailSender mail = mock(JavaMailSender.class);
        when(mail.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        org.mockito.Mockito.doAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return null;
        }).when(mail).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));

        EmailOtpProvider provider = new EmailOtpProvider(mail, "noreply@gpstore.co.in");
        provider.send("shop@example.com", OtpPurpose.LOGIN, Duration.ofMinutes(5), "654321");

        assertEquals(1, sent.size());
        assertEquals("Your GP Store verification code", sent.getFirst().getSubject());
        String body = (String) sent.getFirst().getContent();
        assertTrue(body.contains("654321"));
        assertFalse(sent.getFirst().getSubject().contains("654321"));
        assertTrue(provider.peekIssuedOtpForTests("shop@example.com", OtpPurpose.LOGIN).orElseThrow().equals("654321"));
    }
}
