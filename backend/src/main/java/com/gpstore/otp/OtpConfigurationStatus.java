package com.gpstore.otp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Whether OTP can actually be sent, in a form the shopkeeper can look at.
 *
 * WHY THIS EXISTS. When email OTP is misconfigured, every customer trying to
 * log in or reset a password sees "Unable to send OTP right now" and nothing
 * else. The reason lives in one WARN line written at startup, which means
 * finding it requires SSH access to the VPS and knowing to look - and the
 * person who most needs to know is the shopkeeper, who has neither.
 *
 * The failure is deliberately generic to the CUSTOMER, and that is right: an
 * error page must not describe the shop's mail setup to a stranger. But the
 * operator is not a stranger, this is behind the admin permission that
 * already guards every other ops reading, and "login is broken and no
 * screen anywhere will say why" is not a state a shop should be left in.
 *
 * WHAT IT WILL NOT REPORT. No password, no auth key, and no value of any
 * secret - only whether each one is present. A status page that leaks the
 * SMTP password to fix a login problem has made things worse.
 */
@Component
public class OtpConfigurationStatus {

    private final OtpProvider provider;
    private final String channel;
    private final String smtpHost;
    private final String emailFrom;
    private final boolean msg91Enabled;
    private final String msg91AuthKey;
    private final String msg91TemplateId;

    public OtpConfigurationStatus(
            OtpProvider provider,
            @Value("${otp.channel:EMAIL}") String channel,
            @Value("${spring.mail.host:}") String smtpHost,
            @Value("${otp.email.from:}") String emailFrom,
            @Value("${msg91.enabled:false}") boolean msg91Enabled,
            @Value("${msg91.auth-key:}") String msg91AuthKey,
            @Value("${msg91.otp-template-id:}") String msg91TemplateId) {
        this.provider = provider;
        this.channel = channel;
        this.smtpHost = smtpHost;
        this.emailFrom = emailFrom;
        this.msg91Enabled = msg91Enabled;
        this.msg91AuthKey = msg91AuthKey;
        this.msg91TemplateId = msg91TemplateId;
    }

    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();

        boolean unconfigured = provider instanceof UnconfiguredOtpProvider;
        body.put("channel", OtpChannel.from(channel).name());
        body.put("provider", providerName());

        // THE ONE LINE THAT ANSWERS THE QUESTION. False here means every OTP
        // send is refused before it reaches a mail server, and no amount of
        // retrying on the phone will change it.
        body.put("canSend", !unconfigured);

        if (OtpChannel.from(channel) == OtpChannel.EMAIL) {
            Map<String, Object> email = new LinkedHashMap<>();
            // PRESENCE, NOT VALUE, for every one of these. The names are the
            // environment variables as they are actually spelled on the VPS,
            // so somebody reading this knows exactly what to go and set.
            email.put("SMTP_HOST", present(smtpHost));
            email.put("SMTP_FROM", present(emailFrom));
            // SMTP_USERNAME AND SMTP_PASSWORD ARE DELIBERATELY ABSENT, for
            // two reasons that point the same way.
            //
            // They do not decide anything this reading answers: canSend is
            // SMTP_HOST + SMTP_FROM + a mail sender, and credentials that are
            // present but wrong fail later, at the mail server, where the
            // send-failure log now carries the cause.
            //
            // And AccessDeniedStatusTest asserts that no admin ops body
            // contains the word "password" anywhere. That guard is blunt on
            // purpose and it caught this - the KEY name alone tripped it,
            // even reporting a boolean. Renaming the field to slip past a
            // security check would be the wrong way round; the field simply
            // is not needed.
            body.put("email", email);
        } else {
            Map<String, Object> sms = new LinkedHashMap<>();
            sms.put("MSG91_ENABLED", msg91Enabled);
            sms.put("MSG91_AUTH_KEY", present(msg91AuthKey));
            sms.put("MSG91_OTP_TEMPLATE_ID", present(msg91TemplateId));
            body.put("sms", sms);
        }

        if (unconfigured) {
            body.put("hint", OtpChannel.from(channel) == OtpChannel.EMAIL
                    ? "Set SMTP_HOST and SMTP_FROM on the VPS, then restart the backend."
                    : "Set MSG91_AUTH_KEY and MSG91_OTP_TEMPLATE_ID on the VPS, then restart the backend.");
        } else {
            // CONFIGURED IS NOT THE SAME AS WORKING. The credentials can be
            // present and still be rejected by the mail server, which is a
            // different failure with the same message on the phone - and it
            // is only visible in the log line the send path writes.
            body.put("hint", "Configured. If sends still fail, the mail server is refusing them - "
                    + "look for OTP_SEND_FAILURE in the backend log; it now carries the cause.");
        }

        return body;
    }

    private String providerName() {
        if (provider instanceof UnconfiguredOtpProvider) return "unconfigured";
        if (provider instanceof EmailOtpProvider) return "email";
        if (provider instanceof Msg91OtpProvider) return "msg91";
        if (provider instanceof MockOtpProvider) return "mock";
        return provider.getClass().getSimpleName();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
