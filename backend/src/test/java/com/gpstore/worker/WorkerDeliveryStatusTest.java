package com.gpstore.worker;

import com.gpstore.entity.Address;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Delivery;
import com.gpstore.entity.DeliveryBatch;
import com.gpstore.entity.DeliveryPartner;
import com.gpstore.entity.Order;
import com.gpstore.entity.Payment;
import com.gpstore.entity.Role;
import com.gpstore.enums.DeliveryStatus;
import com.gpstore.enums.OrderStatus;
import com.gpstore.enums.PaymentMethod;
import com.gpstore.enums.PaymentStatus;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.support.DispatchSafeTeardown;
import com.gpstore.repository.AddressRepository;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.DeliveryBatchRepository;
import com.gpstore.repository.DeliveryPartnerRepository;
import com.gpstore.repository.DeliveryRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.PaymentRepository;
import com.gpstore.service.DeliveryPartnerService;
import com.gpstore.service.DeliveryService;
import com.gpstore.service.PaymentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The status change, end to end, against a real delivery row.
 *
 * The sibling of DeliveryStatusTransitionsTest, which tests the rule in
 * isolation. This one tests that the rule is actually CONSULTED - which is a
 * different property, and the one that was missing. The transition table could
 * be perfect and the bug would remain if nothing called it.
 *
 * WHAT WAS BROKEN. DeliveryService.updateDeliveryStatus took a String straight
 * off a @RequestParam and wrote it to the row. A worker could send DELIVERED
 * for an order still on the packing bench: deliveredAt stamped, the customer
 * told it had arrived, and the COD payment closed for cash nobody collected.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class WorkerDeliveryStatusTest {

    private static final String PREFIX = "WD-";
    private static final String MARKER = "WORKER_STATUS_TEST";

    @Autowired private DeliveryService deliveryService;
    @Autowired private DeliveryPartnerService partnerService;
    @Autowired private PaymentService paymentService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private AddressRepository addressRepository;
    @Autowired private DeliveryPartnerRepository partnerRepository;
    @Autowired private DeliveryRepository deliveryRepository;
    @Autowired private DeliveryBatchRepository batchRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExecutorService orderSideEffectsExecutor;

    private DeliveryPartner worker;
    private DeliveryPartner otherWorker;
    private Delivery delivery;

    @BeforeEach
    void setUp() {
        cleanUp();
        worker = newWorker("mine");
        otherWorker = newWorker("theirs");
        delivery = newDelivery(worker, DeliveryStatus.ASSIGNED);
    }

    /**
     * Teardown has to win a race against a writer it cannot see.
     *
     * Fourteen classes in this suite each cache their own Spring context, and
     * they all run in one JVM against one database. This class silences its
     * OWN schedulers with the properties above, but the other contexts keep a
     * live dispatcher running the whole time - and until the UPDATE below
     * lands, the fixture partners here are available=true, which makes them
     * real auto-assignment candidates for it.
     *
     * When it takes one, DeliveryService.assignDelivery writes a delivery row
     * into a WD- batch and queues a "New Delivery Assigned" push to
     * partner.getAccount() - a WORKER_STATUS_TEST customer. Both land on
     * ANOTHER context's orderSideEffectsExecutor, so awaitSideEffectsIdle()
     * below cannot observe them: it can only see this context's pool. The row
     * appears between two of the DELETEs and the teardown dies on a foreign
     * key, naming this test for an assignment it never made.
     *
     * Two things close it. Retiring the partners is now the FIRST statement
     * rather than the last, so no new assignment can select them. That still
     * leaves whatever was already in flight, so the deletes get a few passes:
     * an assignment already committed lands within milliseconds, and by the
     * second pass there is no available partner left to start another.
     */
    /**
     * Retire the fixture partners, then delete, retrying the dispatch race away.
     *
     * The mechanism and the reasons live in DispatchSafeTeardown - this class
     * was one of the three that each rediscovered the same race and patched it
     * differently. FAIL rather than TOLERATE: nothing here exercises real
     * dispatch, so a delete that never succeeds is a genuine ordering bug and
     * should say so.
     */
    @AfterEach
    void cleanUp() {
        DispatchSafeTeardown.sweep(this::retirePartners, this::deleteFixtures,
                DispatchSafeTeardown.WhenStuck.FAIL);
    }

    private void retirePartners() {
        jdbc.update("UPDATE delivery_partners SET available = false, active = false WHERE name LIKE ?",
                PREFIX + "%");
    }

    private void deleteFixtures() {
        // NOTIFICATIONS FIRST, and they are not optional here. Moving a
        // delivery to DELIVERED notifies the customer, so this test creates
        // notification rows as a side effect of the thing it is testing - and
        // they hold a foreign key to the order. Deleting the order first fails
        // with a constraint violation, which is what happened the first time
        // this file ran.
        //
        // Wait for AfterCommitExecutor too: a DELIVERED status change queues
        // the insert asynchronously. CI failed when that insert landed after
        // the first DELETE FROM notifications and before DELETE FROM orders.
        awaitSideEffectsIdle();
        jdbc.update("DELETE FROM notifications WHERE order_id IN "
                + "(SELECT id FROM orders WHERE order_number LIKE ?)", PREFIX + "%");
        jdbc.update("DELETE FROM notifications WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE full_name LIKE ?)", MARKER + "%");
        jdbc.update("DELETE FROM outbox_events WHERE aggregate_id IN "
                + "(SELECT id FROM orders WHERE order_number LIKE ?)", PREFIX + "%");
        jdbc.update("DELETE FROM deliveries WHERE order_id IN "
                + "(SELECT id FROM orders WHERE order_number LIKE ?)", PREFIX + "%");
        jdbc.update("DELETE FROM payments WHERE order_id IN "
                + "(SELECT id FROM orders WHERE order_number LIKE ?)", PREFIX + "%");
        jdbc.update("DELETE FROM notifications WHERE order_id IN "
                + "(SELECT id FROM orders WHERE order_number LIKE ?)", PREFIX + "%");
        jdbc.update("DELETE FROM orders WHERE order_number LIKE ?", PREFIX + "%");
        jdbc.update("UPDATE addresses SET subzone_id = NULL WHERE full_name LIKE ?", MARKER + "%");
        jdbc.update("DELETE FROM addresses WHERE full_name LIKE ?", MARKER + "%");
        jdbc.update("DELETE FROM deliveries WHERE batch_id IN "
                + "(SELECT id FROM delivery_batches WHERE delivery_partner_id IN "
                + " (SELECT id FROM delivery_partners WHERE name LIKE ?))", PREFIX + "%");
        jdbc.update("DELETE FROM delivery_batches WHERE delivery_partner_id IN "
                + "(SELECT id FROM delivery_partners WHERE name LIKE ?)", PREFIX + "%");
        jdbc.update("DELETE FROM delivery_partners WHERE name LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM delivery_partners WHERE account_customer_id IN "
                + "(SELECT id FROM customers WHERE full_name LIKE ?)", MARKER + "%");
        // Last thing before the customers go: the partner-assignment push is
        // written to partner.getAccount(), so it can appear long after the
        // sweep at the top of this method.
        jdbc.update("DELETE FROM notifications WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE full_name LIKE ?)", MARKER + "%");
        jdbc.update("DELETE FROM customers WHERE full_name LIKE ?", MARKER + "%");
    }

    // ------------------------------------------------------ the actual bug

    @Test
    @DisplayName("a worker cannot mark an order delivered off the packing bench")
    void deliveredIsRefusedFromAssigned() {
        ConflictException refused = assertThrows(ConflictException.class,
                () -> deliveryService.updateDeliveryStatus(
                        delivery.getId(), "DELIVERED", sessionOf(worker), false),
                "ASSIGNED -> DELIVERED must be refused. Allowing it stamps a delivery time, tells the "
                        + "customer their order arrived, and settles a COD payment for money nobody took.");

        assertTrue(refused.getMessage().contains("PACKED"),
                "The refusal must name what IS allowed, or a worker is stuck with no idea what to press.");

        Delivery reloaded = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertEquals("ASSIGNED", reloaded.getDeliveryStatus(), "Nothing may have been written.");
        assertNull(reloaded.getDeliveredAt(), "deliveredAt must not have been stamped.");
    }

    @Test
    @DisplayName("nonsense is not a delivery status")
    void freeTextIsRefused() {
        for (String rubbish : new String[]{"BANANA", "", "  ", "delivered!"}) {
            assertThrows(BadRequestException.class,
                    () -> deliveryService.updateDeliveryStatus(
                            delivery.getId(), rubbish, sessionOf(worker), false),
                    "\"" + rubbish + "\" was accepted as a delivery status.");
        }

        assertEquals("ASSIGNED",
                deliveryRepository.findById(delivery.getId()).orElseThrow().getDeliveryStatus());
    }

    @Test
    @DisplayName("the ordinary day works one step at a time")
    void theHappyPathIsAllowed() {
        move("PACKED");
        move("PICKED_UP");
        move("OUT_FOR_DELIVERY");
        move("DELIVERED");

        Delivery finished = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertEquals("DELIVERED", finished.getDeliveryStatus());
        assertNotNull(finished.getDeliveredAt(), "A real delivery stamps its time.");
        assertEquals(OrderStatus.DELIVERED,
                orderRepository.findById(finished.getOrder().getId()).orElseThrow().getOrderStatus(),
                "The order must follow the delivery to DELIVERED.");
    }

    @Test
    @DisplayName("a double-tapped button does not stamp the delivery twice")
    void repeatingAStatusIsANoOp() {
        move("PACKED");
        move("PICKED_UP");
        move("OUT_FOR_DELIVERY");
        move("DELIVERED");

        LocalDateTime firstStamp =
                deliveryRepository.findById(delivery.getId()).orElseThrow().getDeliveredAt();

        // The retry on a bad connection. Must not be an error screen, and must
        // not re-run the side effects behind it.
        assertDoesNotThrow(() -> move("DELIVERED"));

        assertEquals(firstStamp,
                deliveryRepository.findById(delivery.getId()).orElseThrow().getDeliveredAt(),
                "deliveredAt was re-stamped by a repeat request. The early return is what stops that.");
    }

    @Test
    @DisplayName("a finished delivery cannot be moved again")
    void terminalIsTerminal() {
        move("PACKED");
        move("PICKED_UP");
        move("OUT_FOR_DELIVERY");
        move("DELIVERED");

        assertThrows(ConflictException.class,
                () -> deliveryService.updateDeliveryStatus(
                        delivery.getId(), "CANCELLED", sessionOf(worker), false),
                "A delivered order cannot be un-delivered - the goods changed hands.");
    }

    // ------------------------------------------------------- authorisation

    @Test
    @DisplayName("a worker cannot touch a delivery that is not theirs")
    void anotherWorkersDeliveryIsNotFound() {
        assertThrows(ResourceNotFoundException.class,
                () -> deliveryService.updateDeliveryStatus(
                        delivery.getId(), "PACKED", sessionOf(otherWorker), false),
                "Authorisation must be checked before anything else, and a stranger's delivery must "
                        + "read as not-found rather than as forbidden.");

        assertEquals("ASSIGNED",
                deliveryRepository.findById(delivery.getId()).orElseThrow().getDeliveryStatus());
    }

    @Test
    @DisplayName("a worker cannot read a delivery that is not theirs")
    void anotherWorkersDeliveryIsHiddenOnRead() {
        assertTrue(deliveryService.getDeliveryById(delivery.getId(), sessionOf(worker), false).isPresent(),
                "the assigned worker must still be able to open their own delivery");
        assertTrue(deliveryService.getDeliveryById(delivery.getId(), sessionOf(otherWorker), false).isEmpty(),
                "a stranger's delivery must read as missing, not as someone else's row");
        assertTrue(deliveryService.getDeliveryById(delivery.getId(), sessionOf(otherWorker), true).isPresent(),
                "an admin can still open any delivery");
    }

    @Test
    @DisplayName("a worker cannot mark COD collected on someone else's order")
    void anotherWorkerCannotCompleteCod() {
        Payment payment = new Payment();
        payment.setOrder(delivery.getOrder());
        payment.setPaymentMethod(PaymentMethod.COD);
        payment.setPaymentStatus(PaymentStatus.COD_PENDING);
        payment.setAmount(delivery.getOrder().getTotalAmount());
        payment.setActive(true);
        paymentRepository.save(payment);

        assertThrows(ResourceNotFoundException.class,
                () -> paymentService.completeCodPayment(
                        delivery.getOrder().getId(), sessionOf(otherWorker), false),
                "COD collection must use the same assigned-partner check as delivery status");

        assertEquals(PaymentStatus.COD_PENDING,
                paymentRepository.findByOrderId(delivery.getOrder().getId()).orElseThrow().getPaymentStatus());

        paymentService.completeCodPayment(delivery.getOrder().getId(), sessionOf(worker), false);
        assertEquals(PaymentStatus.COD_RECEIVED,
                paymentRepository.findByOrderId(delivery.getOrder().getId()).orElseThrow().getPaymentStatus());
    }

    @Test
    @DisplayName("marking a COD order delivered settles the cash automatically")
    void deliveringACodOrderSettlesIt() {
        Payment payment = new Payment();
        payment.setOrder(delivery.getOrder());
        payment.setPaymentMethod(PaymentMethod.COD);
        payment.setPaymentStatus(PaymentStatus.COD_PENDING);
        payment.setAmount(delivery.getOrder().getTotalAmount());
        payment.setActive(true);
        paymentRepository.save(payment);

        // WHY THIS EXISTS. Walking a delivery to DELIVERED is supposed to
        // close the COD payment with it, and nothing tested that it does -
        // the neighbouring tests all cover who may do it, not that it
        // happens.
        //
        // It is guarded by PaymentMethod.COD.name().equals(...), which looks
        // like the always-false String-versus-enum comparison that made
        // WorkerOrderView tell riders to collect zero. It is NOT: this call
        // returns a PaymentResponse DTO whose fields really are Strings, so
        // the comparison is String to String and correct. This test is what
        // keeps it that way - if that DTO ever exposes the enum instead, the
        // guard goes silently false and only this assertion notices.
        // The real path a rider walks, one step at a time - the state machine
        // refuses ASSIGNED straight to OUT_FOR_DELIVERY, correctly.
        for (String step : new String[]{"PACKED", "PICKED_UP", "OUT_FOR_DELIVERY", "DELIVERED"}) {
            deliveryService.updateDeliveryStatus(delivery.getId(), step, sessionOf(worker), false);
        }

        assertEquals(PaymentStatus.COD_RECEIVED,
                paymentRepository.findByOrderId(delivery.getOrder().getId()).orElseThrow().getPaymentStatus(),
                "a delivered cash order was left owing the shop its own money");
    }

    @Test
    @DisplayName("authorisation is checked before the transition rule")
    void ownershipOutranksTheStateMachine() {
        // A stranger sending an ILLEGAL transition must be told not-found, not
        // told which transitions would have been legal - the refusal message
        // names the delivery's current state, which is information about
        // somebody else's delivery.
        assertThrows(ResourceNotFoundException.class,
                () -> deliveryService.updateDeliveryStatus(
                        delivery.getId(), "DELIVERED", sessionOf(otherWorker), false));
    }

    // ------------------------------------------------------------ location

    @Test
    @DisplayName("a position that is not a coordinate is refused")
    void impossibleCoordinatesAreRejected() {
        Long account = sessionOf(worker);

        assertThrows(BadRequestException.class,
                () -> partnerService.updateMyLocation(account, Double.NaN, 83.94, null),
                "NaN gets past @DecimalMin and @DecimalMax - both comparisons are false for it - and "
                        + "then poisons every distance calculation that touches this partner.");

        assertThrows(BadRequestException.class,
                () -> partnerService.updateMyLocation(account, 0.0, 0.0, null),
                "(0, 0) is what a phone reports before it has a fix. It is a real place in the Gulf of "
                        + "Guinea and will be drawn on the map as confidently as any other position.");

        assertNull(partnerRepository.findById(worker.getId()).orElseThrow().getCurrentLatitude(),
                "None of those may have been stored.");
    }

    @Test
    @DisplayName("a fix too vague to be useful is refused rather than drawn")
    void wildlyInaccurateFixesAreRejected() {
        assertThrows(BadRequestException.class,
                () -> partnerService.updateMyLocation(sessionOf(worker), 27.162, 83.940, 5000.0),
                "A 5 km accuracy radius is a cell-tower guess. Rendered as a pin it looks exactly as "
                        + "confident as a real fix.");
    }

    @Test
    @DisplayName("a real position is stored with the server's own timestamp")
    void goodFixesAreStored() {
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        partnerService.updateMyLocation(sessionOf(worker), 27.162, 83.940, 12.0);

        DeliveryPartner stored = partnerRepository.findById(worker.getId()).orElseThrow();
        assertEquals(27.162, stored.getCurrentLatitude(), 0.000001);
        assertEquals(83.940, stored.getCurrentLongitude(), 0.000001);
        assertNotNull(stored.getLocationUpdatedAt());
        assertTrue(stored.getLocationUpdatedAt().isAfter(before),
                "The timestamp must be the server's. A phone with a wrong clock would otherwise make a "
                        + "stale position look fresh - and that timestamp is the only thing telling an "
                        + "administrator whether the pin means anything.");
    }

    @Test
    @DisplayName("a worker can only ever move their own position")
    void locationIsResolvedFromTheCallerNotTheBody() {
        // There is no partner id in the request at all, by design. This is the
        // assertion that it stays that way: updating as one worker must never
        // touch another's row.
        partnerService.updateMyLocation(sessionOf(worker), 27.162, 83.940, 10.0);

        assertNull(partnerRepository.findById(otherWorker.getId()).orElseThrow().getCurrentLatitude(),
                "Another worker's position was written.");
    }

    // ------------------------------------------------------------ fixtures

    private void move(String status) {
        deliveryService.updateDeliveryStatus(delivery.getId(), status, sessionOf(worker), false);
    }

    /**
     * The id a worker's own session carries - the roster row, not a customer
     * account. Worker credentials moved onto the roster row, so there is no
     * account link to translate through any more.
     */
    private Long sessionOf(DeliveryPartner partner) {
        return partner.getId();
    }

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

    private Delivery newDelivery(DeliveryPartner partner, DeliveryStatus status) {
        Customer shopper = newCustomer(MARKER + "-shopper", Role.CUSTOMER);

        Address address = new Address();
        address.setFullName(MARKER + "-address");
        address.setMobileNumber("9100000000");
        address.setHouseNo("1");
        address.setArea("Test");
        address.setCity("Malhia");
        address.setPincode("274401");
        address.setLatitude(27.162);
        address.setLongitude(83.940);
        address.setCustomer(shopper);
        address = addressRepository.save(address);

        Order order = new Order();
        order.setOrderNumber(PREFIX + System.nanoTime());
        order.setCustomer(shopper);
        order.setAddress(address);
        // CONFIRMED, not PENDING_CONFIRMATION: this test is about the delivery
        // status machine, and a pending order would drag payment advancement
        // into it.
        order.setOrderStatus(OrderStatus.CONFIRMED);
        order.setTotalAmount(new BigDecimal("250.00"));
        order.setOrderDate(LocalDateTime.now());
        order.setActive(true);
        order = orderRepository.save(order);

        DeliveryBatch batch = new DeliveryBatch();
        batch.setDeliveryPartner(partner);
        batch.setCreatedAt(LocalDateTime.now());
        batch.setActive(true);
        batch = batchRepository.save(batch);

        Delivery d = new Delivery();
        d.setOrder(order);
        d.setBatch(batch);
        d.setDeliveryStatus(status.name());
        d.setAssignedAt(LocalDateTime.now());
        d.setActive(true);
        return deliveryRepository.save(d);
    }

    private void awaitSideEffectsIdle() {
        if (!(orderSideEffectsExecutor instanceof ThreadPoolExecutor pool)) {
            return;
        }
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
