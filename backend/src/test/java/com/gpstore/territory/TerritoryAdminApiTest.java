package com.gpstore.territory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The territory configuration API: mapped, and admin-only.
 *
 * Both halves matter and neither is obvious from reading the controller. A
 * @RestController whose package is not component-scanned is silently a 404,
 * which looks exactly like a security rule working. And a security rule that
 * does not cover a path is silently open, which looks exactly like a
 * controller working. Only asking the running application distinguishes them.
 *
 * WHAT THESE ROUTES CAN DO is why the second half is worth a test at all: one
 * PUT here moves a boundary, and every future order in that area quietly goes
 * to a different rider.
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
class TerritoryAdminApiTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("the health endpoint is actually mapped, and reports the 8/26 target")
    void healthIsMappedForAnAdmin() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/territory/health")).andReturn();

        assertEquals(200, result.getResponse().getStatus(),
                "a 404 here means the controller is not being scanned, which looks identical to "
                        + "a security rule working; body: " + result.getResponse().getContentAsString());

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"expectedZones\":8"), body);
        assertTrue(body.contains("\"expectedSubzones\":26"), body);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("resolving a point is answerable without drawing anything first")
    void resolveIsMapped() throws Exception {
        MvcResult result = mockMvc.perform(
                get("/api/admin/territory/resolve?latitude=28.61&longitude=77.21")).andReturn();

        assertEquals(200, result.getResponse().getStatus(),
                "body: " + result.getResponse().getContentAsString());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("a customer cannot read the delivery map")
    void customersAreRefused() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/territory/health")).andReturn();

        int status = result.getResponse().getStatus();
        assertNotEquals(200, status, "the territory map is not customer-readable");
        assertNotEquals(404, status,
                "404 would mean the route is unmapped rather than protected - those must not be "
                        + "confused, because one of them is a security hole wearing the other's "
                        + "status code");
        assertTrue(status == 401 || status == 403, "expected an auth failure, got " + status);
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("a customer cannot redraw a boundary")
    void customersCannotWriteTheMap() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/territory/zones")
                        .contentType("application/json")
                        .content("{\"code\":\"Z9\",\"name\":\"Not yours\"}"))
                .andReturn();

        int status = result.getResponse().getStatus();
        assertTrue(status == 401 || status == 403,
                "one POST here creates a territory; expected an auth failure, got " + status);
    }
}
