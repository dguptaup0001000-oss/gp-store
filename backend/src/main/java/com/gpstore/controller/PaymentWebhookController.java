package com.gpstore.controller;

import com.gpstore.payment.GatewayPaymentService;
import com.gpstore.payment.GatewayPaymentService.WebhookResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Where Cashfree posts payment events.
 *
 * PUBLIC BY NECESSITY, AUTHENTICATED BY SIGNATURE. Cashfree cannot obtain a
 * JWT, so this path is permitAll in SecurityConfig - and the HMAC check
 * inside is therefore the only thing distinguishing a real event from
 * anyone on the internet posting JSON that says an order was paid. Nothing
 * in this class runs before that check.
 *
 * @RequestBody String, NOT a DTO. The signature covers the exact bytes
 * Cashfree sent; letting Spring parse to an object and re-serializing it
 * produces different bytes and a signature that never matches. This is the
 * single most common way a webhook integration fails.
 */
@RestController
@RequestMapping("/api/payments/webhooks")
public class PaymentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

    private final GatewayPaymentService gatewayPaymentService;

    public PaymentWebhookController(GatewayPaymentService gatewayPaymentService) {
        this.gatewayPaymentService = gatewayPaymentService;
    }

    @PostMapping("/cashfree")
    public ResponseEntity<String> cashfree(
            @RequestBody(required = false) String rawBody,
            @RequestHeader(value = "x-webhook-signature", required = false) String signature,
            @RequestHeader(value = "x-webhook-timestamp", required = false) String timestamp) {

        try {
            WebhookResult result = gatewayPaymentService.applyWebhook(rawBody, signature, timestamp);

            if (!result.accepted()) {
                // 401, and deliberately terse. An attacker probing this
                // endpoint learns only that it was refused - not which check
                // failed, not what a valid payload looks like.
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("rejected");
            }

            // 2xx for everything we understood, INCLUDING events that changed
            // nothing - an unknown order, an event type we do not act on.
            // Answering non-2xx to those makes Cashfree retry something that
            // can never succeed, forever.
            return ResponseEntity.ok("ok");

        } catch (GatewayPaymentService.DuplicateEventException e) {
            // A retry of an event already applied. The transaction rolled
            // back, so nothing was applied twice - which is success from
            // Cashfree's point of view, and must be answered 2xx or it will
            // keep retrying.
            return ResponseEntity.ok("duplicate");

        } catch (Exception e) {
            // 500 ON PURPOSE. This is the one case where a retry is what we
            // want: the database was briefly unavailable, or the order
            // update failed. Cashfree redelivers, and the dedup makes the
            // redelivery safe. Answering 200 here would silently drop a real
            // payment - the exact failure the brief calls out.
            log.error("Cashfree webhook processing failed, asking for redelivery: {}",
                    e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("retry");
        }
    }
}
