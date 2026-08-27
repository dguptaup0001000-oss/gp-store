package com.gpstore.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A browser checkout sends Idempotency-Key. CORS must allow that header on
 * the preflight, or Flutter web never reaches the place-order endpoint.
 *
 * Native apps ignore CORS; this test exists for the web client path.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorsIdempotencyPreflightTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("a checkout preflight that asks to send Idempotency-Key is allowed")
    void idempotencyKeyIsAcceptedOnPreflight() throws Exception {
        MvcResult result = mockMvc.perform(options("/api/orders/place")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization, content-type, idempotency-key"))
                .andReturn();

        int status = result.getResponse().getStatus();
        assertTrue(status == 200 || status == 204,
                "preflight must succeed so the browser will send the real POST; got " + status
                        + " body=" + result.getResponse().getContentAsString());

        String allowOrigin = result.getResponse().getHeader("Access-Control-Allow-Origin");
        assertEquals("http://localhost:3000", allowOrigin);

        String allowHeaders = result.getResponse().getHeader("Access-Control-Allow-Headers");
        assertNotNull(allowHeaders, "Access-Control-Allow-Headers must be present");
        assertTrue(allowHeaders.toLowerCase().contains("idempotency-key"),
                "Idempotency-Key must be an allowed request header; was: " + allowHeaders);
    }

    @Test
    @DisplayName("a header that is not on the allow-list is not approved")
    void unlistedRequestHeaderIsNotApproved() throws Exception {
        MvcResult result = mockMvc.perform(options("/api/orders/place")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "x-custom-debug"))
                .andReturn();

        String allowHeaders = result.getResponse().getHeader("Access-Control-Allow-Headers");
        if (allowHeaders != null) {
            assertFalse(allowHeaders.toLowerCase().contains("x-custom-debug"),
                    "CORS must not echo an unlisted header as allowed; was: " + allowHeaders);
        }
        assertNotEquals("*", result.getResponse().getHeader("Access-Control-Allow-Origin"),
                "credentials mode must never pair with a wildcard origin");
    }

    @Test
    @DisplayName("liveness does not need the database and stays a plain string")
    void livenessIsPublic() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("GP-STORE Backend Running Successfully!"));
        mockMvc.perform(get("/api/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("live"));
    }

    @Test
    @DisplayName("readiness borrows a pool connection and succeeds when Postgres answers")
    void readinessChecksTheDatabase() throws Exception {
        mockMvc.perform(get("/api/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ready"));
    }

    @Test
    @DisplayName("actuator readiness is public so a load balancer does not need an admin token")
    void actuatorReadinessIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk());
    }
}
