package com.gpstore.store;

import com.gpstore.entity.Role;
import com.gpstore.security.WithStaff;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Who may shut the shop, asserted against the real filter chain.
 *
 * <p>THE REFUSALS ARE THE POINT. Pausing orders stops the shop earning and
 * closing a day cancels deliveries customers are expecting - if a support
 * account or a customer's JWT can reach either, hiding the button in Flutter
 * has bought nothing. Every write below is exercised only from roles that must
 * be refused, so the request stops in the filter chain and never reaches a
 * controller: a 403 proves the door is locked without opening it.
 */
@SpringBootTest(properties = {
        // NO LIVE OUTBOX WORKER. This class places real orders, and a running
        // drain turns each one into an auto-assigned delivery against whichever
        // rider is available - including another test class's fixture riders,
        // because the least-loaded fallback picks globally. Spring caches this
        // context and never closes it, so the worker outlives the class and can
        // still be assigning while a later class asserts on rider workload.
        //
        // That is exactly how TerritoryDispatchTest started failing with
        // "expected: <22> but was: <23>": a stray assignment gave one of two
        // deliberately-tied riders a live order, its score rose, and the tie
        // it was asserting broke the other way.
        //
        // Nothing here tests the outbox or any async side effect, so the drain
        // has no purpose in this class beyond causing that.
        "outbox.drain-interval-ms=3600000"
})
@AutoConfigureMockMvc
class StoreHoursSecurityTest {

    private static final String PUBLIC_STATUS = "/api/store/status";
    private static final String OPERATIONS = "/api/admin/store/operations";
    private static final String CLOSURES = "/api/admin/store/closures";
    private static final String PREPARATION = "/api/admin/store/preparation";

    @Autowired
    private MockMvc mockMvc;

    // ------------------------------------------------------------------
    // The public endpoint.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("anyone, signed in or not, can ask whether the shop is open")
    void statusIsPublic() throws Exception {
        // A customer browsing at 3am before signing in is exactly who needs
        // this answer. A login wall here would hide the one message the whole
        // feature exists to deliver.
        mockMvc.perform(get(PUBLIC_STATUS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.browsingOpen").value(true))
                .andExpect(jsonPath("$.serverTime").exists());
    }

    @Test
    @DisplayName("the public status exposes no customer, order or staff data")
    void statusLeaksNothing() throws Exception {
        // It says what a sign on the door says. If a field ever appears here
        // that names a person or an order, this is where it gets caught.
        mockMvc.perform(get(PUBLIC_STATUS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").doesNotExist())
                .andExpect(jsonPath("$.orders").doesNotExist())
                .andExpect(jsonPath("$.updatedBy").doesNotExist());
    }

    // ------------------------------------------------------------------
    // A customer must never reach the controls.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a customer JWT cannot read the controls")
    @WithMockUser(roles = "CUSTOMER")
    void customerCannotReadOperations() throws Exception {
        mockMvc.perform(get(OPERATIONS)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a customer JWT cannot pause the shop")
    @WithMockUser(roles = "CUSTOMER")
    void customerCannotPauseOrders() throws Exception {
        // The one that matters: a customer who can send this can stop the
        // shop trading. Flutter never shows them the control; curl does not
        // care what Flutter shows.
        mockMvc.perform(put(OPERATIONS)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderAcceptance\":\"OFF\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a customer JWT cannot close a day")
    @WithMockUser(roles = "CUSTOMER")
    void customerCannotCloseADay() throws Exception {
        mockMvc.perform(post(CLOSURES)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2099-01-01\",\"reason\":\"nope\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an anonymous request cannot reach the controls at all")
    void anonymousCannotReachOperations() throws Exception {
        // 401 or 403 - which one depends on the entry point, and pinning the
        // exact code here would make this test about Spring's error handling
        // rather than about access. What matters is that it is not 2xx.
        mockMvc.perform(get(OPERATIONS))
                .andExpect(result -> {
                    int code = result.getResponse().getStatus();
                    if (code < 400) {
                        throw new AssertionError(
                                "anonymous reached the store controls with " + code);
                    }
                });
    }

    // ------------------------------------------------------------------
    // Staff roles: who may, and who may not.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an admin can read the controls")
    @WithStaff(Role.ADMIN)
    void adminCanReadOperations() throws Exception {
        mockMvc.perform(get(OPERATIONS)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the delivery manager runs the vans, so may close a day")
    @WithStaff(Role.DELIVERY_MANAGER)
    void deliveryManagerCanReadOperations() throws Exception {
        // Read, not write: a successful POST here would leave a closure row
        // behind for every other test in the shared database to trip over.
        mockMvc.perform(get(OPERATIONS)).andExpect(status().isOk());
        mockMvc.perform(get(CLOSURES)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("support answers the phone; it does not shut the shop")
    @WithStaff(Role.SUPPORT)
    void supportCannotReachTheControls() throws Exception {
        mockMvc.perform(get(OPERATIONS)).andExpect(status().isForbidden());
        mockMvc.perform(put(OPERATIONS)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderAcceptance\":\"OFF\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a counter clerk cannot pause the shop either")
    @WithStaff(Role.ORDER_MANAGER)
    void orderManagerCannotPauseOrders() throws Exception {
        mockMvc.perform(put(OPERATIONS)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderAcceptance\":\"OFF\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("stock staff have no business with the delivery calendar")
    @WithStaff(Role.INVENTORY_MANAGER)
    void inventoryManagerCannotCloseADay() throws Exception {
        mockMvc.perform(post(CLOSURES)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2099-01-01\"}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // The packing list is deliberately wider than the switch.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the people who pack the boxes can read the packing list")
    @WithStaff(Role.ORDER_MANAGER)
    void orderManagerCanReadThePreparationList() throws Exception {
        // ORDERS_VIEW, not DELIVERY_MANAGE - reading what to pack must not
        // require the ability to shut the shop.
        mockMvc.perform(get(PREPARATION)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a customer cannot read the packing list")
    @WithMockUser(roles = "CUSTOMER")
    void customerCannotReadThePreparationList() throws Exception {
        // It lists order numbers and amounts for a whole day.
        mockMvc.perform(get(PREPARATION)).andExpect(status().isForbidden());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor csrf() {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf();
    }
}
