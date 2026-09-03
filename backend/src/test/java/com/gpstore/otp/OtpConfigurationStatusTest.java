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

    private static final String AUTH_KEY = "msg91-secret-auth-key";

    private OtpConfigurationStatus email(OtpProvider provider, String host, String from) {
        return new OtpConfigurationStatus(
                provider, "EMAIL", host, from, false, "", "");
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
    @DisplayName("a secret handed in never comes back out")
    void secretsAreNeverEchoed() {
        // ONLY THE MSG91 KEY IS TESTED HERE, and that is not an oversight.
        // This class no longer takes the SMTP password at all - the field was
        // removed rather than merely kept out of the output - so asserting it
        // does not appear would be a test that cannot fail. The auth key IS
        // still passed in, because MSG91_AUTH_KEY's presence is part of the
        // reading, so it is the one that needs holding down.
        Map<String, Object> sms = new OtpConfigurationStatus(
                new UnconfiguredOtpProvider("test"), "SMS", "", "",
                true, AUTH_KEY, "template-1").status();

        assertFalse(sms.toString().contains(AUTH_KEY),
                "the MSG91 auth key reached an admin status page");
        assertEquals(true, ((Map<?, ?>) sms.get("sms")).get("MSG91_AUTH_KEY"),
                "presence must still be reported - a boolean, not the key");
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
        assertInstanceOf(Boolean.class, mail.get("SMTP_HOST"));
        assertEquals(true, mail.get("SMTP_HOST"));
        assertEquals(true, mail.get("SMTP_FROM"));
    }

    @Test
    @DisplayName("the word password appears nowhere, not even as a field name")
    void noFieldIsCalledPassword() {
        // AccessDeniedStatusTest asserts an admin ops body contains no
        // "password" anywhere, and it caught an earlier version of this class
        // reporting SMTP_PASSWORD as a boolean - the key name alone was
        // enough to trip it. Held here too, next to the code, so the next
        // person to add a field finds out before the ops endpoint does.
        String rendered = email(
                new EmailOtpProvider(null, "shop@gmail.com", null),
                "smtp.gmail.com", "shop@gmail.com").status().toString().toLowerCase();

        assertFalse(rendered.contains("password"), rendered);
        assertFalse(rendered.contains("passphrase"), rendered);
    }
}
