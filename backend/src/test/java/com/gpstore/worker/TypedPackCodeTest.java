package com.gpstore.worker;

import com.gpstore.entity.*;
import com.gpstore.enums.OrderStatus;
import com.gpstore.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A worker whose camera will not scan can still claim the order.
 *
 * THE GAP THIS FILLS. The scan screen's own error text used to end with "ask
 * an administrator to record the order for you" - which is a worker standing
 * at a bench, holding a carton, unable to do their job because a lens is
 * cracked or the storeroom is dark.
 *
 * THE TRAP IT AVOIDS, which is the more important half. The obvious fix is to
 * let them type the ORDER NUMBER. Order numbers are sequential
 * (GP20260902000116), printed on the invoice, and visible to every customer
 * who ever received one - so accepting one would let any worker claim any
 * order without ever touching it, and the label would stop meaning "I have
 * this carton in my hands". These tests pin that refusal as hard as they pin
 * the feature.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("A pack code can be typed when the camera will not scan")
class TypedPackCodeTest {

    private static final String MARKER = "TYPED_CODE_TEST";

    @Autowired private WorkerScanService scanService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderScanEventRepository scanRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private DeliveryPartnerRepository partnerRepository;

    private Customer shopper;
    private DeliveryPartner worker;
    private Order order;

    @BeforeEach
    void setUp() {
        shopper = new Customer();
        shopper.setFullName(MARKER + " Shopper");
        shopper.setEmail("typedcode-" + System.nanoTime() + "@example.com");
        shopper.setMobileNumber("9" + String.format("%09d", System.nanoTime() % 1_000_000_000L));
        shopper.setPassword("irrelevant-for-this-test");
        shopper.setEnabled(true);
        shopper.setActive(true);
        shopper = customerRepository.save(shopper);

        worker = new DeliveryPartner();
        worker.setName(MARKER + " Worker");
        worker.setMobile("9" + String.format("%09d", (System.nanoTime() + 7) % 1_000_000_000L));
        worker.setActive(true);
        worker.setAvailable(true);
        worker = partnerRepository.save(worker);

        order = newOrder();
    }

    // ------------------------------------------------------------- the code

    @Test
    @DisplayName("the label carries a code a person can actually read out")
    void codeIsReadable() {
        scanService.issueToken(order.getId());
        String code = reload().getPackCode();

        assertNotNull(code, "A label with no typeable code is a label that fails "
                + "the moment the camera does.");
        assertEquals(8, code.length());

        // The characters people misread off a smudged sticker in bad light.
        // A code containing them produces failed attempts, which this design
        // then counts against the worker as though they were guessing.
        for (char confusable : new char[]{'O', '0', 'I', '1', 'L'}) {
            assertFalse(code.indexOf(confusable) >= 0,
                    "The alphabet must exclude " + confusable + "; it was in " + code);
        }
    }

    @Test
    @DisplayName("two labels never carry the same code")
    void codesAreDistinct() {
        scanService.issueToken(order.getId());
        String first = reload().getPackCode();

        Order second = newOrder();
        scanService.issueToken(second.getId());
        String other = orderRepository.findById(second.getId()).orElseThrow().getPackCode();

        assertNotEquals(first, other);
    }

    // ------------------------------------------------------------- claiming

    @Test
    @DisplayName("typing the code claims the order, exactly as scanning would")
    void typingTheCodeClaimsIt() {
        scanService.issueToken(order.getId());
        String code = reload().getPackCode();

        var result = scanService.packScan(worker.getId(), code, null);

        assertTrue(result.accepted(), "Rejected with: " + result.message());
        Order after = reload();
        assertEquals(OrderStatus.PACKED, after.getOrderStatus());
        assertNotNull(after.getQrTokenUsedAt(),
                "A typed claim consumes the same single-use label a scan would.");
    }

    @Test
    @DisplayName("the code survives being typed the way people actually type it")
    void normalisationHandlesRealTyping() {
        scanService.issueToken(order.getId());
        String code = reload().getPackCode();

        // Lowercase, spaced and dashed - all of which a phone keyboard and a
        // worker reading four-and-four will produce.
        String messy = code.substring(0, 4).toLowerCase() + " - " + code.substring(4).toLowerCase();

        var result = scanService.packScan(worker.getId(), messy, null);
        assertTrue(result.accepted(), "Rejected with: " + result.message());
    }

    @Test
    @DisplayName("a code already used is refused, whichever way it was presented")
    void aUsedCodeIsRefused() {
        scanService.issueToken(order.getId());
        String code = reload().getPackCode();

        assertTrue(scanService.packScan(worker.getId(), code, null).accepted());

        var second = scanService.packScan(worker.getId(), code, null);
        assertFalse(second.accepted());
        assertEquals("ALREADY_SCANNED", second.outcome());
    }

    // ----------------------------------------------------- the refusal that matters

    @Test
    @DisplayName("the ORDER NUMBER is not a credential and must never claim an order")
    void orderNumberIsRefused() {
        scanService.issueToken(order.getId());
        String orderNumber = reload().getOrderNumber();

        // THE ASSERTION THIS WHOLE FILE EXISTS FOR. Order numbers are
        // sequential and printed on the customer's invoice. If this ever
        // passes, any worker can claim any order in the shop from their sofa.
        var result = scanService.packScan(worker.getId(), orderNumber, null);

        assertFalse(result.accepted(),
                "An order number claimed an order. The label no longer proves possession.");
        assertEquals("UNKNOWN_TOKEN", result.outcome());
        assertNull(reload().getQrTokenUsedAt());
        assertNotEquals(OrderStatus.PACKED, reload().getOrderStatus());
    }

    @Test
    @DisplayName("guessing is cut off long before the space could be walked")
    void repeatedWrongCodesAreStopped() {
        // Ten wrong guesses is far above honest fumbling and far below what
        // brute force would need against 30^8.
        for (int i = 0; i < 10; i++) {
            var attempt = scanService.packScan(worker.getId(), "ZZZZ" + String.format("%04d", i), null);
            assertFalse(attempt.accepted());
            assertEquals("UNKNOWN_TOKEN", attempt.outcome());
        }

        var stopped = scanService.packScan(worker.getId(), "ZZZZ9999", null);
        assertFalse(stopped.accepted());
        assertEquals("TOO_MANY_WRONG_CODES", stopped.outcome(),
                "A keyboard can offer anything a camera cannot; without a ceiling "
                        + "the typed path would be the weakest way into an order.");
    }

    @Test
    @DisplayName("every wrong code is recorded, so a run of them is visible afterwards")
    void wrongCodesAreAudited() {
        long before = scanRepository.countRejectionsSince(
                worker.getId(), "UNKNOWN_TOKEN", LocalDateTime.now().minusHours(1));

        scanService.packScan(worker.getId(), "ZZZZ7777", null);

        long after = scanRepository.countRejectionsSince(
                worker.getId(), "UNKNOWN_TOKEN", LocalDateTime.now().minusHours(1));
        assertEquals(before + 1, after,
                "A refusal nobody can find afterwards is a refusal that teaches nobody anything.");
    }

    @Test
    @DisplayName("reprinting a label replaces the old code as well as the old QR")
    void reprintingReplacesTheCode() {
        scanService.issueToken(order.getId());
        String firstCode = reload().getPackCode();

        scanService.issueToken(order.getId());
        String secondCode = reload().getPackCode();

        assertNotEquals(firstCode, secondCode);

        // The old sticker must stop working, or a carton retrieved from the
        // bin could still claim the order.
        var stale = scanService.packScan(worker.getId(), firstCode, null);
        assertFalse(stale.accepted());
        assertEquals("UNKNOWN_TOKEN", stale.outcome());
    }

    // ------------------------------------------------------------ fixtures

    private Order reload() {
        return orderRepository.findById(order.getId()).orElseThrow();
    }

    private Order newOrder() {
        Order o = new Order();
        o.setOrderNumber("TYPEDCODE-" + System.nanoTime());
        o.setCustomer(shopper);
        o.setTotalAmount(new BigDecimal("250.00"));
        o.setOrderStatus(OrderStatus.PACKING);
        o.setOrderDate(LocalDateTime.now());
        o.setActive(true);
        return orderRepository.save(o);
    }
}
