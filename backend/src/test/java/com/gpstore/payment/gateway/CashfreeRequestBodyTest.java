package com.gpstore.payment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpstore.exception.BadRequestException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The exact JSON GP-Store sends to Cashfree.
 *
 * WHY THIS IS THE MOST VALUABLE CASHFREE TEST WE CAN WRITE WITHOUT A NETWORK.
 * No live transaction has ever run, so every field name in the request is an
 * unverified assumption. If "order_amount" were spelled "amount", nothing in
 * the codebase would notice - the first thing to say otherwise would be a
 * rejected payment with a real customer watching. This pins the wire format so
 * a rename or a refactor cannot silently change what goes over the wire.
 *
 * It does NOT prove Cashfree accepts this shape. That is still LIVE SANDBOX
 * territory, and it is stated as such in the report. What it proves is that
 * the shape we intend to send is the shape we actually build.
 */
class CashfreeRequestBodyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private CashfreeGateway configuredGateway() {
        CashfreeProperties props = new CashfreeProperties();
        props.setAppId("test-app-id");
        props.setSecretKey("test-secret-key");
        props.setEnvironment("sandbox");
        return new CashfreeGateway(props, mapper);
    }

    private PaymentGateway.GatewaySessionRequest request(BigDecimal amount) {
        return new PaymentGateway.GatewaySessionRequest(
                "GP-1234-7f3a9b", amount, "INR",
                "cust-42", "9000000000", "Test Customer", "test@example.invalid",
                "https://gp-store.example/return", "https://gp-store.example/webhook");
    }

    @Test
    @DisplayName("the order fields carry the names Cashfree's API expects")
    void fieldNamesMatchTheApi() {
        JsonNode body = configuredGateway().buildOrderBody(request(new BigDecimal("199.00")));

        assertTrue(body.has("order_id"), "order_id missing");
        assertTrue(body.has("order_amount"), "order_amount missing");
        assertTrue(body.has("order_currency"), "order_currency missing");
        assertTrue(body.has("customer_details"), "customer_details missing");

        assertEquals("GP-1234-7f3a9b", body.get("order_id").asText());
        assertEquals("INR", body.get("order_currency").asText());
    }

    @Test
    @DisplayName("the amount is sent at scale 2, never in scientific notation")
    void amountIsAlwaysTwoDecimalPlaces() {
        // BigDecimal.toString can emit "1E+2" for some values, and "1E+2" is
        // not 100.00 to a JSON number parser on the other side. Cashfree
        // compares what it settles against what we asked for, so a
        // misformatted amount is a mismatched payment.
        JsonNode plain = configuredGateway().buildOrderBody(request(new BigDecimal("1E+2")));
        assertEquals("100.00", plain.get("order_amount").asText(),
                "scientific notation reached the request body");

        JsonNode rounded = configuredGateway().buildOrderBody(request(new BigDecimal("199.999")));
        assertEquals("200.00", rounded.get("order_amount").asText(),
                "HALF_UP to two places, matching how the order total was computed");

        JsonNode whole = configuredGateway().buildOrderBody(request(new BigDecimal("50")));
        assertEquals("50.00", whole.get("order_amount").asText());
    }

    @Test
    @DisplayName("customer details are nested where Cashfree expects them")
    void customerDetailsAreNested() {
        JsonNode customer = configuredGateway()
                .buildOrderBody(request(new BigDecimal("199.00"))).get("customer_details");

        assertEquals("cust-42", customer.get("customer_id").asText());
        assertEquals("9000000000", customer.get("customer_phone").asText());
        assertEquals("Test Customer", customer.get("customer_name").asText());
        assertEquals("test@example.invalid", customer.get("customer_email").asText());
    }

    @Test
    @DisplayName("optional customer fields are OMITTED rather than sent as null or empty")
    void blankOptionalFieldsAreOmitted() {
        // An empty string is not the same as an absent field to a strict API,
        // and a customer who registered by phone has no email at all. Sending
        // "" would be a validation error on a payment that should have worked.
        PaymentGateway.GatewaySessionRequest phoneOnly = new PaymentGateway.GatewaySessionRequest(
                "GP-1-abc", new BigDecimal("10.00"), "INR",
                "cust-1", "9000000000", "  ", "",
                "https://r", "https://n");

        JsonNode customer = configuredGateway().buildOrderBody(phoneOnly).get("customer_details");

        assertFalse(customer.has("customer_name"), "a blank name was sent instead of omitted");
        assertFalse(customer.has("customer_email"), "an empty email was sent instead of omitted");
        assertTrue(customer.has("customer_phone"), "phone is required by Cashfree and must always be present");
    }

    @Test
    @DisplayName("the webhook and return URLs travel in order_meta")
    void callbackUrlsAreInOrderMeta() {
        JsonNode meta = configuredGateway()
                .buildOrderBody(request(new BigDecimal("199.00"))).get("order_meta");

        assertNotNull(meta, "order_meta missing - Cashfree would never call the webhook");
        assertEquals("https://gp-store.example/webhook", meta.get("notify_url").asText());
        assertEquals("https://gp-store.example/return", meta.get("return_url").asText());
    }

    @Test
    @DisplayName("an unconfigured gateway refuses instead of calling Cashfree with empty credentials")
    void unconfiguredGatewayRefuses() {
        // Fails CLOSED. The alternative - attempting the call with blank
        // credentials - produces an authentication error from Cashfree that
        // surfaces to the customer as a generic failure, and costs a round
        // trip to learn something already knowable locally.
        CashfreeProperties empty = new CashfreeProperties();
        CashfreeGateway gateway = new CashfreeGateway(empty, mapper);

        assertFalse(empty.enabled(), "blank credentials must not count as configured");
        assertThrows(BadRequestException.class,
                () -> gateway.createSession(request(new BigDecimal("199.00"))),
                "an unconfigured gateway attempted a real call");
    }

    @Test
    @DisplayName("sandbox and production point at different hosts")
    void environmentSelectsTheRightHost() {
        CashfreeProperties sandbox = new CashfreeProperties();
        sandbox.setEnvironment("sandbox");
        assertTrue(sandbox.baseUrl().contains("sandbox.cashfree.com"),
                "sandbox must not talk to the production API");
        assertFalse(sandbox.isProduction());

        CashfreeProperties production = new CashfreeProperties();
        production.setEnvironment("production");
        assertTrue(production.baseUrl().contains("api.cashfree.com"));
        assertTrue(production.isProduction());

        // Anything unrecognised must NOT be treated as production - a typo in
        // an environment variable should degrade to sandbox, never to live
        // money.
        CashfreeProperties typo = new CashfreeProperties();
        typo.setEnvironment("prod-uction");
        assertFalse(typo.isProduction(), "an unrecognised environment was treated as production");
    }
}
