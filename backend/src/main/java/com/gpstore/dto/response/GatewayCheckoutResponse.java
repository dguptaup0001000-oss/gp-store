package com.gpstore.dto.response;

import java.math.BigDecimal;

/**
 * Everything the Flutter app is given to open a gateway checkout, and
 * nothing else.
 *
 * WHAT IS DELIBERATELY ABSENT: the client id, the secret key, the webhook
 * secret, any signature, any header. A payment session id is a
 * single-purpose, short-lived token for exactly one order - handing it to
 * the app is safe in a way that handing it a credential never is.
 *
 * The amount is included for display only. It is the figure the BACKEND
 * computed and already sent to Cashfree; the app renders it and has no way
 * to alter what will actually be charged.
 */
public class GatewayCheckoutResponse {

    private final Long orderId;
    private final Long paymentId;
    private final String provider;
    private final String providerOrderId;
    private final String paymentSessionId;
    private final BigDecimal amount;
    private final String currency;
    /** "sandbox" or "production" - the SDK needs to know which to open. */
    private final String environment;

    public GatewayCheckoutResponse(Long orderId, Long paymentId, String provider, String providerOrderId,
                                   String paymentSessionId, BigDecimal amount, String currency,
                                   String environment) {
        this.orderId = orderId;
        this.paymentId = paymentId;
        this.provider = provider;
        this.providerOrderId = providerOrderId;
        this.paymentSessionId = paymentSessionId;
        this.amount = amount;
        this.currency = currency;
        this.environment = environment;
    }

    public Long getOrderId() { return orderId; }
    public Long getPaymentId() { return paymentId; }
    public String getProvider() { return provider; }
    public String getProviderOrderId() { return providerOrderId; }
    public String getPaymentSessionId() { return paymentSessionId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getEnvironment() { return environment; }
}
