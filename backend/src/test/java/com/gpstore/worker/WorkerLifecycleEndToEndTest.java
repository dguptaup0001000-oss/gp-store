package com.gpstore.worker;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Role;
import com.gpstore.repository.CustomerRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The whole thing the shop actually does, over HTTP.
 *
 * WHY THIS FILE IS THE POINT. Four attempts at worker login passed their
 * service-level tests and failed on the phone, because everything that broke
 * lived between the service and the request: the authorization rules, the
 * filter, the token, the lookup. So this drives the real endpoints - hire,
 * sign in, pause, resume, delete - and asserts what the rider's screen would
 * show at each step.
 *
 * If this file is green, the feature works.
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
@DisplayName("Hire a worker, they sign in, pause them, remove them")
class WorkerLifecycleEndToEndTest {

    private static final String MARKER = "E2EWORKER-";
    private static final String PASSWORD = "a-real-passphrase";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CustomerRepository customers;
    @Autowired private JwtService jwt;
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder encoder;

    /**
     * A REAL TOKEN PER CALLER, not @WithStaff, and that is not a style choice.
     *
     * @WithStaff writes the security context into a thread local before the
     * test method. The filter chain clears that context when a request that
     * carries its own Authorization header finishes - so the first worker
     * request in a test silently logged the ADMIN out, and every admin call
     * after it came back 403. Every request here carries its own header, so
     * one caller cannot affect another, which is also how the real apps work.
     */
    private String adminAuth;

    /**
     * ONE @BeforeEach, in a fixed order, because two of them do not have one.
     * JUnit does not order @BeforeEach methods, so a separate wipe could - and
     * did - run after the admin was created and delete it, leaving every
     * request 401. Wipe first, then create, in one method.
     */
    @BeforeEach
    void freshStart() {
        wipeFixtures();

        Customer admin = new Customer();
        admin.setFullName(MARKER + "Owner");
        admin.setEmail("shop-" + unique() + "@gmail.com");
        admin.setMobileNumber("7" + String.format("%09d", System.nanoTime() % 1000000000L));
        admin.setPassword(encoder.encode("the-owners-own-password"));
        admin.setRole(Role.ADMIN);
        admin.setEnabled(true);
        admin.setActive(true);
        admin.setVerified(true);
        admin = customers.save(admin);
        adminAuth = "Bearer " + jwt.generateToken(admin.getId(), admin.getEmail(), admin.getRole());
    }

    @AfterEach
    void tidyUp() {
        wipeFixtures();
    }

    private void wipeFixtures() {
        jdbc.update("DELETE FROM delivery_partners WHERE name LIKE ?", MARKER + "%");
        jdbc.update("DELETE FROM customers WHERE full_name LIKE ?", MARKER + "%");
    }

    /** Drops the per-request noise so only the answer itself is compared. */
    private static String scrub(String body) {
        return body.replaceAll("\"(path|timestamp)\":\"[^\"]*\",?", "");
    }

    /**
     * Ten digits, always.
     *
     * "9" + (nanoTime % 1e9) loses leading zeros, so it built a NINE digit
     * number about one run in ten - and the server rightly refused it. A
     * fixture that is invalid one time in ten reads as an intermittent
     * product bug, which is the most expensive kind of test to own.
     */
    private static String tenDigits() {
        return "9" + String.format("%09d", System.nanoTime() % 1000000000L);
    }

    private static String unique() {
        return Long.toString(System.nanoTime(), 36);
    }

    /** The admin dashboard's "add worker" form, as JSON. */
    private String form(String name, String email, String mobile, String password) {
        return "{\"name\":\"" + name + "\",\"loginEmail\":\"" + email + "\","
                + "\"mobile\":" + (mobile == null ? "null" : "\"" + mobile + "\"") + ","
                + "\"password\":\"" + password + "\",\"vehicleType\":\"BIKE\","
                + "\"vehicleNumber\":\"UP32 AB 1234\",\"available\":true}";
    }

    /** Uses the shop's own admin token, like the dashboard does. */
    private long hire(String email, String mobile) throws Exception {
        MvcResult created = mvc.perform(post("/api/admin/workers").header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(form(MARKER + unique(), email, mobile, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canSignIn").value(true))
                .andReturn();
        // Number, not Long: JsonPath hands back an Integer for ids this small.
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** Signs in exactly as the worker app does, and returns the bearer header. */
    private String signIn(String identifier) throws Exception {
        MvcResult result = mvc.perform(post("/api/worker/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + identifier + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return "Bearer " + com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.accessToken");
    }

    @Test
    @DisplayName("the shop hires a worker and they sign in with either identifier")
    void hireThenSignInWithEitherIdentifier() throws Exception {
        String email = "rider-" + unique() + "@gmail.com";
        String mobile = tenDigits();

        long workerId = hire(email, mobile);

        // BOTH IDENTIFIERS, because a rider in the street uses whichever they
        // remember and should not have to guess which one the shop typed in.
        for (String identifier : new String[]{email, mobile, "+91 " + mobile, email.toUpperCase()}) {
            String auth = signIn(identifier);
            mvc.perform(get("/api/worker/me").header("Authorization", auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").exists());
        }

        // And the password never comes back out of the admin API.
        String body = mvc.perform(get("/api/admin/workers/" + workerId).header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains(PASSWORD), "The password must never be echoed.");
        assertFalse(body.toLowerCase().contains("passwordhash"), "Nor the hash: " + body);
    }

    @Test
    @DisplayName("a wrong password and an unknown worker are refused identically")
    void refusalsDoNotLeakWhoExists() throws Exception {
        String email = "rider-" + unique() + "@gmail.com";
        hire(email, null);

        String wrongPassword = mvc.perform(post("/api/worker/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + email + "\",\"password\":\"not-the-one\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknown = mvc.perform(post("/api/worker/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"nobody-" + unique() + "@gmail.com\",\"password\":\"not-the-one\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Otherwise the login screen answers "does this person work here?"
        // Timestamp and path differ per request and say nothing about the
        // account; the MESSAGE is the part that must not distinguish them.
        assertEquals(scrub(unknown), scrub(wrongPassword),
                "A wrong password and an unknown worker must be indistinguishable.");
    }

    @Test
    @DisplayName("pausing a worker stops them mid-session, and the reason reaches their screen")
    void suspensionTakesEffectImmediately() throws Exception {
        String email = "rider-" + unique() + "@gmail.com";
        long workerId = hire(email, null);
        String auth = signIn(email);

        // Working normally.
        mvc.perform(get("/api/worker/me").header("Authorization", auth)).andExpect(status().isOk());

        mvc.perform(post("/api/admin/workers/" + workerId + "/suspend").header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minutes\":120,\"reason\":\"Bike is being repaired.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suspended").value(true))
                .andExpect(jsonPath("$.canSignIn").value(false));

        // THE TOKEN THEY ALREADY HOLD STOPS WORKING. A pause that only applied
        // at the next login would do nothing for twelve hours, which is how
        // long a worker session lasts.
        String refused = mvc.perform(get("/api/worker/me").header("Authorization", auth))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        assertTrue(refused.contains("paused for another 2 hours"), refused);
        assertTrue(refused.contains("Bike is being repaired."), refused);

        // And they cannot simply sign in again to get a fresh one.
        mvc.perform(post("/api/worker/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());

        // Lifting it early puts them straight back to work.
        mvc.perform(post("/api/admin/workers/" + workerId + "/resume").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canSignIn").value(true));
        mvc.perform(get("/api/worker/me").header("Authorization", signIn(email)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("deleting a worker ends their session and frees their email for someone else")
    void deleteBlocksLoginAndReleasesTheIdentifiers() throws Exception {
        String email = "rider-" + unique() + "@gmail.com";
        String mobile = tenDigits();
        long workerId = hire(email, mobile);
        String auth = signIn(email);
        mvc.perform(get("/api/worker/me").header("Authorization", auth)).andExpect(status().isOk());

        mvc.perform(delete("/api/admin/workers/" + workerId).header("Authorization", adminAuth)).andExpect(status().isNoContent());

        // Their open app stops working on the next tap, not at token expiry.
        mvc.perform(get("/api/worker/me").header("Authorization", auth))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/worker/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());

        // Gone from the roster, but the row survives so finished orders still
        // show who delivered them.
        mvc.perform(get("/api/admin/workers/" + workerId).header("Authorization", adminAuth)).andExpect(status().isNotFound());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_partners WHERE id = ?", Integer.class, workerId),
                "The row is kept on purpose - deliveries point at it.");

        // AND THE ADDRESS IS FREE AGAIN, which is what a shop expects once
        // somebody has left. A unique index over live rows only is what makes
        // this work; a plain one would refuse forever.
        long replacement = hire(email, mobile);
        assertNotEquals(workerId, replacement);
        mvc.perform(get("/api/worker/me").header("Authorization", signIn(email)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the mandatory fields are the login, and only the login")
    void onlyTheLoginIsMandatory() throws Exception {
        // No phone, no vehicle - a shop that has not written those down yet
        // must still be able to put someone to work.
        mvc.perform(post("/api/admin/workers").header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + MARKER + unique() + "\",\"loginEmail\":\"sparse-"
                                + unique() + "@gmail.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canSignIn").value(true));

        // The login itself is not optional.
        mvc.perform(post("/api/admin/workers").header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + MARKER + unique() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/workers").header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + MARKER + unique() + "\",\"loginEmail\":\"nopass-"
                                + unique() + "@gmail.com\"}"))
                .andExpect(status().isBadRequest());
        // And a password worth having.
        mvc.perform(post("/api/admin/workers").header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + MARKER + unique() + "\",\"loginEmail\":\"short-"
                                + unique() + "@gmail.com\",\"password\":\"abc\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("two workers cannot share an identifier, because the login screen takes both")
    void identifiersAreUnique() throws Exception {
        String email = "shared-" + unique() + "@gmail.com";
        String mobile = tenDigits();
        hire(email, mobile);

        mvc.perform(post("/api/admin/workers").header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(form(MARKER + unique(), email, null, PASSWORD)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/admin/workers").header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(form(MARKER + unique(), "other-" + unique() + "@gmail.com", mobile, PASSWORD)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("editing a worker leaves their password alone unless a new one is typed")
    void blankPasswordOnEditKeepsTheOldOne() throws Exception {
        String email = "rider-" + unique() + "@gmail.com";
        long workerId = hire(email, null);

        // Changing the vehicle must not silently reset the password - the
        // shop would have no idea until the rider phoned.
        mvc.perform(put("/api/admin/workers/" + workerId).header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + MARKER + "renamed\",\"loginEmail\":\"" + email
                                + "\",\"vehicleType\":\"SCOOTER\",\"password\":\"\"}"))
                .andExpect(status().isOk());
        assertNotNull(signIn(email), "Their original password must still work.");

        // A typed one does replace it.
        mvc.perform(put("/api/admin/workers/" + workerId).header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + MARKER + "renamed\",\"loginEmail\":\"" + email
                                + "\",\"password\":\"a-brand-new-passphrase\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/worker/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a delivery worker cannot edit the roster they appear on")
    void workersCannotManageWorkers() throws Exception {
        String email = "rider-" + unique() + "@gmail.com";
        hire(email, null);
        String workerAuth = signIn(email);

        // A worker's own token must not open the roster they appear on -
        // otherwise a rider could lift their own suspension.
        mvc.perform(get("/api/admin/workers").header("Authorization", workerAuth))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/workers").header("Authorization", workerAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(form(MARKER + unique(), "sneaky-" + unique() + "@gmail.com", null, PASSWORD)))
                .andExpect(status().isForbidden());
    }
}
