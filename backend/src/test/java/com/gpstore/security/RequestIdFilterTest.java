package com.gpstore.security;

import com.gpstore.config.RequestIdFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/**
 * Request correlation ids.
 *
 * The point of these is operational: a customer reports a failure, reads the
 * id off their screen, and it appears on every log line that request wrote.
 * The security-relevant half is that a client-supplied id reaches the log, so
 * it must not be able to carry a newline - otherwise a crafted header writes
 * forged log entries, which is how someone hides what they did.
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
class RequestIdFilterTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("every response carries a request id, even an unauthenticated 401")
    void unauthenticatedResponsesStillGetAnId() throws Exception {
        // The filter is ordered ahead of the JWT and rate-limit filters
        // precisely so the requests that fail earliest are still traceable -
        // those are the ones most worth tracing.
        MvcResult result = mockMvc.perform(get("/api/orders/my-orders"))
                .andExpect(header().exists(RequestIdFilter.HEADER))
                .andReturn();

        String id = result.getResponse().getHeader(RequestIdFilter.HEADER);
        assertNotNull(id);
        assertFalse(id.isBlank(), "an id was returned but it is empty");
    }

    @Test
    @DisplayName("two requests get different ids")
    void idsAreUnique() throws Exception {
        String first = mockMvc.perform(get("/api/orders/my-orders"))
                .andReturn().getResponse().getHeader(RequestIdFilter.HEADER);
        String second = mockMvc.perform(get("/api/orders/my-orders"))
                .andReturn().getResponse().getHeader(RequestIdFilter.HEADER);

        assertNotEquals(first, second, "the same id was reused across requests");
    }

    @Test
    @DisplayName("a clean client-supplied id is propagated back")
    void safeClientIdIsEchoed() throws Exception {
        mockMvc.perform(get("/api/orders/my-orders").header(RequestIdFilter.HEADER, "flutter-abc123_XYZ"))
                .andExpect(header().string(RequestIdFilter.HEADER, "flutter-abc123_XYZ"));
    }

    @Test
    @DisplayName("a header carrying a newline is REPLACED, never logged as sent")
    void logInjectionIsRefused() throws Exception {
        // The attack: a newline turns one log line into two, and the second
        // can be made to look like a genuine entry - "user admin logged in",
        // say. Sanitising to a conservative character set closes it.
        String malicious = "abc\nWARN [] com.gpstore fake log line";

        MvcResult result = mockMvc.perform(
                        get("/api/orders/my-orders").header(RequestIdFilter.HEADER, malicious))
                .andReturn();

        String returned = result.getResponse().getHeader(RequestIdFilter.HEADER);
        assertNotEquals(malicious, returned, "the hostile id was accepted verbatim");
        assertFalse(returned.contains("\n"), "a newline reached the response header");
        assertFalse(returned.contains(" "), "whitespace reached the id");
    }

}
