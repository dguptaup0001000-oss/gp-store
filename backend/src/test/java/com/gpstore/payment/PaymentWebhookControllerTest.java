package com.gpstore.payment;

import com.gpstore.entity.PaymentProviderEvent.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
class PaymentWebhookControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private GatewayPaymentService gatewayPaymentService;

    @Test
    @DisplayName("a rejected signature is 401")
    void rejectedSignatureIsUnauthorized() throws Exception {
        when(gatewayPaymentService.applyWebhook(any(), any(), any()))
                .thenReturn(new GatewayPaymentService.WebhookResult(false, "INVALID_SIGNATURE", null));

        mockMvc.perform(post("/api/payments/webhooks/cashfree")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-webhook-signature", "nope")
                        .header("x-webhook-timestamp", "1")
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("rejected"));
    }

    @Test
    @DisplayName("a valid webhook is 200 ok")
    void validWebhookIsOk() throws Exception {
        when(gatewayPaymentService.applyWebhook(any(), any(), any()))
                .thenReturn(GatewayPaymentService.WebhookResult.accepted(Outcome.APPLIED));

        mockMvc.perform(post("/api/payments/webhooks/cashfree")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-webhook-signature", "sig")
                        .header("x-webhook-timestamp", "1")
                        .content("{\"type\":\"PAYMENT_SUCCESS_WEBHOOK\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    @DisplayName("a duplicate event is 200 duplicate")
    void duplicateWebhookIsOk() throws Exception {
        when(gatewayPaymentService.applyWebhook(any(), any(), any()))
                .thenThrow(new GatewayPaymentService.DuplicateEventException("evt-1"));

        mockMvc.perform(post("/api/payments/webhooks/cashfree")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().string("duplicate"));
    }

    @Test
    @DisplayName("a processing failure is 500 retry")
    void processingFailureAsksForRedelivery() throws Exception {
        when(gatewayPaymentService.applyWebhook(any(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        mockMvc.perform(post("/api/payments/webhooks/cashfree")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("retry"));
    }
}
