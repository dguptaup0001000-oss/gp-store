package com.gpstore.payment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gpstore.enums.PaymentProvider;
import com.gpstore.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The only class in this application that knows what Cashfree is.
 *
 * java.net.http.HttpClient rather than RestTemplate or WebClient, matching
 * SmsService - the one other outbound HTTP caller here. A payment
 * integration is not the place to introduce a third HTTP stack into a
 * service running on 0.5 vCPU.
 *
 * WHAT LEAVES THIS CLASS is a providerOrderId and a paymentSessionId. The
 * client id and secret never appear in a return value, an exception message
 * or a log line, so there is no path by which a credential reaches the
 * Flutter app - a property of what the methods return, not a rule someone
 * has to remember.
 */
@Component
public class CashfreeGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(CashfreeGateway.class);

    private final CashfreeProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public CashfreeGateway(CashfreeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .build();
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.CASHFREE;
    }

    @Override
    public GatewaySession createSession(GatewaySessionRequest request) {
        requireConfigured();

        JsonNode response = send("POST", "/orders", buildOrderBody(request).toString());
        return readSession(response, request.providerOrderId());
    }

    /**
     * The exact JSON sent to Cashfree, built separately from the call that
     * sends it.
     *
     * EXTRACTED SO IT CAN BE TESTED WITHOUT A NETWORK. Every field name here
     * has to match Cashfree's schema exactly, and the first thing that tells
     * you otherwise is a rejected live transaction - by which point a real
     * customer is looking at a failure. Package-private rather than public:
     * nothing outside this package needs to build a Cashfree order, and the
     * test lives in this package.
     */
    ObjectNode buildOrderBody(GatewaySessionRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("order_id", request.providerOrderId());
        // Scale 2 explicitly. Cashfree compares the amount it settles against
        // the amount we asked for, and BigDecimal's toString can emit
        // scientific notation for some values - "1E+2" is not 100.00 to a
        // JSON number parser on the other side.
        body.put("order_amount", request.amount().setScale(2, java.math.RoundingMode.HALF_UP));
        body.put("order_currency", request.currency());

        ObjectNode customer = body.putObject("customer_details");
        customer.put("customer_id", request.customerId());
        customer.put("customer_phone", request.customerPhone());
        if (request.customerName() != null && !request.customerName().isBlank()) {
            customer.put("customer_name", request.customerName());
        }
        if (request.customerEmail() != null && !request.customerEmail().isBlank()) {
            customer.put("customer_email", request.customerEmail());
        }

        ObjectNode meta = body.putObject("order_meta");
        if (request.returnUrl() != null && !request.returnUrl().isBlank()) {
            meta.put("return_url", request.returnUrl());
        }
        if (request.notifyUrl() != null && !request.notifyUrl().isBlank()) {
            meta.put("notify_url", request.notifyUrl());
        }

        return body;
    }

    private GatewaySession readSession(JsonNode response, String requestedOrderId) {
        String sessionId = text(response, "payment_session_id");
        String orderId = text(response, "order_id");
        if (sessionId == null || sessionId.isBlank()) {
            // Without a session id the client has nothing to open. Failing
            // here keeps the order in its pending state rather than handing
            // the app a half-built checkout.
            throw new BadRequestException("Payment could not be started. Please try again.");
        }

        return new GatewaySession(orderId != null ? orderId : requestedOrderId, sessionId);
    }

    @Override
    public GatewayOrderStatus fetchOrderStatus(String providerOrderId) {
        requireConfigured();

        JsonNode response = send("GET", "/orders/" + providerOrderId, null);

        String status = text(response, "order_status");
        BigDecimal amount = response.hasNonNull("order_amount")
                ? response.get("order_amount").decimalValue()
                : null;

        return new GatewayOrderStatus(
                providerOrderId,
                // Cashfree returns the payment id on the payments resource
                // rather than the order; the webhook carries it. Null here is
                // expected and callers must tolerate it.
                null,
                mapState(status),
                amount,
                text(response, "order_currency"),
                null);
    }

    @Override
    public GatewayRefund requestRefund(GatewayRefundRequest request) {
        requireConfigured();

        JsonNode response = send("POST",
                "/orders/" + request.providerOrderId() + "/refunds",
                buildRefundBody(request).toString(),
                "The refund could not be sent to the payment provider. Nothing has been refunded - try again.");
        return readRefund(response, request.refundId());
    }

    @Override
    public GatewayRefund fetchRefund(String providerOrderId, String refundId) {
        requireConfigured();

        JsonNode response = send("GET", "/orders/" + providerOrderId + "/refunds/" + refundId, null,
                "Could not check the refund with the payment provider. Try again in a moment.");
        return readRefund(response, refundId);
    }

    /**
     * The refund body, extracted for the same reason as buildOrderBody: a
     * wrong field name here is only discovered by a live refund failing, with
     * a customer already waiting for their money.
     *
     * refund_id IS THE IDEMPOTENCY KEY. Cashfree rejects a second refund
     * using an id it has already seen, which is exactly what should happen
     * when this call is retried after a timeout - the shop must not send the
     * money twice.
     */
    ObjectNode buildRefundBody(GatewayRefundRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        // Scale 2 for the same reason as the order amount: a BigDecimal in
        // scientific notation is not a number Cashfree will match.
        body.put("refund_amount", request.amount().setScale(2, java.math.RoundingMode.HALF_UP));
        body.put("refund_id", request.refundId());
        if (request.note() != null && !request.note().isBlank()) {
            body.put("refund_note", request.note());
        }
        return body;
    }

    private GatewayRefund readRefund(JsonNode response, String requestedRefundId) {
        String refundId = text(response, "refund_id");
        return new GatewayRefund(
                refundId != null ? refundId : requestedRefundId,
                text(response, "cf_refund_id"),
                mapRefundState(text(response, "refund_status")),
                response.hasNonNull("refund_amount") ? response.get("refund_amount").decimalValue() : null,
                text(response, "status_description"));
    }

    /**
     * Cashfree's refund_status vocabulary, normalised.
     *
     * ONLY "SUCCESS" IS SUCCESS. PENDING and ONHOLD both mean the money has
     * not reached the customer yet, and an unrecognised value maps to UNKNOWN
     * rather than to success - so a vocabulary change at the provider leaves a
     * refund waiting for a human instead of silently marking it done.
     */
    public static GatewayRefund.State mapRefundState(String refundStatus) {
        if (refundStatus == null) return GatewayRefund.State.UNKNOWN;
        return switch (refundStatus.toUpperCase()) {
            case "SUCCESS" -> GatewayRefund.State.SUCCEEDED;
            case "PENDING", "ONHOLD" -> GatewayRefund.State.PENDING;
            case "CANCELLED" -> GatewayRefund.State.CANCELLED;
            case "FAILED" -> GatewayRefund.State.FAILED;
            default -> GatewayRefund.State.UNKNOWN;
        };
    }

    /**
     * Cashfree's order_status vocabulary, normalised.
     *
     * ACTIVE means the session is live and the customer has not finished -
     * deliberately NOT treated as failure. Mapping "not paid yet" to failed
     * is how a customer who is still typing an OTP gets their order
     * cancelled underneath them.
     */
    static GatewayOrderStatus.State mapState(String orderStatus) {
        if (orderStatus == null) return GatewayOrderStatus.State.UNKNOWN;
        return switch (orderStatus.toUpperCase()) {
            case "PAID" -> GatewayOrderStatus.State.PAID;
            case "ACTIVE" -> GatewayOrderStatus.State.ACTIVE;
            case "EXPIRED" -> GatewayOrderStatus.State.EXPIRED;
            case "TERMINATED", "TERMINATION_REQUESTED" -> GatewayOrderStatus.State.CANCELLED;
            case "FAILED" -> GatewayOrderStatus.State.FAILED;
            default -> GatewayOrderStatus.State.UNKNOWN;
        };
    }

    private JsonNode send(String method, String path, String jsonBody) {
        return send(method, path, jsonBody, "Payment could not be started. Please try again.");
    }

    /**
     * @param failureMessage what the caller sees when Cashfree refuses or is
     *     unreachable. A refund that fails must not tell a shopkeeper their
     *     PAYMENT could not be started - they would retry the wrong thing.
     */
    private JsonNode send(String method, String path, String jsonBody, String failureMessage) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(properties.baseUrl() + path))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("x-api-version", properties.getApiVersion())
                .header("x-client-id", properties.getAppId())
                .header("x-client-secret", properties.getSecretKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");

        builder = "GET".equals(method)
                ? builder.GET()
                : builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody));

        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                // The status code and Cashfree's own message, and nothing
                // else. The request headers carry the secret; logging the
                // request would put it in the log.
                log.error("Cashfree {} {} failed: HTTP {} {}", method, path, response.statusCode(),
                        safeMessage(response.body()));
                throw new BadRequestException(failureMessage);
            }

            return objectMapper.readTree(response.body());
        } catch (BadRequestException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequestException(failureMessage);
        } catch (Exception e) {
            log.error("Cashfree {} {} error: {}", method, path, e.getClass().getSimpleName());
            throw new BadRequestException(failureMessage);
        }
    }

    /** Cashfree's error message only - never the whole body, which echoes the request. */
    private String safeMessage(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            String message = text(node, "message");
            return message == null ? "(no message)" : message;
        } catch (Exception e) {
            return "(unparseable error body)";
        }
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private void requireConfigured() {
        if (!properties.enabled()) {
            throw new BadRequestException("Online payment is not available right now.");
        }
    }
}
