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
