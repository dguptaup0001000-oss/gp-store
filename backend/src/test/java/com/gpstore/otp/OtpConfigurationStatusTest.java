package com.gpstore.otp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The reading that tells a shopkeeper why nobody can log in.
 *
 * THE FAILURE THIS ANSWERS. When email OTP is misconfigured, every login and
 * every password reset shows "Unable to send OTP right now. Please try
 * again." - a message that deliberately says nothing, which is correct for a
 * stranger and useless for the person who owns the shop. The only explanation
 * was one WARN line written at startup on the VPS.
 *
 * Two things are being held in place here at once: that the reading is
 * TRUTHFUL about whether a send can succeed, and that fixing a login problem
 * never becomes a way to read the shop's SMTP password.
 */
@DisplayName("OTP configuration status")
class OtpConfigurationStatusTest {

    private static final String SECRET = "hunter2-not-a-real-password";
    private static final String AUTH_KEY = "msg91-secret-auth-key";

    private OtpConfigurationStatus email(OtpProvider provider, String host, String from) {
        return new OtpConfigurationStatus(
                provider, "EMAIL", host, from, "shop@gmail.com", SECRET,
                false, "", "");
    }

    @Test
    @DisplayName("an unconfigured provider says so, in one field")
    void unconfiguredIsVisible() {
        Map<String, Object> status =
                email(new UnconfiguredOtpProvider("test"), "", "").status();

        assertEquals(false, status.get("canSend"),
                "the whole point is that this is readable without SSH");
        assertEquals("unconfigured", status.get("provider"));

        @SuppressWarnings("unchecked")
        Map<String, Object> mail = (Map<String, Object>) status.get("email");
        assertEquals(false, mail.get("SMTP_HOST"));
        assertEquals(false, mail.get("SMTP_FROM"));
    }

    @Test
    @DisplayName("the hint names the variables as the VPS actually spells them")
    void theHintNamesRealVariables() {
        Map<String, Object> status =
                email(new UnconfiguredOtpProvider("test"), "", "").status();

        String hint = String.valueOf(status.get("hint"));
        // THE BUG THIS PINS. Every one of these messages used to say
        // OTP_EMAIL_FROM, which is not a variable this application reads -
        // application.properties maps otp.email.from to SMTP_FROM. An
        // operator following that instruction would set something with no
        // effect and stay broken.
        assertTrue(hint.contains("SMTP_FROM"), "hint must name SMTP_FROM: " + hint);
        assertFalse(hint.contains("OTP_EMAIL_FROM"),
                "OTP_EMAIL_FROM is not read by anything: " + hint);
    }

    @Test
    @DisplayName("a configured provider reports that it can send")
    void configuredIsVisibleToo() {
        Map<String, Object> status = email(
                new EmailOtpProvider(null, "shop@gmail.com", null),
                "smtp.gmail.com", "shop@gmail.com").status();

        assertEquals(true, status.get("canSend"));
        assertEquals("email", status.get("provider"));

        // AND IT DOES NOT OVERCLAIM. Present credentials can still be
        // refused by the mail server, which looks identical on the phone.
        assertTrue(String.valueOf(status.get("hint")).contains("OTP_SEND_FAILURE"),
                "a configured-but-failing shop must be pointed at the log line");
    }

    @Test
    @DisplayName("no secret value appears anywhere in the reading")
    void secretsAreNeverEchoed() {
        String rendered = email(
                new EmailOtpProvider(null, "shop@gmail.com", null),
                "smtp.gmail.com", "shop@gmail.com").status().toString();

        assertFalse(rendered.contains(SECRET),
                "the SMTP password reached an admin status page");

        Map<String, Object> sms = new OtpConfigurationStatus(
                new UnconfiguredOtpProvider("test"), "SMS", "", "", "", "",
                true, AUTH_KEY, "template-1").status();
        assertFalse(sms.toString().contains(AUTH_KEY),
                "the MSG91 auth key reached an admin status page");
    }

    @Test
    @DisplayName("presence is reported without the value")
    void presenceNotValue() {
        @SuppressWarnings("unchecked")
        Map<String, Object> mail = (Map<String, Object>) email(
                new EmailOtpProvider(null, "shop@gmail.com", null),
                "smtp.gmail.com", "shop@gmail.com").status().get("email");

        // Booleans, not strings - a field that could hold a value is a field
        // that will eventually hold the wrong one.
        assertInstanceOf(Boolean.class, mail.get("SMTP_PASSWORD"));
        assertEquals(true, mail.get("SMTP_PASSWORD"));
        assertEquals(true, mail.get("SMTP_HOST"));
    }
}
