package com.gpstore.platform;

import com.gpstore.entity.Role;
import com.gpstore.security.AuthenticatedUser;
import com.gpstore.security.RolePermissions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Barring somebody from the marketplace leaves a record, or it does not happen.
 *
 * <h2>Why this is a test and not a convention</h2>
 *
 * <p>GP-STORE is where real people buy real food from real shops. Switching
 * an account off stops a person shopping. Six months later, the only thing
 * that can answer "why is this account off, and who decided" is a row written
 * at the moment it was switched off. A console that offers the button but
 * records nothing produces exactly the situation nobody can unwind.
 *
 * <p>So this asserts three separate things, because they fail separately:
 * that the route is refused to everyone but the platform; that a call
 * without a stated reason is refused outright rather than silently accepted;
 * and that the audit row afterwards names the previous state, the new state
 * and the operator's words.
 *
 * <p>It also asserts the negative that matters: the audit row must NOT
 * contain the account's password hash, activation hash or contact details.
 * An audit trail that quietly becomes a second copy of the credentials table
 * is worse than none.
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
@DisplayName("Barring a customer is a recorded act")
class BarringACustomerIsRecordedTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    private final String tag = "bar" + System.nanoTime();
    private Long platformAdmin;
    private Long merchantAdmin;
    private Long customer;
    private Long bystander;

    @BeforeEach
    void accounts() {
        platformAdmin = insert("Platform Owner " + tag, tag + "-platform@example.test",
                Role.PLATFORM_ADMIN);
        merchantAdmin = insert("Shop Owner " + tag, tag + "-merchant@example.test", Role.ADMIN);
        customer = insert("Anita Shopper " + tag, tag + "-customer@example.test", Role.CUSTOMER);
        bystander = insert("Ravi Shopper " + tag, tag + "-bystander@example.test", Role.CUSTOMER);
    }

    @AfterEach
    void cleanup() {
        List<Long> ids = List.of(platformAdmin, merchantAdmin, customer, bystander);
        for (Long id : ids) {
            jdbc.update("DELETE FROM audit_logs WHERE entity_type='Customer' AND entity_id=?", id);
            jdbc.update("DELETE FROM audit_logs WHERE actor_customer_id=?", id);
            jdbc.update("DELETE FROM customers WHERE id=?", id);
        }
    }

    @Nested
    @DisplayName("Who may do it")
    class WhoMay {

        @Test
        @DisplayName("a customer cannot bar anybody, including themselves")
        void customerIsRefused() throws Exception {
            mockMvc.perform(put("/api/platform/control/customers/{id}/status", bystander)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(false, "Trying it on"))
                            .with(authentication(token(customer, Role.CUSTOMER))))
                    .andExpect(status().isForbidden());

            assertTrue(isActive(bystander), "a customer's call changed another account");
        }

        /**
         * THE IDOR CASE. A merchant admin has real power over their own shop
         * and none at all over the platform's customer roster. If the route
         * were gated on "is logged in and has some admin authority" rather
         * than on PLATFORM_ADMIN, this is the request that would go through.
         */
        @Test
        @DisplayName("a merchant admin cannot reach the platform route by id")
        void merchantIsRefused() throws Exception {
            mockMvc.perform(put("/api/platform/control/customers/{id}/status", bystander)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(false, "A rival's regular customer"))
                            .with(authentication(token(merchantAdmin, Role.ADMIN))))
                    .andExpect(status().isForbidden());

            assertTrue(isActive(bystander), "a merchant barred somebody through the platform route");
        }
    }

    @Nested
    @DisplayName("What it takes")
    class WhatItTakes {

        @Test
        @DisplayName("no reason, no change")
        void reasonIsMandatory() throws Exception {
            mockMvc.perform(put("/api/platform/control/customers/{id}/status", customer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"active\":false}")
                            .with(authentication(token(platformAdmin, Role.PLATFORM_ADMIN))))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(put("/api/platform/control/customers/{id}/status", customer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(false, "hm"))
                            .with(authentication(token(platformAdmin, Role.PLATFORM_ADMIN))))
                    .andExpect(status().isBadRequest());

            assertTrue(isActive(customer), "a refused call still changed the account");
            assertEquals(0, auditRows(customer).size(),
                    "a refused call still wrote an audit row");
        }

        @Test
        @DisplayName("and the state to move to is not optional either")
        void activeIsMandatory() throws Exception {
            mockMvc.perform(put("/api/platform/control/customers/{id}/status", customer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Fraud investigation GP-4471\"}")
                            .with(authentication(token(platformAdmin, Role.PLATFORM_ADMIN))))
                    .andExpect(status().isBadRequest());

            assertTrue(isActive(customer));
        }
    }

    @Nested
    @DisplayName("What it leaves behind")
    class WhatItLeaves {

        @Test
        @DisplayName("the account is barred and the row says who, what and why")
        void barringIsAudited() throws Exception {
            mockMvc.perform(put("/api/platform/control/customers/{id}/status", customer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(false, "Fraud investigation GP-4471"))
                            .with(authentication(token(platformAdmin, Role.PLATFORM_ADMIN))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false));

            assertFalse(isActive(customer), "the account is still usable after being barred");

            List<Map<String, Object>> rows = auditRows(customer);
            assertEquals(1, rows.size(), "expected exactly one audit row, got " + rows);
            Map<String, Object> row = rows.getFirst();
            assertEquals("CUSTOMER_ACCOUNT_DEACTIVATED", row.get("action"));
            assertEquals("ACTIVE", row.get("previous_state"));
            assertEquals("INACTIVE", row.get("new_state"));
            assertEquals("Fraud investigation GP-4471", row.get("reason"));
            assertNotNull(row.get("occurred_at"), "an audit row with no timestamp dates nothing");
        }

        @Test
        @DisplayName("restoring is recorded the same way, in the other direction")
        void restoringIsAudited() throws Exception {
            bar(customer, "Fraud investigation GP-4471");
            mockMvc.perform(put("/api/platform/control/customers/{id}/status", customer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(true, "Cleared by review GP-4471"))
                            .with(authentication(token(platformAdmin, Role.PLATFORM_ADMIN))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(true));

            assertTrue(isActive(customer));

            List<Map<String, Object>> rows = auditRows(customer);
            assertEquals(2, rows.size(), "expected the bar and the restore, got " + rows);
            Map<String, Object> restore = rows.get(1);
            assertEquals("CUSTOMER_ACCOUNT_REACTIVATED", restore.get("action"));
            assertEquals("INACTIVE", restore.get("previous_state"));
            assertEquals("ACTIVE", restore.get("new_state"));
            assertEquals("Cleared by review GP-4471", restore.get("reason"));
        }

        /**
         * The audit trail must not become a second copy of the things it
         * exists to protect. Nothing here is a coincidence of this fixture:
         * the hash, the activation hash and the contact details are all real
         * columns on the row being acted upon.
         */
        @Test
        @DisplayName("and it carries no credential and no contact detail")
        void theRowIsNotACopyOfTheAccount() throws Exception {
            bar(customer, "Fraud investigation GP-4471");

            String everything = auditRows(customer).toString();
            assertFalse(everything.contains("not-a-real-hash"), everything);
            assertFalse(everything.contains(tag + "-customer@example.test"), everything);
            assertFalse(everything.toLowerCase().contains("password"), everything);
            assertFalse(everything.toLowerCase().contains("activation"), everything);
        }

        /**
         * A barred account has to stop working NOW, not when its access token
         * expires. The route this delegates to revokes every refresh token
         * and drops the JWT status cache; this asserts the flag the whole
         * chain hangs off, which is what CustomerAccountStatusService reads.
         */
        @Test
        @DisplayName("the flag the login path reads is the one that changed")
        void theAccountIsActuallyUnusable() throws Exception {
            bar(customer, "Fraud investigation GP-4471");

            Boolean active = jdbc.queryForObject(
                    "SELECT active FROM customers WHERE id=?", Boolean.class, customer);
            assertEquals(Boolean.FALSE, active);
            assertFalse(com.gpstore.security.CustomerAccountStatusService.isCustomerUsable(
                            loaded(customer)),
                    "the account still passes the check the JWT filter makes");
        }
    }

    // ------------------------------------------------------------- helpers

    private void bar(Long id, String reason) throws Exception {
        mockMvc.perform(put("/api/platform/control/customers/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(false, reason))
                        .with(authentication(token(platformAdmin, Role.PLATFORM_ADMIN))))
                .andExpect(status().isOk());
    }

    private static String body(boolean active, String reason) {
        return "{\"active\":" + active + ",\"reason\":\"" + reason + "\"}";
    }

    private boolean isActive(Long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT active FROM customers WHERE id=?", Boolean.class, id));
    }

    private com.gpstore.entity.Customer loaded(Long id) {
        com.gpstore.entity.Customer found = new com.gpstore.entity.Customer();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT active, enabled FROM customers WHERE id=?", id);
        found.setActive((Boolean) row.get("active"));
        found.setEnabled((Boolean) row.get("enabled"));
        return found;
    }

    /** Oldest first, so an assertion can name "the bar" and "the restore". */
    private List<Map<String, Object>> auditRows(Long id) {
        return jdbc.queryForList("""
                SELECT action, previous_state, new_state, reason, occurred_at
                FROM audit_logs
                WHERE entity_type='Customer' AND entity_id=?
                  AND action LIKE 'CUSTOMER_ACCOUNT_%'
                ORDER BY id
                """, id);
    }

    private Long insert(String name, String email, Role role) {
        jdbc.update("""
                INSERT INTO customers
                    (full_name,email,mobile_number,password,role,enabled,active,verified,created_at)
                VALUES (?,?,?,'not-a-real-hash',?,true,true,true,CURRENT_TIMESTAMP)
                """, name, email, "8" + Math.abs(email.hashCode() % 1_000_000_000), role.name());
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
