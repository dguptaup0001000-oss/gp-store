package com.gpstore.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stolen access token is not enough to destroy an account or re-bind its
 * phone number. The current password is the step-up.
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
class AccountStepUpAuthTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("DELETE /api/customers/me without a password is refused")
    void deleteWithoutPasswordIsRefused() throws Exception {
        String token = register("del-nopw");
        mockMvc.perform(delete("/api/customers/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Current password is incorrect"));
    }

    @Test
    @DisplayName("DELETE /api/customers/me with the wrong password is refused")
    void deleteWithWrongPasswordIsRefused() throws Exception {
        String token = register("del-badpw");
        mockMvc.perform(delete("/api/customers/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong-password-9\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE /api/customers/me with the current password anonymizes the account")
    void deleteWithPasswordSucceeds() throws Exception {
        String token = register("del-ok");
        mockMvc.perform(delete("/api/customers/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Passw0rd!23\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("changing mobile without the current password is refused")
    void mobileChangeRequiresPassword() throws Exception {
        String token = register("mob-nopw");
        mockMvc.perform(put("/api/customers/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Same\",\"mobileNumber\":\"9000000001\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Current password is incorrect"));
    }

    @Test
    @DisplayName("name-only profile update does not need the password")
    void nameChangeDoesNotNeedPassword() throws Exception {
        String token = register("name-only");
        mockMvc.perform(put("/api/customers/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"New Name\",\"mobileNumber\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("New Name"));
    }

    @Test
    @DisplayName("a weak password is refused at registration")
    void weakPasswordIsRefused() throws Exception {
        long stamp = System.nanoTime();
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Weak","email":"weak-%d@example.com","phone":"9%09d","password":"passwordaa"}
                                """.formatted(stamp, Math.abs(stamp % 1_000_000_000L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("letter and one number")));
    }

    @Test
    @DisplayName("admin ops backup status is not customer-readable")
    void customerCannotReadBackupStatus() throws Exception {
        String token = register("ops-deny");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/admin/ops/status")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    private String register(String who) throws Exception {
        long stamp = System.nanoTime();
        String email = who + "-" + stamp + "@stepup-test.invalid";
        String phone = "9" + String.format("%09d", Math.abs(stamp % 1_000_000_000L));
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","email":"%s","phone":"%s","password":"Passw0rd!23"}
                                """.formatted(who, email, phone)))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }
}
