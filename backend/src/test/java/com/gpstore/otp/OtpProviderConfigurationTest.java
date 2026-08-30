package com.gpstore.otp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtpProviderConfigurationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration T = Duration.ofSeconds(2);

    @Test
    void productionWithoutMsg91UsesUnconfiguredNotMock() {
        OtpProvider provider = OtpProviderConfiguration.create(
                true, false, "https://control.msg91.com",
                "", "", "GPSTOR", T, T, MAPPER);
        assertInstanceOf(UnconfiguredOtpProvider.class, provider);
        assertFalse(provider instanceof MockOtpProvider);
    }

    @Test
    void productionWithMsg91FlagButMissingCredentialsStillBootsUnconfigured() {
        OtpProvider missingKey = OtpProviderConfiguration.create(
                true, true, "https://control.msg91.com",
                "", "template", "GPSTOR", T, T, MAPPER);
        OtpProvider missingTemplate = OtpProviderConfiguration.create(
                true, true, "https://control.msg91.com",
                "key", "", "GPSTOR", T, T, MAPPER);
        assertInstanceOf(UnconfiguredOtpProvider.class, missingKey);
        assertInstanceOf(UnconfiguredOtpProvider.class, missingTemplate);
    }

    @Test
    void productionWithCredentialsUsesMsg91() {
        OtpProvider provider = OtpProviderConfiguration.create(
                true, true, "https://control.msg91.com",
                "key", "template", "GPSTOR", T, T, MAPPER);
        assertInstanceOf(Msg91OtpProvider.class, provider);
    }

    @Test
    void nonProductionWithoutMsg91UsesMock() {
        OtpProvider provider = OtpProviderConfiguration.create(
                false, false, "https://control.msg91.com",
                "", "", "GPSTOR", T, T, MAPPER);
        assertInstanceOf(MockOtpProvider.class, provider);
    }

    @Test
    void nonProductionEnabledWithoutCredentialsRefusesToStart() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                OtpProviderConfiguration.create(false, true, "https://control.msg91.com",
                        "", "template", "GPSTOR", T, T, MAPPER));
        assertTrue(ex.getMessage().contains("MSG91_AUTH_KEY"));
    }

    @Test
    void emailChannelUsesEmailProviderLocallyWithoutSmtp() {
        OtpProvider provider = OtpProviderConfiguration.createEmail(
                false, "", "", null);
        assertInstanceOf(EmailOtpProvider.class, provider);
        assertTrue(provider.issuesLocalCode());
    }

    @Test
    void emailChannelPassesRedisThroughToTheProvider() {
        org.springframework.data.redis.core.StringRedisTemplate redis =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> ops =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.when(redis.opsForValue()).thenReturn(ops);
        OtpProvider provider = OtpProviderConfiguration.createEmail(
                false, "", "", null, redis);
        provider.send("shop@example.com", com.gpstore.auth.OtpPurpose.LOGIN, Duration.ofMinutes(5), "654321");
        org.mockito.Mockito.verify(ops).set(
                org.mockito.ArgumentMatchers.startsWith(EmailOtpProvider.REDIS_KEY_PREFIX),
                org.mockito.ArgumentMatchers.eq(OtpCodeHashes.sha256Hex("654321")),
                org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(5)));
    }

    @Test
    void emailChannelProductionWithoutSmtpIsUnconfigured() {
        OtpProvider provider = OtpProviderConfiguration.createEmail(
                true, "", "", null);
        assertInstanceOf(UnconfiguredOtpProvider.class, provider);
    }

    @Test
    void unconfiguredSendFailsClosedWithoutIssuingACode() {
        UnconfiguredOtpProvider provider = new UnconfiguredOtpProvider();
        OtpProviderException ex = assertThrows(OtpProviderException.class, () ->
                provider.send("919876543210", com.gpstore.auth.OtpPurpose.LOGIN, Duration.ofMinutes(5), "123456"));
        assertTrue(ex.getMessage().contains("Unable to send OTP"));
        assertTrue(provider.peekIssuedOtpForTests(
                "919876543210", com.gpstore.auth.OtpPurpose.LOGIN).isEmpty());
        assertFalse(provider.verify("919876543210", "123456", com.gpstore.auth.OtpPurpose.LOGIN));
    }
}
