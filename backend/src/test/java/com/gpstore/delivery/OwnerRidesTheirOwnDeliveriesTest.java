package com.gpstore.delivery;

import com.gpstore.entity.Customer;
import com.gpstore.entity.DeliveryPartner;
import com.gpstore.entity.Role;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.DeliveryPartnerRepository;
import com.gpstore.service.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The whole journey, over HTTP, with a real token.
 *
 * WHY THIS EXISTS AND THE SERVICE TESTS WERE NOT ENOUGH. This failed for the
 * shop three times running, and each time the service-level tests were green.
 * They were green because they call the service directly, and every part that
 * actually broke lives OUTSIDE it: the authorization rule on /api/worker/**,
 * the permission the controller derives for the caller, JwtFilter turning a
 * role into authorities, and requireWorker()'s findByAccountId.
 *
 * So this test drives the two requests the shop actually makes, through the
 * filter chain, with a signed JWT:
 *
 *   1. the owner links their own address to a roster row
 *   2. the owner opens the worker app
 *
 * A pass here means the sequence works end to end. Nothing short of that has
 * been worth trusting on this feature.
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
@DisplayName("The owner links their own address and then opens the worker app")
class OwnerRidesTheirOwnDeliveriesTest {

    /** Every row this class writes carries this, so cleanUp can find it. */
    private static final String MARKER = "OWNERRIDE-";

    @Autowired private MockMvc mvc;
    @Autowired private DeliveryPartnerRepository partners;
    @Autowired private CustomerRepository customers;
    @Autowired private JwtService jwt;
    @Autowired private PasswordEncoder encoder;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.update("UPDATE delivery_partners SET account_customer_id = NULL WHERE name LIKE ?",
                MARKER + "%");
        jdbc.update("DELETE FROM delivery_partners WHERE name LIKE ?", MARKER + "%");
        jdbc.update("DELETE FROM customers WHERE full_name LIKE ?", MARKER + "%");
    }

    private static String unique() {
        return Long.toString(System.nanoTime(), 36);
    }

    /**
     * Unavailable and inactive on purpose: nothing here tests dispatch, and an
     * available fixture rider is a live auto-assignment candidate for every
     * other test sharing this database.
     */
    private DeliveryPartner rosterOnlyPartner() {
        DeliveryPartner partner = new DeliveryPartner();
        partner.setName(MARKER + unique());
        partner.setMobile("9" + System.nanoTime() % 1000000000L);
        partner.setVehicleType("BIKE");
        partner.setAvailable(false);
        partner.setActive(false);
        // Saved through the repository, not the service: the service links a
        // mobile account on create, and the state the shop reported is a roster
        // row with no account at all.
        return partners.save(partner);
    }

    private Customer owner(String email, Role role) {
        Customer account = new Customer();
        account.setFullName(MARKER + "Owner");
        account.setEmail(email);
        account.setMobileNumber("8" + System.nanoTime() % 1000000000L);
        account.setPassword(encoder.encode("the-owners-own-password"));
        account.setRole(role);
        account.setEnabled(true);
        account.setActive(true);
        account.setVerified(true);
        return customers.save(account);
    }

    private String tokenFor(Customer account) {
        return "Bearer " + jwt.generateToken(account.getId(), account.getEmail(), account.getRole());
    }

    @Test
    @DisplayName("link, then /api/worker/me answers as the rider instead of refusing")
    void theOwnerCanLinkThemselvesAndOpenTheWorkerApp() throws Exception {
        DeliveryPartner partner = rosterOnlyPartner();
        String email = "owner-" + unique() + "@gmail.com";
        Customer admin = owner(email, Role.ADMIN);
        String auth = tokenFor(admin);

        // BEFORE: exactly what the shop was seeing. The token is fine and the
        // account clears /api/worker/** (ADMIN holds DELIVERY_MANAGE), so this
        // is not a 401 or a 403 - it is the roster lookup coming back empty.
        mvc.perform(get("/api/worker/me").header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("not linked to a worker record")));

        // THE SAVE the admin screen makes. An address and a password, the same
        // two fields every other rider gets - which is what kept being refused.
        mvc.perform(put("/api/delivery-partners/" + partner.getId() + "/login-account")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"a-real-passphrase\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.canSignIn").value(true));

        // AFTER: the same request that failed a moment ago now resolves the
        // roster row. This assertion is the entire point of the feature.
        mvc.perform(get("/api/worker/me").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(partner.getName()));

        Customer after = customers.findById(admin.getId()).orElseThrow();
        assertEquals(Role.ADMIN, after.getRole(),
                "and doing it must not have cost the owner their own permissions.");
        assertTrue(encoder.matches("a-real-passphrase", after.getPassword()),
                "The password typed into the roster screen is the one that now works.");
    }

    @Test
    @DisplayName("a roster-only operator still cannot set a password on the owner's account")
    void aRosterOnlyOperatorCannotTakeTheOwnersAccount() throws Exception {
        DeliveryPartner partner = rosterOnlyPartner();
        String ownerEmail = "boss-" + unique() + "@gmail.com";
        Customer boss = owner(ownerEmail, Role.ADMIN);
        String bossPasswordBefore = boss.getPassword();

        // DELIVERY_MANAGER edits the roster and nothing about accounts: they
        // hold DELIVERY_MANAGE and not CUSTOMERS_MANAGE. If they could write a
        // password here they could sign in as the owner.
        Customer dispatcher = owner("dispatch-" + unique() + "@gmail.com", Role.DELIVERY_MANAGER);

        mvc.perform(put("/api/delivery-partners/" + partner.getId() + "/login-account")
                        .header("Authorization", tokenFor(dispatcher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + ownerEmail + "\",\"password\":\"chosen-by-them\"}"))
                .andExpect(status().isConflict());

        Customer after = customers.findById(boss.getId()).orElseThrow();
        assertEquals(bossPasswordBefore, after.getPassword(),
                "The owner's password must be byte-identical after a refused attempt.");
        assertFalse(encoder.matches("chosen-by-them", after.getPassword()));
        assertNull(partners.findById(partner.getId()).orElseThrow().getAccount(),
                "and the refusal must be total - no half-link left behind.");
    }

    @Test
    @DisplayName("that same operator may still link the owner without touching the password")
    void aRosterOnlyOperatorMayStillLink() throws Exception {
        DeliveryPartner partner = rosterOnlyPartner();
        String ownerEmail = "boss2-" + unique() + "@gmail.com";
        Customer boss = owner(ownerEmail, Role.ADMIN);
        String before = boss.getPassword();
        Customer dispatcher = owner("dispatch2-" + unique() + "@gmail.com", Role.DELIVERY_MANAGER);

        // Linking is not a takeover: it writes no credential, so the narrower
        // operator is allowed to do it. Blank password means "leave it alone".
        mvc.perform(put("/api/delivery-partners/" + partner.getId() + "/login-account")
                        .header("Authorization", tokenFor(dispatcher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + ownerEmail + "\",\"password\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canSignIn").value(true));

        assertEquals(before, customers.findById(boss.getId()).orElseThrow().getPassword(),
                "Their password must be untouched.");
        mvc.perform(get("/api/worker/me").header("Authorization", tokenFor(boss)))
                .andExpect(status().isOk());
    }
}
