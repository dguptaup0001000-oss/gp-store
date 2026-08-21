package com.gpstore.payment.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cashfree credentials and environment, entirely from configuration.
 *
 * NOTHING HERE HAS A CREDENTIAL DEFAULT. appId, secretKey and webhookSecret
 * default to empty, and an empty secret disables the gateway rather than
 * silently starting with a blank key - see enabled(). A default that "works
 * in dev" is how a shared sandbox credential ends up in git.
 *
 * SANDBOX AND PRODUCTION differ only by baseUrl, which is derived from the
 * environment name rather than pasted in. Two properties that must agree
 * (a production URL with sandbox keys) is a mistake nobody notices until
 * real money is involved.
 */
@Component
@ConfigurationProperties(prefix = "cashfree")
public class CashfreeProperties {

    private static final String SANDBOX_BASE = "https://sandbox.cashfree.com/pg";
    private static final String PRODUCTION_BASE = "https://api.cashfree.com/pg";

    /** "sandbox" or "production". Anything unrecognised is treated as sandbox. */
    private String environment = "sandbox";

    private String appId = "";
    private String secretKey = "";

    /**
     * The secret Cashfree signs webhooks with.
     *
     * Kept separate from secretKey even though Cashfree currently signs with
     * the same value: they are different responsibilities, and if the
     * dashboard ever lets them diverge, this configuration already can.
     */
    private String webhookSecret = "";

    /**
     * Current API version. A header value, not a URL segment, so it is
     * bumped here rather than by editing endpoint strings in several places.
     */
    private String apiVersion = "2025-01-01";

    /** Where Cashfree posts webhooks - the app's own public base URL. */
    private String notifyUrl = "";

    /** Where the checkout returns the customer. */
    private String returnUrl = "";

    /** Seconds to wait on a Cashfree call before giving up. */
    private int timeoutSeconds = 10;

    public boolean isProduction() {
        return "production".equalsIgnoreCase(environment);
    }

    public String baseUrl() {
        return isProduction() ? PRODUCTION_BASE : SANDBOX_BASE;
    }

    /**
     * Whether the gateway can be used at all.
     *
     * Checked before every call so that a deployment with no credentials
     * fails with a clear "online payment is not configured" instead of
     * sending Cashfree an empty client id and surfacing whatever it says
     * about that to a customer at checkout.
     */
    public boolean enabled() {
        return !appId.isBlank() && !secretKey.isBlank();
    }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }

    public String getApiVersion() { return apiVersion; }
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }

    public String getNotifyUrl() { return notifyUrl; }
    public void setNotifyUrl(String notifyUrl) { this.notifyUrl = notifyUrl; }

    public String getReturnUrl() { return returnUrl; }
    public void setReturnUrl(String returnUrl) { this.returnUrl = returnUrl; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
