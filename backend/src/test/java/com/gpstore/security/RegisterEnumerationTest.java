package com.gpstore.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public registration must not tell an attacker which identifier is taken.
 */
@SpringBootTest(properties = {
        // THE RATE LIMITER STARTED WORKING IN TESTS, and this fixture is the
        // first thing it caught. Every MockMvc request comes from the same
        // mock address, so a class that registers several accounts looks
        // exactly like one machine hammering /api/auth/register - which is
        // what the auth bucket exists to stop. Real customers arrive from
        // different addresses; this is an artefact of the fixture, not the
        // behaviour under test, so the bucket is widened rather than the
        // filter weakened.
        //
        // Why it only started now: RateLimitFilter classified on
        // getServletPath(), which MockMvc leaves empty, so every request in
        // the suite fell into the default bucket. See RequestPath.
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
class RegisterEnumerationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("duplicate email and duplicate phone return the same conflict wording")
    void duplicateEmailAndPhoneShareOneMessage() throws Exception {
        long stamp = System.nanoTime();
        String email = "enum-" + stamp + "@example.com";
        String phone = "9" + String.format("%09d", Math.abs(stamp % 1_000_000_000L));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("First", email, phone)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Second", email, "8" + phone.substring(1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Unable to create this account. Try a different email or phone."))
                .andExpect(jsonPath("$.message", not(org.hamcrest.Matchers.containsString("already exists"))));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Third", "other-" + stamp + "@example.com", phone)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Unable to create this account. Try a different email or phone."))
                .andExpect(jsonPath("$.message", not(org.hamcrest.Matchers.containsString("already exists"))));
    }

    private static String body(String name, String email, String phone) {
        return """
                {"name":"%s","email":"%s","phone":"%s","password":"Passw0rd!23"}
                """.formatted(name, email, phone);
    }
}
