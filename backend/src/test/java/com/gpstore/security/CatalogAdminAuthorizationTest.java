package com.gpstore.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The catalog admin surface, which arrived after
 * AdminAuthorizationIntegrationTest was written and was therefore never
 * covered by it.
 *
 * WHY IT MATTERS MORE THAN MOST ADMIN ROUTES: these three endpoints can insert
 * a thousand products, make a thousand outbound HTTP requests, and delete every
 * test product in the shop. An authorization gap here is not "someone reads a
 * report they shouldn't"; it is an unauthenticated stranger able to empty the
 * catalogue or use the shop as a request amplifier.
 *
 * NOTHING DESTRUCTIVE ACTUALLY RUNS HERE. Every case below asserts a REJECTION,
 * so the request stops at the filter chain and never reaches the controller.
 * The one admin-allowed case deliberately uses the read-only audit endpoint.
 * The DELETE case is exercised only from roles that must be refused - proving
 * the door is locked without opening it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogAdminAuthorizationTest {

    private static final String SEED = "/api/admin/catalog/seed";
    private static final String AUDIT = "/api/admin/catalog/audit";
    private static final String IMAGES = "/api/admin/catalog/images/backfill";
    private static final String TEST_DATA = "/api/admin/catalog/test-data";

    @Autowired private MockMvc mockMvc;

    // ---------------- A. unauthenticated ----------------

    @Test
    @DisplayName("unauthenticated cannot seed the catalogue")
    void unauthenticatedCannotSeed() throws Exception {
        mockMvc.perform(post(SEED)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("unauthenticated cannot read the catalogue audit")
    void unauthenticatedCannotAudit() throws Exception {
        mockMvc.perform(get(AUDIT)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("unauthenticated cannot trigger the image backfill")
    void unauthenticatedCannotBackfillImages() throws Exception {
        mockMvc.perform(post(IMAGES)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("unauthenticated cannot delete test data")
    void unauthenticatedCannotDeleteTestData() throws Exception {
        mockMvc.perform(delete(TEST_DATA)).andExpect(status().isUnauthorized());
    }

    // ---------------- B. ordinary customer ----------------

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("a customer cannot seed the catalogue")
    void customerCannotSeed() throws Exception {
        mockMvc.perform(post(SEED)).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("a customer cannot read the catalogue audit")
    void customerCannotAudit() throws Exception {
        mockMvc.perform(get(AUDIT)).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("a customer cannot delete test data")
    void customerCannotDeleteTestData() throws Exception {
        mockMvc.perform(delete(TEST_DATA)).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("a customer cannot make the shop fetch a thousand external images")
    void customerCannotBackfillImages() throws Exception {
        mockMvc.perform(post(IMAGES)).andExpect(status().isForbidden());
    }

    // ---------------- C. delivery partner ----------------

    @Test
    @WithMockUser(roles = "DELIVERY_BOY")
    @DisplayName("a delivery partner cannot seed the catalogue")
    void deliveryBoyCannotSeed() throws Exception {
        mockMvc.perform(post(SEED)).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "DELIVERY_BOY")
    @DisplayName("a delivery partner cannot delete test data")
    void deliveryBoyCannotDeleteTestData() throws Exception {
        mockMvc.perform(delete(TEST_DATA)).andExpect(status().isForbidden());
    }

    // ---------------- D. admin ----------------

    @Test
    @WithStaff
    @DisplayName("an admin reaches the audit endpoint - read-only, nothing is mutated")
    void adminCanReadAudit() throws Exception {
        mockMvc.perform(get(AUDIT)).andExpect(status().isOk());
    }

    /**
     * The destructive endpoint's own guard, asserted from the admin role.
     *
     * Deleting is refused WITHOUT ?confirm=true, so this proves the safety
     * catch works while deleting nothing at all. Asserting only that an admin
     * is authorised would have required actually running the deletion, which
     * is not a thing a test suite should do to a shared database.
     */
    @Test
    @WithStaff
    @DisplayName("even an admin must pass confirm=true before anything is deleted")
    void adminDeletionRequiresExplicitConfirmation() throws Exception {
        mockMvc.perform(delete(TEST_DATA)).andExpect(status().isBadRequest());
    }
}
