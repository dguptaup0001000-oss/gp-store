package com.gpstore.worker;

import com.gpstore.support.DispatchSafeTeardown;
import com.gpstore.entity.*;
import com.gpstore.enums.OrderStatus;
import com.gpstore.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The worker pack-scan, against a real database.
 *
 * WHAT THESE TESTS ARE REALLY GUARDING. The brief's hard rules are all
 * negative - the app must NOT be trusted, a second worker must NOT be able to
 * take a taken order, the customer must NOT be told the order is on its way.
 * Negative rules are the ones that rot silently: nothing fails when a check is
 * removed, the flow just gets more permissive. So each one is written here as
 * an assertion that fails loudly if it is ever relaxed.
 *
 * Fixtures are prefixed and removed in @AfterEach, because this suite shares
 * one database and a stray worker record is a candidate for another test's
 * auto-assignment.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class WorkerPackScanTest {

    private static final String PREFIX = "WS-";
    private static final String MARKER = "WORKER_SCAN_TEST";

    /** A square around the shop, so the fixture address lands in it. */
    private static final String BOUNDARY =
            "[[27.15,83.93],[27.15,83.95],[27.18,83.95],[27.18,83.93]]";
    private static final double ADDR_LAT = 27.162;
    private static final double ADDR_LNG = 83.940;

    @Autowired private WorkerScanService scanService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderScanEventRepository scanRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private AddressRepository addressRepository;
    @Autowired private DeliveryPartnerRepository partnerRepository;
    @Autowired private DeliveryZoneRepository zoneRepository;
    @Autowired private DeliverySubzoneRepository subzoneRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExecutorService orderSideEffectsExecutor;

    private DeliverySubzone territory;
    private Customer shopper;
    private Order order;
    private DeliveryPartner primary;

    @BeforeEach
    void setUp() {
        cleanUp();

        DeliveryZone zone = new DeliveryZone();
        zone.setCode(PREFIX + "Z1");
        zone.setName("Worker scan test zone");
        zone.setActive(true);
        zone = zoneRepository.save(zone);

        territory = new DeliverySubzone();
        territory.setZone(zone);
        territory.setCode(PREFIX + "Z1A");
        territory.setName("Scan test territory");
        territory.setBoundary(BOUNDARY);
        territory.setActive(true);
        territory = subzoneRepository.save(territory);

        primary = newWorker("primary");
        territory.setPrimaryPartner(primary);
        territory = subzoneRepository.save(territory);

        shopper = newCustomer(MARKER + "-shopper", Role.CUSTOMER);
        order = newOrder(shopper, territory, OrderStatus.PACKING);
    }

    /**
     * Retire the fixture partners, then delete everything, as one retried unit.
     *
     * EVERY statement is inside deleteFixtureRows, and that is the fix rather
     * than a tidy-up. This teardown used to run two thirds of its deletes
     * inline here and only the partner ones inside a tolerant try/catch - so a
     * batch that gained a delivery mid-teardown made the guarded part swallow
     * its error, the partners survived, and DELETE FROM customers below hit
     * the foreign key from one of them with nothing to catch it. A leftover
     * that was tolerated on purpose became a red suite blaming the wrong line.
     *
     * See DispatchSafeTeardown for the race and why retiring comes first.
     */
    @AfterEach
    void cleanUp() {
        // Pack-scan notifications run after commit on the side-effects pool.
        // If we delete orders while a task is still in flight, the INSERT
        // lands after DELETE FROM notifications and the FK on orders fails.
        // Waited for once, outside the retry: it is this context's own pool,
        // and re-awaiting it on every pass would only add latency.
        awaitSideEffectsIdle();

        DispatchSafeTeardown.sweep(this::retirePartners, this::deleteFixtureRows,
                // TOLERATE: this class drives real pack-scan dispatch, so an
                // assignment can legitimately still be arriving. What is left
                // behind is retired and therefore inert.
                DispatchSafeTeardown.WhenStuck.TOLERATE);
    }

    private void retirePartners() {
        jdbc.update("UPDATE delivery_partners SET available = false, active = false "
                + "WHERE name LIKE ? OR account_customer_id IN "
                + "(SELECT id FROM customers WHERE full_name LIKE ?)",
                PREFIX + "%", MARKER + "%");
    }

    private void deleteFixtureRows() {
        jdbc.update("DELETE FROM order_scan_events WHERE order_number LIKE ? OR worker_name LIKE ?",
                PREFIX + "%", PREFIX + "%");
        jdbc.update("DELETE FROM notifications WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE full_name LIKE ?)", MARKER + "%");
        jdbc.update("UPDATE orders SET packed_by_partner_id = NULL, assigned_worker_partner_id = NULL "
                + "WHERE order_number LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM deliveries WHERE order_id IN "
                + "(SELECT id FROM orders WHERE order_number LIKE ?)", PREFIX + "%");
        jdbc.update("DELETE FROM orders WHERE order_number LIKE ?", PREFIX + "%");
        jdbc.update("UPDATE addresses SET subzone_id = NULL WHERE full_name LIKE ?", MARKER + "%");
        jdbc.update("DELETE FROM addresses WHERE full_name LIKE ?", MARKER + "%");
        jdbc.update("UPDATE delivery_subzones SET primary_partner_id = NULL WHERE code LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM subzone_backup_partners WHERE subzone_id IN "
                + "(SELECT id FROM delivery_subzones WHERE code LIKE ?)", PREFIX + "%");
        jdbc.update("DELETE FROM delivery_subzones WHERE code LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM delivery_zones WHERE code LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM deliveries WHERE batch_id IN "
                + "(SELECT id FROM delivery_batches WHERE delivery_partner_id IN "
                + " (SELECT id FROM delivery_partners WHERE name LIKE ? "
                + "  OR account_customer_id IN (SELECT id FROM customers WHERE full_name LIKE ?)))",
                PREFIX + "%", MARKER + "%");
        jdbc.update("DELETE FROM delivery_batches WHERE delivery_partner_id IN "
                + "(SELECT id FROM delivery_partners WHERE name LIKE ? "
                + " OR account_customer_id IN (SELECT id FROM customers WHERE full_name LIKE ?))",
                PREFIX + "%", MARKER + "%");
        jdbc.update("DELETE FROM delivery_partners WHERE name LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM delivery_partners WHERE account_customer_id IN "
                + "(SELECT id FROM customers WHERE full_name LIKE ?)", MARKER + "%");
        jdbc.update("DELETE FROM notifications WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE full_name LIKE ?)", MARKER + "%");
        jdbc.update("DELETE FROM customers WHERE full_name LIKE ?", MARKER + "%");
    }

    // ------------------------------------------------------------- fixtures

    private Customer newCustomer(String name, Role role) {
        Customer c = new Customer();
        c.setFullName(name);
        c.setEmail(name.toLowerCase().replace("_", "-") + "-" + System.nanoTime() + "@example.com");
        c.setMobileNumber(String.valueOf(9100000000L + (System.nanoTime() % 89999999L)));
        c.setPassword("{noop}not-a-real-password");
        c.setRole(role);
        c.setEnabled(true);
        c.setActive(true);
        c.setVerified(true);
        return customerRepository.save(c);
    }

    private DeliveryPartner newWorker(String label) {
        Customer account = newCustomer(MARKER + "-" + label, Role.DELIVERY_BOY);
        DeliveryPartner p = new DeliveryPartner();
        p.setName(PREFIX + label);
        p.setMobile(account.getMobileNumber());
        p.setVehicleType("BIKE");
        p.setAvailable(true);
        p.setActive(true);
        p.setAccount(account);
        return partnerRepository.save(p);
    }

    private Long accountOf(DeliveryPartner worker) {
        return worker.getAccount().getId();
    }

    private Order newOrder(Customer customer, DeliverySubzone subzone, OrderStatus status) {
        Address address = new Address();
        address.setFullName(MARKER + "-address");
        address.setMobileNumber("9100000000");
        address.setHouseNo("1");
        address.setArea("Test");
        address.setCity("Malhia");
        address.setPincode("274401");
        address.setLatitude(ADDR_LAT);
        address.setLongitude(ADDR_LNG);
        address.setCustomer(customer);
        address.setSubzone(subzone);
        address = addressRepository.save(address);

        Order o = new Order();
        o.setOrderNumber(PREFIX + System.nanoTime());
        o.setCustomer(customer);
        o.setAddress(address);
        o.setOrderStatus(status);
        o.setTotalAmount(new BigDecimal("250.00"));
        o.setOrderDate(LocalDateTime.now());
        o.setActive(true);
        return orderRepository.save(o);
    }

    /**
     * Issues a token and returns it.
     *
     * issueToken loads its OWN copy of the order, so the caller's instance is
     * stale the moment this returns - saving that stale instance afterwards
     * would write its null qrToken straight back over the one just issued, and
     * the scan would fail with UNKNOWN_TOKEN for reasons that have nothing to
     * do with the code under test. Tests that change an order after issuing
     * must reload it, which is what {@link #reload} is for.
     */
    private String issue(Order o) {
        return scanService.issueToken(o.getId());
    }

    private Order reload(Order o) {
        return orderRepository.findById(o.getId()).orElseThrow();
    }

    // ------------------------------------------------------------ the token

    @Test
    @DisplayName("the QR token is random and carries nothing about the customer")
    void tokenIsOpaqueAndUnguessable() {
        String a = issue(order);
        Order other = newOrder(shopper, territory, OrderStatus.PACKING);
        String b = issue(other);

        assertNotEquals(a, b, "two orders must never share a token");
        assertTrue(a.length() >= 24, "a short token is a guessable token; was " + a.length());

        // The brief is explicit that the label must not carry customer data.
        // Anything derived from the order would leak by construction, so the
        // test is that NOTHING recognisable appears in it.
        String lowered = a.toLowerCase();
        assertFalse(lowered.contains(order.getOrderNumber().toLowerCase()), a);
        assertFalse(lowered.contains(shopper.getMobileNumber()), a);
        assertFalse(lowered.contains(String.valueOf(order.getId())), a);
        assertFalse(lowered.contains("250"), "the order total must not appear in the label: " + a);
    }

    // ------------------------------------------------------- the happy path

    @Test
    @DisplayName("the territory's own worker scans, and the order becomes PACKED")
    void primaryWorkerScansSuccessfully() {
        String token = issue(order);

        var result = scanService.packScan(accountOf(primary), token, "req-1");

        assertTrue(result.accepted(), result.message());
        assertEquals("ACCEPTED", result.outcome());
        assertEquals(PREFIX + "Z1A", result.subzoneCode());
        assertEquals(PREFIX + "Z1", result.zoneCode());
        assertEquals("D" + primary.getId(), result.workerCode());

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.PACKED, reloaded.getOrderStatus());
        assertEquals(primary.getId(), reloaded.getPackedByPartner().getId());
        assertNotNull(reloaded.getPackedAt());
        assertNotNull(reloaded.getQrTokenUsedAt(), "a used token must be marked used");
    }

    @Test
    @DisplayName("the customer is told the order is packed, and nothing about delivery")
    void customerHearsExactlyOneThing() {
        // THE RULE FROM THE BRIEF, as an assertion. These workers are shop
        // employees; a scan happens at the counter with the order still in the
        // shop. Any word about delivery would send a customer to wait at the
        // door for something that has not left.
        scanService.packScan(accountOf(primary), issue(order), "req-1");

        List<Notification> sent = List.of();
        for (int attempt = 0; attempt < 50; attempt++) {
            awaitSideEffectsIdle();
            sent = notificationRepository.findAll().stream()
                    .filter(n -> n.getCustomer() != null && n.getCustomer().getId().equals(shopper.getId()))
                    .toList();
            if (!sent.isEmpty()) {
                break;
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        assertFalse(sent.isEmpty(), "the customer must be told their order is packed");

        Notification packed = sent.stream()
                .filter(n -> "Order Packed".equals(n.getTitle()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no 'Order Packed' notification was sent"));

        assertEquals("📦 Your order is packed.", packed.getMessage());

        for (Notification n : sent) {
            String text = (n.getTitle() + " " + n.getMessage()).toLowerCase();
            for (String forbidden : new String[]{
                    "ready for delivery", "picked up", "out for delivery",
                    "on the way", "delivery partner", "on its way"}) {
                assertFalse(text.contains(forbidden),
                        "a pack scan must never tell the customer \"" + forbidden + "\"; sent: " + text);
            }
        }
    }

    // --------------------------------------------------------- double scans

    @Test
    @DisplayName("a second worker cannot take an order that is already taken")
    void doubleScanIsRefusedByTheBackend() {
        String token = issue(order);
        DeliveryPartner other = newWorker("other");
        // Make the second worker otherwise authorised, so the refusal can only
        // be about the order already being taken.
        jdbc.update("INSERT INTO subzone_backup_partners (subzone_id, partner_id, priority) "
                + "VALUES (?, ?, 1)", territory.getId(), other.getId());

        assertTrue(scanService.packScan(accountOf(primary), token, "req-1").accepted());

        var second = scanService.packScan(accountOf(other), token, "req-2");

        assertFalse(second.accepted());
        assertEquals("ALREADY_SCANNED", second.outcome());
        assertTrue(second.message().contains(primary.getName()),
                "the refusal has to name who has it, so the carton can be handed over rather than "
                        + "argued about; was: " + second.message());

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(primary.getId(), reloaded.getPackedByPartner().getId(),
                "the first worker keeps the order");
    }

    @Test
    @DisplayName("two workers scanning the last carton at once: exactly one accepts")
    void concurrentDoubleScanOnlyOneAccepts() throws Exception {
        String token = issue(order);
        DeliveryPartner other = newWorker("race");
        jdbc.update("INSERT INTO subzone_backup_partners (subzone_id, partner_id, priority) "
                + "VALUES (?, ?, 1)", territory.getId(), other.getId());

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger accepted = new AtomicInteger();
        try {
            pool.submit(() -> {
                await(start);
                try {
                    if (scanService.packScan(accountOf(primary), token, null).accepted()) {
                        accepted.incrementAndGet();
                    }
                } finally {
                    done.countDown();
                }
            });
            pool.submit(() -> {
                await(start);
                try {
                    if (scanService.packScan(accountOf(other), token, null).accepted()) {
                        accepted.incrementAndGet();
                    }
                } finally {
                    done.countDown();
                }
            });
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "pack-scan race timed out");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, accepted.get(), "exactly one worker may take the carton");
        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertNotNull(reloaded.getQrTokenUsedAt());
        assertNotNull(reloaded.getPackedByPartner());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("start latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // -------------------------------------------------------- authorisation

    @Test
    @DisplayName("a worker from another territory is refused, and told whose it is")
    void wrongTerritoryIsRefused() {
        DeliveryPartner stranger = newWorker("stranger");

        var result = scanService.packScan(accountOf(stranger), issue(order), "req-1");

        assertFalse(result.accepted());
        assertEquals("NOT_AUTHORISED", result.outcome());
        assertTrue(result.message().contains(primary.getName()), result.message());

        assertEquals(OrderStatus.PACKING,
                orderRepository.findById(order.getId()).orElseThrow().getOrderStatus(),
                "a refused scan must not move the order");
    }

    @Test
    @DisplayName("a named standing backup may take the territory's orders")
    void namedBackupIsAuthorised() {
        DeliveryPartner backup = newWorker("backup");
        jdbc.update("INSERT INTO subzone_backup_partners (subzone_id, partner_id, priority) "
                + "VALUES (?, ?, 1)", territory.getId(), backup.getId());

        var result = scanService.packScan(accountOf(backup), issue(order), "req-1");

        assertTrue(result.accepted(), result.message());
        assertTrue(result.message() != null);
    }

    @Test
    @DisplayName("an administrator's assignment outranks the territory rules")
    void adminOverrideAuthorises() {
        DeliveryPartner stranger = newWorker("stranger");
        Order assigned = reload(order);
        assigned.setAssignedWorkerPartner(stranger);
        orderRepository.save(assigned);

        var result = scanService.packScan(accountOf(stranger), issue(order), "req-1");

        assertTrue(result.accepted(), result.message());
    }

    @Test
    @DisplayName("once assigned by an administrator, even the primary must stand down")
    void adminAssignmentExcludesEveryoneElse() {
        // Otherwise an override would be advisory rather than a decision, and
        // two people could both believe the order was theirs.
        DeliveryPartner stranger = newWorker("stranger");
        Order assigned = reload(order);
        assigned.setAssignedWorkerPartner(stranger);
        orderRepository.save(assigned);

        var result = scanService.packScan(accountOf(primary), issue(order), "req-1");

        assertFalse(result.accepted());
        assertTrue(result.message().contains(stranger.getName()), result.message());
    }

    @Test
    @DisplayName("a deactivated worker cannot scan at all")
    void inactiveWorkerIsRefused() {
        primary.setActive(false);
        partnerRepository.save(primary);

        var result = scanService.packScan(accountOf(primary), issue(order), "req-1");

        assertFalse(result.accepted());
        assertEquals("WORKER_INACTIVE", result.outcome());
    }

    // ------------------------------------------------------------ eligibility

    @Test
    @DisplayName("a code that is not one of ours says so plainly")
    void unknownTokenIsRefused() {
        var result = scanService.packScan(accountOf(primary), "not-a-real-token", "req-1");

        assertFalse(result.accepted());
        assertEquals("UNKNOWN_TOKEN", result.outcome());
        assertNull(result.orderNumber());
    }

    @Test
    @DisplayName("a label from a cancelled order is caught at the counter")
    void cancelledOrderIsNotScannable() {
        // The mistake this exists to catch: a carton that was packed, then
        // cancelled, then picked up anyway because the sticker was still on it.
        String token = issue(order);
        Order cancelled = reload(order);
        cancelled.setOrderStatus(OrderStatus.CANCELLED);
        orderRepository.save(cancelled);

        var result = scanService.packScan(accountOf(primary), token, "req-1");

        assertFalse(result.accepted());
        assertEquals("NOT_ELIGIBLE", result.outcome());
        assertTrue(result.message().contains("CANCELLED"), result.message());
    }

    @Test
    @DisplayName("an order already out for delivery cannot be re-packed")
    void dispatchedOrderIsNotScannable() {
        String token = issue(order);
        Order dispatched = reload(order);
        dispatched.setOrderStatus(OrderStatus.OUT_FOR_DELIVERY);
        orderRepository.save(dispatched);

        var result = scanService.packScan(accountOf(primary), token, "req-1");

        assertFalse(result.accepted());
        assertEquals("NOT_ELIGIBLE", result.outcome());
    }

    // ------------------------------------------------------------ idempotency

    @Test
    @DisplayName("a retried scan replays the first answer instead of scanning twice")
    void retryIsIdempotent() {
        // A worker on a weak connection taps, sees nothing, and taps again.
        // Without this the order gets two scan records and the customer gets
        // two notifications.
        String token = issue(order);

        var first = scanService.packScan(accountOf(primary), token, "same-request-id");
        var second = scanService.packScan(accountOf(primary), token, "same-request-id");

        assertTrue(first.accepted());
        assertTrue(second.accepted(), "the retry must not turn a success into a failure");
        assertTrue(second.replayed(), "the retry must be reported as a replay, not a new scan");
        assertEquals(first.orderNumber(), second.orderNumber());

        long accepted = scanRepository.findByOrderIdOrderByScannedAtDesc(order.getId()).stream()
                .filter(e -> "ACCEPTED".equals(e.getOutcome()))
                .count();
        assertEquals(1, accepted, "exactly one scan may be recorded for one physical scan");

        awaitSideEffectsIdle();
        long packedMessages = notificationRepository.findAll().stream()
                .filter(n -> n.getCustomer() != null && n.getCustomer().getId().equals(shopper.getId()))
                .filter(n -> "Order Packed".equals(n.getTitle()))
                .count();
        assertEquals(1, packedMessages, "the customer must be told once, not once per tap");
    }

    @Test
    @DisplayName("without a request id a scan still works - it just loses the retry protection")
    void missingRequestIdDoesNotBlockAWorker() {
        // A worker standing at a counter must never be blocked by a
        // client-side detail they cannot see or fix.
        var result = scanService.packScan(accountOf(primary), issue(order), null);
        assertTrue(result.accepted(), result.message());
    }

    // ----------------------------------------------------------- the audit

    @Test
    @DisplayName("refused scans are recorded too, with the reason the worker was shown")
    void refusalsAreAudited() {
        // The rejected scan is the more interesting record: a worker at the
        // counter being told no, and why. Storing only successes leaves the
        // system silent exactly when somebody is asking.
        DeliveryPartner stranger = newWorker("stranger");
        var result = scanService.packScan(accountOf(stranger), issue(order), "req-1");

        List<OrderScanEvent> history = scanRepository.findByOrderIdOrderByScannedAtDesc(order.getId());

        assertEquals(1, history.size(), "the refusal must be in the history");
        OrderScanEvent event = history.get(0);
        assertEquals("NOT_AUTHORISED", event.getOutcome());
        assertEquals(stranger.getName(), event.getWorkerName());
        assertEquals(PREFIX + "Z1A", event.getSubzoneCode());
        assertEquals(result.message(), event.getReason(),
                "the recorded reason must be the sentence the worker was shown, or the audit and "
                        + "their memory can disagree");
    }

    @Test
    @DisplayName("a successful scan records who, where and when")
    void successIsAudited() {
        scanService.packScan(accountOf(primary), issue(order), "req-1");

        OrderScanEvent event = scanRepository.findByOrderIdOrderByScannedAtDesc(order.getId()).get(0);

        assertEquals("PACKED", event.getAction());
        assertEquals("ACCEPTED", event.getOutcome());
        assertEquals(primary.getId(), event.getPartnerId());
        assertEquals(primary.getName(), event.getWorkerName());
        assertEquals(PREFIX + "Z1", event.getZoneCode());
        assertEquals(PREFIX + "Z1A", event.getSubzoneCode());
        assertNotNull(event.getScannedAt());
        assertFalse(event.getPerformedByAdmin());
    }

    @Test
    @DisplayName("an order with no territory is still scannable, and recorded as such")
    void anUnmappedAddressDoesNotStrandACarton() {
        // Refusing here would leave a real packed order at a real counter over
        // a hole in the map. It is allowed, and the reason says exactly that
        // so the gap is visible rather than silently normal.
        Order unmapped = newOrder(shopper, null, OrderStatus.PACKING);
        DeliveryPartner anyone = newWorker("anyone");

        var result = scanService.packScan(accountOf(anyone), issue(unmapped), "req-1");

        assertTrue(result.accepted(), result.message());
        assertNull(result.subzoneCode());
    }

    private void awaitSideEffectsIdle() {
        if (!(orderSideEffectsExecutor instanceof ThreadPoolExecutor pool)) {
            return;
        }
        // afterCommit submits asynchronously relative to the first idle sample.
        try {
            Thread.sleep(50);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        for (int i = 0; i < 100; i++) {
            if (pool.getActiveCount() == 0 && pool.getQueue().isEmpty()) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (pool.getActiveCount() == 0 && pool.getQueue().isEmpty()) {
                    return;
                }
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
