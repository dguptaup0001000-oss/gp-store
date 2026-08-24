package com.gpstore.otp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtpProviderConfigurationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration T = Duration.ofSeconds(2);

    @Test
    void productionRefusesTheMock() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                OtpProviderConfiguration.create(true, false, "https://control.msg91.com",
                        "", "", "GPSTOR", T, T, MAPPER));
        assertTrue(ex.getMessage().toLowerCase().contains("production"));
    }

    @Test
    void productionRefusesMissingCredentials() {
        assertThrows(IllegalStateException.class, () ->
                OtpProviderConfiguration.create(true, true, "https://control.msg91.com",
                        "", "template", "GPSTOR", T, T, MAPPER));
        assertThrows(IllegalStateException.class, () ->
                OtpProviderConfiguration.create(true, true, "https://control.msg91.com",
                        "key", "", "GPSTOR", T, T, MAPPER));
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
}
