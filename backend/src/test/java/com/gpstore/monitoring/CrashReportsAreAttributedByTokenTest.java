package com.gpstore.monitoring;

import com.gpstore.entity.ClientCrashReport;
import com.gpstore.entity.Customer;
import com.gpstore.entity.DeliveryPartner;
import com.gpstore.entity.Role;
import com.gpstore.repository.ClientCrashReportRepository;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.DeliveryPartnerRepository;
import com.gpstore.service.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A crash report says who crashed, and the phone does not get a vote.
 *
 * WHY THIS FILE EXISTS. The worker APK ships without Firebase on purpose, so
 * its crash handlers had nowhere to send anything and every rider-side crash
 * was silently dropped. This endpoint is the replacement, and the moment an
 * app can POST a row about itself the question becomes what stops it writing
 * a row about somebody else.
 *
 * The answer is structural, the same shape as AdminCreateCustomerRequest in
 * Part 1: CrashReportRequest has no customerId, no workerId and no app field,
 * so there is nothing to forge. These tests hold that structure in place -
 * adding any of those fields later breaks them.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "refund.reconcile-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000",
        // Small enough to hit deliberately in a test, and the production
        // default stays where it is.
        "crash.max-per-reporter-per-hour=3",
        "crash.max-stack-chars=200"
})
@AutoConfigureMockMvc
@DisplayName("Crash reports")
class CrashReportsAreAttributedByTokenTest {

    private static final String MARKER = "CRASHTEST-";

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private CustomerRepository customers;
    @Autowired private DeliveryPartnerRepository partners;
    @Autowired private ClientCrashReportRepository reports;
    @Autowired private JdbcTemplate jdbc;

    /**
     * SQL, not the repositories, and children before parents.
     *
     * Walking the entities to decide what is ours means touching
     * report.getWorker().getName() on a detached lazy proxy, which throws
     * outside a session - the teardown then fails, the fixtures survive, and
     * the next run inherits them. Exactly the failure mode fixed in
     * WorkerLifecycleEndToEndTest; there is no reason to rebuild it here.
     */
    @AfterEach
    void tidyUp() {
        jdbc.update("DELETE FROM client_crash_reports WHERE worker_id IN "
                + "(SELECT id FROM delivery_partners WHERE name LIKE ?)", MARKER + "%");
        jdbc.update("DELETE FROM client_crash_reports WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE full_name LIKE ?)", MARKER + "%");
        jdbc.update("DELETE FROM delivery_partners WHERE name LIKE ?", MARKER + "%");
        jdbc.update("DELETE FROM customers WHERE full_name LIKE ?", MARKER + "%");
    }

    private String body(String message) {
        return "{\"message\":\"" + message + "\",\"stack\":\"#0 main\",\"platform\":\"android\"}";
    }

    @Test
    @DisplayName("nobody signed in cannot file one")
    void anonymousIsRefused() throws Exception {
        int status = mvc.perform(post("/api/client/crash-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("from nowhere")))
                .andReturn().getResponse().getStatus();

        assertTrue(status == 401 || status == 403,
                "an unauthenticated POST must not write a row, got " + status);
        assertTrue(reports.findAll().stream().noneMatch(r -> "from nowhere".equals(r.getMessage())),
                "an unauthenticated request wrote a crash report");
    }

    @Test
    @DisplayName("a rider's crash is filed against the rider from the token")
    void workerCrashIsAttributedFromTheToken() throws Exception {
        DeliveryPartner rider = newWorker();
        String auth = "Bearer " + jwt.generateWorkerToken(
                rider.getId(), rider.getLoginEmail(), 60_000L);

        mvc.perform(post("/api/client/crash-reports")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("rider app died")))
                .andExpect(status().isAccepted());

        ClientCrashReport stored = onlyReportSaying("rider app died");
        assertEquals(ClientCrashReport.App.WORKER, stored.getApp(),
                "a worker session must file against the worker app");
        assertNotNull(stored.getWorker(), "the rider must be named on their own crash");
        assertEquals(rider.getId(), stored.getWorker().getId());
    }

    @Test
    @DisplayName("a body naming somebody else changes nothing")
    void theBodyCannotChooseTheReporter() throws Exception {
        DeliveryPartner rider = newWorker();
        Customer victim = newCustomer();
        String auth = "Bearer " + jwt.generateWorkerToken(
                rider.getId(), rider.getLoginEmail(), 60_000L);

        // THE ATTACK, if the request had those fields: file a crash against
        // another account, or against an app this session has nothing to do
        // with. CrashReportRequest has nowhere to put any of it.
        String forged = "{\"message\":\"forged\",\"customerId\":" + victim.getId()
                + ",\"workerId\":999999,\"app\":\"ADMIN\"}";

        mvc.perform(post("/api/client/crash-reports")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forged))
                .andExpect(status().isAccepted());

        ClientCrashReport stored = onlyReportSaying("forged");
        assertEquals(ClientCrashReport.App.WORKER, stored.getApp(), "app came from the body");
        assertEquals(rider.getId(), stored.getWorker().getId(), "workerId came from the body");
        assertNull(stored.getCustomer(), "a customer was named by the body");
    }

    @Test
    @DisplayName("an enormous stack is shortened, not thrown away")
    void theStackIsTruncatedRatherThanRefused() throws Exception {
        DeliveryPartner rider = newWorker();
        String auth = "Bearer " + jwt.generateWorkerToken(
                rider.getId(), rider.getLoginEmail(), 60_000L);

        // The deepest stacks belong to the worst crashes. Refusing the body
        // would mean those are precisely the ones never recorded.
        String huge = "x".repeat(5000);
        mvc.perform(post("/api/client/crash-reports")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"deep one\",\"stack\":\"" + huge + "\"}"))
                .andExpect(status().isAccepted());

        ClientCrashReport stored = onlyReportSaying("deep one");
        assertEquals(200, stored.getStack().length(),
                "the stack was not truncated to the configured cap");
    }

    @Test
    @DisplayName("a crash loop cannot bury the crashes already recorded")
    void theHourlyCapHolds() throws Exception {
        DeliveryPartner rider = newWorker();
        String auth = "Bearer " + jwt.generateWorkerToken(
                rider.getId(), rider.getLoginEmail(), 60_000L);

        // An app dying on startup restarts and dies again. Every one of these
        // is accepted - telling a crashing app that its crash report also
        // failed helps nobody - but only the cap is stored.
        for (int i = 0; i < 8; i++) {
            mvc.perform(post("/api/client/crash-reports")
                            .header("Authorization", auth)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("loop " + i)))
                    .andExpect(status().isAccepted());
        }

        long mine = reports.findAll().stream()
                .filter(r -> r.getWorker() != null && r.getWorker().getId().equals(rider.getId()))
                .count();
        assertEquals(3, mine, "the hourly cap did not hold; stored " + mine);
    }

    // ------------------------------------------------------------- fixtures

    private ClientCrashReport onlyReportSaying(String message) {
        List<ClientCrashReport> found = reports.findAll().stream()
                .filter(r -> message.equals(r.getMessage())).toList();
        assertEquals(1, found.size(), "expected exactly one report saying '" + message + "'");
        return found.get(0);
    }

    private DeliveryPartner newWorker() {
        DeliveryPartner worker = new DeliveryPartner();
        worker.setName(MARKER + System.nanoTime());
        worker.setActive(true);
        worker.setLoginEmail("crash-" + System.nanoTime() + "@gmail.com");
        worker.setPasswordHash("$2a$10$notcheckedbythistest000000000000000000000000000000000000");
        return partners.save(worker);
    }

    private Customer newCustomer() {
        Customer customer = new Customer();
        customer.setFullName(MARKER + System.nanoTime());
        customer.setEmail("crashc-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber("9" + String.format("%09d", System.nanoTime() % 1_000_000_000L));
        customer.setPassword("irrelevant");
        customer.setRole(Role.CUSTOMER);
        customer.setActive(true);
        customer.setEnabled(true);
        return customers.save(customer);
    }
}
