package com.gpstore.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What each staff role can actually reach, asserted against the real filter
 * chain rather than against the permission map.
 *
 * <p>THE REJECTIONS MATTER MORE THAN THE GRANTS. A role that cannot reach
 * something it should is a support ticket; a role that CAN reach something it
 * should not is the reason this feature exists. So every role below is checked
 * for at least one thing it must be refused, and the money routes are checked
 * from every role that must not have them.
 *
 * <p>NOTHING DESTRUCTIVE RUNS. Every allowed case uses a read; every write is
 * exercised only from roles that must be refused, so the request stops in the
 * filter chain and never reaches a controller. A 403 proves the door is locked
 * without opening it.
 */
@SpringBootTest(properties = {
        // NO LIVE OUTBOX WORKER. A running drain turns committed work into
        // auto-assigned deliveries against whichever rider is available, and
        // Spring caches this context and never closes it - so the worker
        // outlives the class and keeps assigning while later classes are
        // asserting. That is how TerritoryDispatchTest failed with
        // "expected: <22> but was: <23>": a stray assignment gave one of two
        // deliberately-tied riders a live order and the tie broke the other
        // way.
        //
        // Nothing in this class tests the outbox or waits on an async side
        // effect, so the drain has no purpose here beyond causing that.
        // OutboxDurabilityTest, which does test it, keeps a live worker.
        "outbox.drain-interval-ms=3600000"
})
@AutoConfigureMockMvc
class StaffRoleAuthorizationTest {

    private static final String ANALYTICS = "/api/analytics/low-stock-count";
    private static final String AUDIT_LOG = "/api/audit-logs";
    private static final String CUSTOMERS = "/api/customers";
    private static final String INVENTORY = "/api/inventory";
    private static final String ADMIN_PRODUCTS = "/api/products/admin/all";
    private static final String ACTUATOR = "/actuator/metrics";
    private static final String CATALOG_SEED = "/api/admin/catalog/seed";
    private static final String REFUND = "/api/payments/order/1/refund/start";
    private static final String BROADCAST = "/api/notifications/broadcast";
    private static final String DELIVERY_PARTNERS = "/api/delivery-partners";

    /**
     * ONLY EVER ASSERTED AS FORBIDDEN, never as allowed.
     *
     * Reading the pricing settings LAZILY CREATES AND SAVES the row when one
     * does not exist (DeliveryPricingService line ~86), so a successful GET
     * here writes to the database every other test in the suite shares - and
     * the dispatch scorer reads that same row to weight a rider's load. A
     * test asserting an authorization rule has no business changing what a
     * later test computes. A 403 stops in the filter chain and never reaches
     * the controller, so the forbidden cases are safe.
     *
     * DELIVERY_PARTNERS proves the same permission (both are DELIVERY_MANAGE)
     * and is a plain read.
     */
    private static final String DELIVERY_PRICING = "/api/admin/delivery-pricing/settings";

    @Autowired private MockMvc mockMvc;

    /** 403 is the assertion. 401 would mean the annotation did not apply. */
    private void forbidden(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isForbidden());
    }

    private void forbiddenPost(String path) throws Exception {
        mockMvc.perform(post(path).with(
                        org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isForbidden());
    }

    /** Anything but 401/403 means the filter chain let it through to a handler. */
    private void allowed(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(result -> {
                    int code = result.getResponse().getStatus();
                    if (code == 401 || code == 403) {
                        throw new AssertionError(
                                "expected " + path + " to be permitted, got " + code);
                    }
                });
    }

    // ------------------------------------------------------------------
    // ADMIN: unchanged. This is the regression guard for real accounts.
    // ------------------------------------------------------------------

    @Test
    @WithStaff
    @DisplayName("ADMIN still reaches everything it did before roles existed")
    void adminReachesEverything() throws Exception {
        allowed(ANALYTICS);
        allowed(AUDIT_LOG);
        allowed(CUSTOMERS);
        allowed(INVENTORY);
        allowed(ADMIN_PRODUCTS);
        allowed(ACTUATOR);
        allowed(DELIVERY_PARTNERS);
    }

    @Test
    @WithStaff(com.gpstore.entity.Role.SUPER_ADMIN)
    @DisplayName("SUPER_ADMIN reaches the system surface")
    void superAdminReachesSystemSurface() throws Exception {
        allowed(ACTUATOR);
        allowed(AUDIT_LOG);
    }

    // ------------------------------------------------------------------
    // MANAGER: everything operational, not the system surface.
    // ------------------------------------------------------------------

    @Test
    @WithStaff(com.gpstore.entity.Role.MANAGER)
    @DisplayName("MANAGER runs the shop but is kept off actuator and bulk seeding")
    void managerHasNoSystemSurface() throws Exception {
        allowed(ANALYTICS);
        allowed(CUSTOMERS);
        allowed(INVENTORY);
        allowed(AUDIT_LOG);

        // The dangerous surface stays with the owner.
        forbidden(ACTUATOR);
        forbiddenPost(CATALOG_SEED);
    }

    // ------------------------------------------------------------------
    // INVENTORY_MANAGER: shelves only.
    // ------------------------------------------------------------------

    @Test
    @WithStaff(com.gpstore.entity.Role.INVENTORY_MANAGER)
    @DisplayName("INVENTORY_MANAGER stocks shelves and sees nothing about people or money")
    void inventoryManagerIsScopedToStock() throws Exception {
        allowed(INVENTORY);
        allowed(ADMIN_PRODUCTS);
        allowed(ANALYTICS);

        forbidden(CUSTOMERS);
        forbidden(AUDIT_LOG);
        forbidden(ACTUATOR);
        forbiddenPost(REFUND);
    }

    // ------------------------------------------------------------------
    // ORDER_MANAGER: money in, never out.
    // ------------------------------------------------------------------

    @Test
    @WithStaff(com.gpstore.entity.Role.ORDER_MANAGER)
    @DisplayName("ORDER_MANAGER works the counter but cannot start a refund")
    void orderManagerCannotRefund() throws Exception {
        allowed(CUSTOMERS);

        // The one action on the payments screen that moves money the other
        // way. Counter staff confirm receipts; they do not send money back.
        forbiddenPost(REFUND);
        forbidden(INVENTORY);
        forbidden(ACTUATOR);
    }

    // ------------------------------------------------------------------
    // DELIVERY_MANAGER: dispatch.
    // ------------------------------------------------------------------

    @Test
    @WithStaff(com.gpstore.entity.Role.DELIVERY_MANAGER)
    @DisplayName("DELIVERY_MANAGER runs dispatch and touches neither stock nor refunds")
    void deliveryManagerIsScopedToDispatch() throws Exception {
        allowed(DELIVERY_PARTNERS);
        allowed(CUSTOMERS);

        forbidden(INVENTORY);
        forbidden(ANALYTICS);
        forbiddenPost(REFUND);
        forbidden(ACTUATOR);
    }

    // ------------------------------------------------------------------
    // SUPPORT: look, do not touch.
    // ------------------------------------------------------------------

    @Test
    @WithStaff(com.gpstore.entity.Role.SUPPORT)
    @DisplayName("SUPPORT can explain an order and moderate a review, and change nothing else")
    void supportIsReadOnly() throws Exception {
        allowed(CUSTOMERS);
        allowed(ADMIN_PRODUCTS);

        forbidden(INVENTORY);
        forbidden(ANALYTICS);
        forbidden(AUDIT_LOG);
        forbidden(ACTUATOR);
        forbidden(DELIVERY_PRICING);
        forbidden(DELIVERY_PARTNERS);
        forbiddenPost(REFUND);
        forbiddenPost(BROADCAST);
        forbiddenPost(CATALOG_SEED);
    }

    // ------------------------------------------------------------------
    // Non-staff. These paths were already closed; the point is that adding
    // roles did not open them.
    // ------------------------------------------------------------------

    @Test
    @WithStaff(com.gpstore.entity.Role.CUSTOMER)
    @DisplayName("a customer gains nothing from the new permission model")
    void customerStillLockedOut() throws Exception {
        forbidden(ANALYTICS);
        forbidden(CUSTOMERS);
        forbidden(INVENTORY);
        forbidden(AUDIT_LOG);
        forbidden(ACTUATOR);
        forbiddenPost(REFUND);
    }

    @Test
    @WithStaff(com.gpstore.entity.Role.DELIVERY_BOY)
    @DisplayName("a rider gains no admin reach - their access comes from ROLE_DELIVERY_BOY alone")
    void riderStillLockedOut() throws Exception {
        forbidden(ANALYTICS);
        forbidden(CUSTOMERS);
        forbidden(INVENTORY);
        forbidden(AUDIT_LOG);
        forbidden(ACTUATOR);
        forbiddenPost(REFUND);
    }
}
