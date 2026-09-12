package com.gpstore.security;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Role;
import com.gpstore.repository.CustomerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class JwtRoleRecheckTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private CustomerAccountStatusService accountStatusService;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("a JWT whose role no longer matches the live account is rejected")
    void demotedRoleCannotKeepUsingTheOldJwt() throws Exception {
        long stamp = System.nanoTime();
        String email = "role-recheck-" + stamp + "@example.com";
        String phone = "9" + String.format("%09d", Math.abs(stamp % 1_000_000_000L));

        MvcResult registered = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Role Recheck","email":"%s","phone":"%s","password":"Passw0rd!23"}
                                """.formatted(email, phone)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(registered.getResponse().getContentAsString());
        String accessToken = body.get("token").asText();
        long customerId = body.get("customerId").asLong();

        mockMvc.perform(get("/api/orders/my-orders").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        Customer stored = customerRepository.findById(customerId).orElseThrow();
        stored.setRole(Role.DELIVERY_BOY);
        customerRepository.save(stored);
        accountStatusService.invalidate(customerId);

        mockMvc.perform(get("/api/orders/my-orders").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }
}
