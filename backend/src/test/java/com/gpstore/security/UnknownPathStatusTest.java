package com.gpstore.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A URL that does not exist must answer 404, not 500.
 *
 * WHY THIS TEST EXISTS. Someone called GET /v1/api/catalog/products - a path
 * this application has never had - with a perfectly valid ADMIN token. They
 * got:
 *
 *     HTTP 500 {"message":"An unexpected error occurred"}
 *
 * and reasonably concluded the catalogue endpoint was broken and that a Java
 * stack trace was waiting in the production logs. There was no stack trace and
 * nothing was broken. GlobalExceptionHandler's catch-all was swallowing
 * Spring's own NoResourceFoundException and relabelling a missing route as a
 * server fault.
 *
 * That is worse than a cosmetic status-code error. A 500 says "the server is
 * broken, this is not your fault, retry later"; a 404 says "that address does
 * not exist, check it". The wrong one sends people hunting through logs for an
 * exception that was never thrown - which is exactly what happened, and cost
 * real time.
 *
 * The 401 case matters just as much and for the opposite reason: an
 * UNAUTHENTICATED caller must not be able to tell a real path from an
 * imaginary one, because that difference maps the API surface for free.
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
class UnknownPathStatusTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @WithStaff
    @DisplayName("an authenticated request to a path that does not exist is 404, never 500")
    void unknownPathIsNotFoundForAnAdmin() throws Exception {
        mockMvc.perform(get("/api/catalog/products"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithStaff
    @DisplayName("a near-miss of a real path is also 404")
    void nearMissIsNotFound() throws Exception {
        // /api/admin/catalog/audit exists; /api/admin/catalog/auditz does not.
        mockMvc.perform(get("/api/admin/catalog/auditz"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/productz"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an UNAUTHENTICATED caller cannot tell a real path from an imaginary one")
    void unauthenticatedCannotProbeTheApiSurface() throws Exception {
        // Both answer 401. If the missing one answered 404 while the real one
        // answered 401, an attacker could enumerate every route in the
        // application without a single credential.
        mockMvc.perform(get("/api/catalog/products"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/orders/my-orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("a non-admin hitting a real admin path still gets 403, not 404")
    void authorizationStillOutranksRouting() throws Exception {
        // The fix must not turn genuine authorization failures into 404s -
        // that would hide misconfigured permissions behind a friendly-looking
        // status and make them very hard to notice.
        mockMvc.perform(get("/api/admin/catalog/audit"))
                .andExpect(status().isForbidden());
    }
}
