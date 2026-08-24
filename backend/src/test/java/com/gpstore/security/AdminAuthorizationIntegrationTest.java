package com.gpstore.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 16/18: SecurityConfig's URL-based authorizeHttpRequests rules are
 * the actual backstop for every admin-only endpoint - a controller/service
 * bug that forgets an ownership check is still caught here as long as the
 * URL itself is correctly locked down. These hit the real filter chain
 * (JwtFilter + the authorizeHttpRequests rules), not a mocked slice, so a
 * typo in a requestMatchers path or role would show up here even though
 * every individual controller method "looks" fine in isolation.
 *
 * @WithMockUser injects a Spring Security Authentication directly into the
 * test's SecurityContext before the request is dispatched - since no
 * Authorization header is sent, JwtFilter (which only ever SETS the
 * SecurityContext when it sees a valid Bearer token, see JwtFilter.java)
 * leaves that test-injected authentication untouched, so the request is
 * evaluated by authorizeHttpRequests exactly as if the JWT filter had
 * validated a token carrying that role.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminAuthorizationIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void adminOnlyInventoryEndpointRejectsCustomerRole() throws Exception {
        mockMvc.perform(get("/api/inventory"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminOnlyInventoryEndpointRejectsUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/inventory"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminOnlyInventoryEndpointAllowsAdminRole() throws Exception {
        mockMvc.perform(get("/api/inventory"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void adminOnlyOrderStatusUpdateRejectsCustomerRole() throws Exception {
        mockMvc.perform(put("/api/orders/1/status").param("status", "CONFIRMED"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "DELIVERY_BOY")
    void adminOnlyDeliveryBatchEndpointRejectsDeliveryBoyRole() throws Exception {
        // Delivery partners can act on individual deliveries (/api/deliveries/**)
        // but batch management is admin-only - a distinct role boundary this
        // pins down separately from the plain CUSTOMER-vs-ADMIN cases above.
        mockMvc.perform(get("/api/delivery-batches"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void deliveryPricingAdminRejectsCustomerRole() throws Exception {
        mockMvc.perform(get("/api/admin/delivery-pricing/settings"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void workerAdminRejectsCustomerRole() throws Exception {
        mockMvc.perform(get("/api/admin/worker/orders/1/accountability"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void broadcastRejectsCustomerRole() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/notifications/broadcast")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"message\":\"y\"}"))
                .andExpect(status().isForbidden());
    }
}
