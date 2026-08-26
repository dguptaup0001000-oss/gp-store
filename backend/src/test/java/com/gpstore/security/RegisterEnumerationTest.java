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

        String emailConflict = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Second", email, "8" + phone.substring(1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Unable to create this account. Try a different email or phone."))
                .andExpect(jsonPath("$.message", not(org.hamcrest.Matchers.containsString("already exists"))))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String phoneConflict = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Third", "other-" + stamp + "@example.com", phone)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Unable to create this account. Try a different email or phone."))
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.junit.jupiter.api.Assertions.assertEquals(emailConflict, phoneConflict,
                "email vs phone collisions must be indistinguishable");
    }

    private static String body(String name, String email, String phone) {
        return """
                {"name":"%s","email":"%s","phone":"%s","password":"Passw0rd!23"}
                """.formatted(name, email, phone);
    }
}
