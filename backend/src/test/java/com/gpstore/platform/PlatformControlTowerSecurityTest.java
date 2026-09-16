package com.gpstore.platform;

import com.gpstore.entity.Role;
import com.gpstore.security.AuthenticatedUser;
import com.gpstore.security.RolePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@DisplayName("The platform control tower is privileged and secret-safe")
class PlatformControlTowerSecurityTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    private final String tag = "tower" + System.nanoTime();
    private Long platformAdmin;
    private Long customer;

    @BeforeEach
    void accounts() {
        platformAdmin = insert("Platform Owner", tag + "-platform@example.test", Role.PLATFORM_ADMIN, null);
        customer = insert("Deepak Search " + tag, tag + "-customer@example.test", Role.CUSTOMER,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM audit_logs WHERE entity_type='Customer' AND entity_id IN (?,?)",
                platformAdmin, customer);
        jdbc.update("DELETE FROM customers WHERE id IN (?,?)", platformAdmin, customer);
    }

    @Test
    void customerCannotSearchThePlatform() throws Exception {
        mockMvc.perform(get("/api/platform/control/search")
                        .param("q", tag)
                        .with(authentication(token(customer, Role.CUSTOMER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void platformSearchIsPagedMaskedAndDoesNotLeakSecrets() throws Exception {
        String body = mockMvc.perform(get("/api/platform/control/search")
                        .param("q", tag)
                        .param("page", "0")
                        .param("size", "1")
                        .with(authentication(token(platformAdmin, Role.PLATFORM_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(tag + "-customer@example.test"), body);
        assertFalse(body.contains("0123456789abcdef"), body);
        assertFalse(body.toLowerCase().contains("password"), body);
        assertFalse(body.toLowerCase().contains("activationcode"), body);
    }

    @Test
    void customer360IsMaskedAndRevealRequiresReasonAndIsAudited() throws Exception {
        String detail = mockMvc.perform(get("/api/platform/control/customers/{id}", customer)
                        .with(authentication(token(platformAdmin, Role.PLATFORM_ADMIN))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(detail.contains(tag + "-customer@example.test"), detail);
        assertFalse(detail.contains("0123456789abcdef"), detail);

        mockMvc.perform(post("/api/platform/control/customers/{id}/reveal", customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"field\":\"email\",\"reason\":\"x\"}")
                        .with(authentication(token(platformAdmin, Role.PLATFORM_ADMIN))))
                .andExpect(status().isBadRequest());

        String revealed = mockMvc.perform(post("/api/platform/control/customers/{id}/reveal", customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"field\":\"email\",\"reason\":\"Customer requested account support\"}")
                        .with(authentication(token(platformAdmin, Role.PLATFORM_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.field").value("email"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(revealed.contains(tag + "-customer@example.test"), revealed);

        String auditDetails = jdbc.queryForObject("""
                SELECT details FROM audit_logs
                WHERE action='SENSITIVE_PII_REVEALED' AND entity_type='Customer' AND entity_id=?
                ORDER BY id DESC LIMIT 1
                """, String.class, customer);
        assertTrue(auditDetails.contains("field=email"));
        assertFalse(auditDetails.contains(tag + "-customer@example.test"));
    }

    @Test
    void platformDashboardIsAvailableButMerchantIsDenied() throws Exception {
        mockMvc.perform(get("/api/platform/control/dashboard")
                        .with(authentication(token(platformAdmin, Role.PLATFORM_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketplace.totalCustomers").isNumber())
                .andExpect(jsonPath("$.finance.gmv").isNumber());

        Long merchantAccount = insert("Merchant", tag + "-merchant@example.test", Role.ADMIN, null);
        try {
            mockMvc.perform(get("/api/platform/control/dashboard")
                            .with(authentication(token(merchantAccount, Role.ADMIN))))
                    .andExpect(status().isForbidden());
        } finally {
            jdbc.update("DELETE FROM customers WHERE id=?", merchantAccount);
        }
    }

    private Long insert(String name, String email, Role role, String activationHash) {
        jdbc.update("""
                INSERT INTO customers
                    (full_name,email,mobile_number,password,role,enabled,active,verified,activation_code_hash,created_at)
                VALUES (?,?,?,'not-a-real-hash',?,true,true,true,?,CURRENT_TIMESTAMP)
                """, name, email, "8" + Math.abs(email.hashCode() % 1_000_000_000), role.name(), activationHash);
        return jdbc.queryForObject("SELECT id FROM customers WHERE email=?", Long.class, email);
    }

    private UsernamePasswordAuthenticationToken token(Long id, Role role) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String authority : RolePermissions.authorityNames(role)) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(id, tag + "@example.test", role.name()), null, authorities);
    }
}
