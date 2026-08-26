package com.gpstore.security;

import com.gpstore.service.CustomerService;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.entity.Customer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A deactivated customer must not keep using an already-issued access JWT.
 *
 * Refresh-token revocation only stops the next login. This suite is the
 * proof that JwtFilter re-checks Customer.active on every authenticated
 * request, and that reactivation restores the same unexpired access token.
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
class DeactivatedCustomerJwtTest {

    private static final String PROTECTED = "/api/orders/my-orders";

    @Autowired private MockMvc mockMvc;
    @Autowired private CustomerService customerService;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("active JWT works, deactivation rejects it, reactivation restores it")
    void deactivatedAccessJwtIsRejectedThenReactivationWorks() throws Exception {
        long stamp = System.nanoTime();
        String email = "deact-" + stamp + "@example.com";
        String phone = "9" + String.format("%09d", Math.abs(stamp % 1_000_000_000L));

        MvcResult registered = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Deact Probe","email":"%s","phone":"%s","password":"Passw0rd!23"}
                                """.formatted(email, phone)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(registered.getResponse().getContentAsString());
        String accessToken = body.get("token").asText();
        long customerId = body.get("customerId").asLong();

        mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        customerService.setAccountActive(customerId, false);

        mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(put("/api/auth/change-password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"Passw0rd!99\"}"))
                .andExpect(status().isUnauthorized());

        customerService.setAccountActive(customerId, true);

        mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a deactivated account cannot mint a new access JWT from its refresh token")
    void deactivatedRefreshTokenCannotMintNewAccessJwt() throws Exception {
        long stamp = System.nanoTime();
        JsonNode body = register("refresh-deact-" + stamp + "@example.com", phone(stamp));
        String refreshToken = body.get("refreshToken").asText();
        long customerId = body.get("customerId").asLong();

        customerService.setAccountActive(customerId, false);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("deactivating one customer does not reject a different active account")
    void otherActiveAccountsAreUnaffected() throws Exception {
        long stamp = System.nanoTime();
        JsonNode banned = register("banned-" + stamp + "@example.com", phone(stamp));
        JsonNode other = register("other-" + stamp + "@example.com", phone(stamp + 1));

        customerService.setAccountActive(banned.get("customerId").asLong(), false);

        mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer " + other.get("token").asText()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a disabled account cannot log in even if it is still marked active")
    void disabledAccountCannotLogin() throws Exception {
        long stamp = System.nanoTime();
        String email = "disabled-" + stamp + "@example.com";
        JsonNode body = register(email, phone(stamp));
        long customerId = body.get("customerId").asLong();

        Customer stored = customerRepository.findById(customerId).orElseThrow();
        stored.setEnabled(false);
        customerRepository.save(stored);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"Passw0rd!23\"}".formatted(email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    @DisplayName("a deactivated JWT cannot place an order or open a payment session")
    void deactivatedAccountCannotUsePaymentEndpoints() throws Exception {
        long stamp = System.nanoTime();
        JsonNode body = register("pay-deact-" + stamp + "@example.com", phone(stamp));
        String accessToken = body.get("token").asText();
        long customerId = body.get("customerId").asLong();

        customerService.setAccountActive(customerId, false);

        mockMvc.perform(post("/api/orders/place")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":1,\"paymentMethod\":\"COD\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/payments/order/1/checkout-session")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/payments/order/1/verify")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    private JsonNode register(String email, String phone) throws Exception {
        MvcResult registered = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Peer Probe","email":"%s","phone":"%s","password":"Passw0rd!23"}
                                """.formatted(email, phone)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(registered.getResponse().getContentAsString());
    }

    private static String phone(long seed) {
        return "9" + String.format("%09d", Math.abs(seed % 1_000_000_000L));
    }
}
