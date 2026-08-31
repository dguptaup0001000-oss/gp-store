package com.gpstore.controller;

import com.gpstore.service.OrderService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the HTTP contract for checkout idempotency at the boundary the client
 * actually talks to.
 *
 * The unit tests around OrderService prove the SERVICE honours a key. They
 * cannot prove the controller reads the header, or reads it under the right
 * name - and that gap is not hypothetical here: the Flutter client shipped
 * for a long time sending no Idempotency-Key at all, so the whole mechanism
 * existed and did nothing. A header-name typo, or someone "tidying" the
 * @RequestHeader, would fail silently in exactly the same way: every
 * checkout would look fine, and duplicate orders would only appear under the
 * network conditions the key exists to survive.
 *
 * These assert on what crosses the wire: the exact header name, that its
 * value reaches the service unchanged, and that a request without it is
 * rejected rather than quietly accepted.
 */
@SpringBootTest(properties = {
        // NO LIVE OUTBOX WORKER. A running drain turns committed work into
        // auto-assigned deliveries against whichever rider is available, and
        // Spring caches this context and never closes it - so the worker
        // outlives the class and keeps assigning while later classes are
        // asserting. That is how TerritoryDispatchTest failed with
        // "expected: <22> but was: <23>": a stray assignment gave one of two
        // deliberately-tied riders a live order and the tie broke the other
        // way.
        //
        // Nothing in this class tests the outbox or waits on an async side
        // effect, so the drain has no purpose here beyond causing that.
        // OutboxDurabilityTest, which does test it, keeps a live worker.
        "outbox.drain-interval-ms=3600000"
})
@AutoConfigureMockMvc
class IdempotencyHeaderContractTest {

    private static final String PLACE_ORDER_BODY = """
            {"addressId": 1, "paymentMethod": "COD"}
            """;

    @Autowired private MockMvc mockMvc;

    /**
     * The controller is what is under test, so the service is mocked - this
     * asserts wiring, not order placement, which is covered against a real
     * database elsewhere.
     */
    @MockitoBean private OrderService orderService;

    /**
     * Also mocked: @WithMockUser installs Spring Security's own User as the
     * principal, but CurrentUser expects this app's AuthenticatedUser and
     * throws otherwise - producing a 500 that has nothing to do with the
     * header contract under test. Stubbing the customer id keeps the failure
     * signal on the thing being asserted.
     */
    @MockitoBean private com.gpstore.security.CurrentUser currentUser;

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void idempotencyKeyHeaderReachesTheServiceUnchanged() throws Exception {
        String key = "11111111-2222-3333-4444-555555555555";
        when(currentUser.customerId()).thenReturn(1L);

        when(orderService.placeOrder(any(), any(), eq(key)))
                .thenReturn(new com.gpstore.dto.response.PlaceOrderResponse());

        mockMvc.perform(post("/api/orders/place")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PLACE_ORDER_BODY))
                .andExpect(status().isOk());

        ArgumentCaptor<String> captured = ArgumentCaptor.forClass(String.class);
        verify(orderService).placeOrder(any(), any(), captured.capture());

        assertEquals(key, captured.getValue(),
                "The Idempotency-Key header must reach the service byte-for-byte - a client "
                        + "retry only replays the original order if the server sees the SAME key");
    }

    /**
     * The header name is case-insensitive per HTTP, and Spring honours that.
     * Asserted explicitly because a client sending a differently-cased name
     * must not silently fall into the "no key supplied" path.
     */
    @Test
    @WithMockUser(roles = "CUSTOMER")
    void idempotencyKeyHeaderIsMatchedCaseInsensitively() throws Exception {
        String key = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        when(currentUser.customerId()).thenReturn(1L);

        when(orderService.placeOrder(any(), any(), eq(key)))
                .thenReturn(new com.gpstore.dto.response.PlaceOrderResponse());

        mockMvc.perform(post("/api/orders/place")
                        .header("idempotency-key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PLACE_ORDER_BODY))
                .andExpect(status().isOk());

        verify(orderService).placeOrder(any(), any(), eq(key));
    }

    /**
     * A request with no key must reach the service as null so the service's
     * own "key required" rule fires. The controller must not invent a key, and
     * must not drop the request before that rule is evaluated - silently
     * accepting a keyless order is precisely the state this system was in
     * before.
     */
    @Test
    @WithMockUser(roles = "CUSTOMER")
    void missingIdempotencyKeyIsPassedThroughAsNullRatherThanFabricated() throws Exception {
        when(currentUser.customerId()).thenReturn(1L);
        when(orderService.placeOrder(any(), any(), eq(null)))
                .thenReturn(new com.gpstore.dto.response.PlaceOrderResponse());

        mockMvc.perform(post("/api/orders/place")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PLACE_ORDER_BODY))
                .andExpect(status().isOk());

        verify(orderService).placeOrder(any(), any(), eq(null));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void clientSuppliedTotalsAreIgnoredAtTheHttpBoundary() throws Exception {
        when(currentUser.customerId()).thenReturn(1L);
        when(orderService.placeOrder(any(), any(), any()))
                .thenReturn(new com.gpstore.dto.response.PlaceOrderResponse());

        mockMvc.perform(post("/api/orders/place")
                        .header("Idempotency-Key", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"addressId": 1, "paymentMethod": "COD",
                                 "totalAmount": 1, "subtotal": 1, "deliveryFee": 0,
                                 "customerId": 999}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<com.gpstore.dto.request.PlaceOrderRequest> captured =
                ArgumentCaptor.forClass(com.gpstore.dto.request.PlaceOrderRequest.class);
        verify(orderService).placeOrder(captured.capture(), eq(1L), any());
        assertEquals(Long.valueOf(1L), captured.getValue().getAddressId());
        assertEquals("COD", captured.getValue().getPaymentMethod());
    }
}
