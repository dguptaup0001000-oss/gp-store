package com.gpstore.concurrency;

import com.gpstore.entity.*;
import com.gpstore.enums.OrderStatus;
import com.gpstore.enums.PaymentMethod;
import com.gpstore.enums.PaymentStatus;
import com.gpstore.platform.PlatformProperties;
import com.gpstore.platform.Shop;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantScope;
import com.gpstore.repository.*;
import com.gpstore.service.DeliveryService;
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

import static com.gpstore.concurrency.ConcurrencyHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Two people moving the same delivery at the same instant.
 *
 * WHO THE TWO PEOPLE ARE. A delivery is the one row in this system that
 * several humans touch within the same minute, from different apps: the
 * shopkeeper's admin screen, the rider's phone, and - since Slice 6 - the
 * outbox worker auto-assigning a rider moments after checkout commits. They
 * do not coordinate. A customer ringing the shop to cancel while the rider
 * is standing at the gate is not an edge case, it is Tuesday.
 *
 * WHAT AN INVARIANT IS HERE. Not "one of them wins" - somebody always wins.
 * It is that the three rows describing one delivery cannot end up telling
 * three different stories: the delivery row, the order's status, and the COD
 * payment. A delivery marked CANCELLED beside an order marked DELIVERED
 * beside cash marked COLLECTED is not a race that resolved badly; it is a
 * shop whose books say money came in for goods it still has on the shelf.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "outbox.purge-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("Two people moving the same delivery at once")
class DeliveryStateUnderConcurrencyTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformProperties platform;
    @Autowired private ShopRepository shops;
    @Autowired private DeliveryService deliveryService;
    @Autowired private DeliveryRepository deliveries;
    @Autowired private DeliveryPartnerRepository partners;
    @Autowired private OrderRepository orders;
    @Autowired private OrderItemRepository orderItems;
    @Autowired private PaymentRepository payments;
    @Autowired private CustomerRepository customers;
    @Autowired private AddressRepository addresses;
    @Autowired private CategoryRepository categories;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;
    @Autowired private InventoryRepository inventories;

    private final String tag = String.valueOf(System.nanoTime());

    private long shopOne;
    private Long customerId;
    private Long addressId;
    private Long categoryId;
    private Long productId;
    private Long variantId;
    private Long orderId;
    private Long paymentId;
    private Long riderOne;
    private Long riderTwo;

    @BeforeEach
    void anOrderOnTheBenchAndTwoRiders() {
        Shop first = shops.findByCode(platform.getFirstShopCode()).orElseThrow();
        shopOne = first.getId();
        TenantContext.set(TenantScope.ofShop(shopOne));

        Customer customer = new Customer();
        customer.setFullName("Delivery race " + tag);
        customer.setEmail("drace-" + tag + "@example.test");
        customer.setMobileNumber("9" + tag.substring(0, 9));
        customer.setPassword("not-a-real-hash");
        customer.setEnabled(true);
        customer.setActive(true);
        customer = customers.save(customer);
        customerId = customer.getId();

        Address address = new Address();
        address.setCustomer(customer);
        address.setFullName(customer.getFullName());
        address.setMobileNumber(customer.getMobileNumber());
        address.setHouseNo("2");
        address.setArea("Race Area");
        address.setCity("Race City");
        address.setState("Race State");
        address.setPincode("110001");
        address.setCountry("India");
        address.setLatitude(first.getLatitude());
        address.setLongitude(first.getLongitude());
        address.setDefaultAddress(true);
        Address savedAddress = addresses.save(address);
        addressId = savedAddress.getId();

        Category category = new Category();
        category.setName("Delivery race category " + tag);
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        categoryId = categories.save(category).getId();

        Product product = new Product();
        product.setName("Delivery race product " + tag);
        product.setCategory(category);
        product.setActive(true);
        productId = products.save(product).getId();

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setQuantity(1.0);
        variant.setUnit("pc");
        variant.setMrp(new BigDecimal("100"));
        variant.setSellingPrice(new BigDecimal("90"));
        variant.setAvailable(true);
        variant.setActive(true);
        variant = variants.save(variant);
        variantId = variant.getId();

        Inventory inventory = new Inventory();
        inventory.setProductVariant(variant);
        inventory.setStock(10);
        inventories.save(inventory);

        // An order that has been paid for at the door, not yet: COD, already
        // confirmed and packed, which is the state a delivery is created in.
        Order order = new Order();
        order.setOrderNumber("DRACE-" + tag);
        order.setCustomer(customer);
        order.setAddress(savedAddress);
        order.setTotalAmount(new BigDecimal("90.00"));
        order.setOrderStatus(OrderStatus.PACKED);
        order.setPaymentStatus(PaymentStatus.COD_PENDING);
        order.setOrderDate(LocalDateTime.now());
        order.setActive(true);
        order = orders.save(order);
        orderId = order.getId();

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProductVariant(variant);
        item.setQuantity(1);
        item.setPrice(new BigDecimal("90.00"));
        item.setTotalPrice(new BigDecimal("90.00"));
        item.setActive(true);
        orderItems.save(item);

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.COD);
        payment.setPaymentStatus(PaymentStatus.COD_PENDING);
        payment.setAmount(new BigDecimal("90.00"));
        payment.setPaymentDate(LocalDateTime.now());
        payment.setActive(true);
        paymentId = payments.save(payment).getId();

        riderOne = newRider("Rider one " + tag);
        riderTwo = newRider("Rider two " + tag);
    }

    private Long newRider(String name) {
        DeliveryPartner rider = new DeliveryPartner();
        rider.setName(name);
        rider.setMobile("8" + String.valueOf(System.nanoTime()).substring(0, 9));
        rider.setVehicleType("BIKE");
        rider.setVehicleNumber("XX" + System.nanoTime() % 10000);
        rider.setAvailable(true);
        rider.setActive(true);
        return partners.save(rider).getId();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private java.util.concurrent.ExecutorService orderSideEffectsExecutor;

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        // WAIT FOR THE ORDER'S OWN AFTER-COMMIT WORK BEFORE DELETING IT.
        //
        // Collecting the cash commits, and AfterCommitExecutor then writes the
        // customer's notification on another thread. The deletes below already
        // take notifications before orders - but on a runner quick enough to
        // start tearing down while that thread is still going, the row lands
        // in the gap and the order delete fails on
        // fk6og1jgdhfyqm6mk8v6a1qxias. CI reported exactly that, from this
        // line, and it cannot happen on a machine slow enough for the thread
        // to have finished first.
        //
        // Draining is the fix rather than deleting notifications twice or
        // catching the violation: the point is that the side effect has
        // finished, not that its trace has been removed twice.
        awaitSideEffectsIdle();
        jdbc.update("DELETE FROM audit_logs WHERE entity_id IN "
                + "(SELECT id FROM deliveries WHERE order_id = ?)"
                + " AND entity_type = 'Delivery'", orderId);
        jdbc.update("DELETE FROM notifications WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM outbox_events WHERE aggregate_id = ?", orderId);
        jdbc.update("DELETE FROM deliveries WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM delivery_batches WHERE delivery_partner_id IN (?, ?)", riderOne, riderTwo);
        jdbc.update("DELETE FROM order_items WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM payments WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM invoices WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM orders WHERE id = ?", orderId);
        jdbc.update("DELETE FROM addresses WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        jdbc.update("DELETE FROM delivery_partners WHERE id IN (?, ?)", riderOne, riderTwo);
        jdbc.update("DELETE FROM inventory WHERE product_variant_id = ?", variantId);
        jdbc.update("DELETE FROM product_variants WHERE id = ?", variantId);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
    }

    // ------------------------------------------------------------ assignment

    @Test
    @DisplayName("two riders assigned at the same instant leave ONE delivery on the order")
    void oneOrderCannotEndUpWithTwoDeliveries() {
        List<Outcome<Object>> outcomes = raceAll(List.of(
                () -> inShop(() -> deliveryService.assignDelivery(orderId, riderOne)),
                () -> inShop(() -> deliveryService.assignDelivery(orderId, riderTwo))));

        long rows = jdbc.queryForObject(
                "SELECT count(*) FROM deliveries WHERE order_id = ?", Long.class, orderId);

        assertEquals(1L, rows,
                "one order is one delivery. Two rows is two riders sent to one address, two "
                        + "ETAs, two 'your order is on its way' pushes - and every later read of "
                        + "this order's delivery throws, because the code asks for one row and "
                        + "gets two." + describe(outcomes));

        // And the ordinary read path still works, which is the failure a
        // customer would actually meet: their tracking screen, not a count.
        assertDoesNotThrow(() -> inShop(() -> deliveries.findByOrderId(orderId)),
                "a duplicated delivery breaks every read of it afterwards");

        // THE LOSER MUST BE TOLD WHAT HAPPENED, in the words the method
        // already has for it. assignDelivery opens with "does this order
        // already have a delivery?" - a check that is simply not true by the
        // time the insert runs, so the refusal came from the database instead
        // and reached the shopkeeper as a generic constraint failure. Same
        // outcome for the data, wrong answer for the person: "that order is
        // already assigned" is actionable, "a data integrity violation
        // occurred" sends them to look for a bug that is not there.
        assertEquals(1, outcomes.stream().filter(Outcome::succeeded).count(),
                "exactly one assignment can win" + describe(outcomes));
        Outcome<Object> loser = outcomes.stream().filter(o -> !o.succeeded()).findFirst()
                .orElseThrow();
        assertTrue(loser.failedWith(com.gpstore.exception.BadRequestException.class),
                "the shop that lost the race must be told the order is already assigned, "
                        + "not handed a database error." + describe(outcomes));
    }

    // ------------------------------------------------- conflicting transitions

    @Test
    @DisplayName("a delivery cancelled at the door never leaves the order marked delivered")
    void thetwoHalvesOfADeliveryCannotDisagree() {
        Long deliveryId = anAssignedDeliveryAt("OUT_FOR_DELIVERY");

        List<Outcome<Object>> outcomes = raceAll(List.of(
                () -> inShop(() -> deliveryService.updateDeliveryStatus(
                        deliveryId, "DELIVERED", null, true)),
                () -> inShop(() -> deliveryService.updateDeliveryStatus(
                        deliveryId, "CANCELLED", null, true))));

        String deliveryStatus = jdbc.queryForObject(
                "SELECT delivery_status FROM deliveries WHERE id = ?", String.class, deliveryId);
        LocalDateTime deliveredAt = jdbc.queryForObject(
                "SELECT delivered_at FROM deliveries WHERE id = ?", LocalDateTime.class, deliveryId);
        String orderStatus = jdbc.queryForObject(
                "SELECT order_status FROM orders WHERE id = ?", String.class, orderId);
        String paymentStatus = jdbc.queryForObject(
                "SELECT payment_status FROM payments WHERE id = ?", String.class, paymentId);

        String story = "\n  delivery=" + deliveryStatus + " deliveredAt=" + deliveredAt
                + "\n  order=" + orderStatus + "\n  payment=" + paymentStatus
                + describe(outcomes);

        // ONE OF THEM HAS TO BE REFUSED, and this is the assertion that
        // actually proves the two are serialized rather than merely landing
        // on a tolerable answer by luck. DELIVERED and CANCELLED are both
        // terminal, so whichever move lands first makes the other illegal -
        // and the transition table can only say so if the second caller reads
        // the first caller's committed state. Two successes here means both
        // read OUT_FOR_DELIVERY and both acted on it.
        assertEquals(1, outcomes.stream().filter(Outcome::succeeded).count(),
                "a delivery cannot both arrive and be called off. The second person to press "
                        + "must be told the delivery has already moved." + story);

        if ("DELIVERED".equals(deliveryStatus)) {
            assertEquals("DELIVERED", orderStatus,
                    "a delivered delivery means a delivered order" + story);
            assertEquals("COD_RECEIVED", paymentStatus,
                    "goods handed over means the cash was taken" + story);
        } else {
            assertEquals("CANCELLED", deliveryStatus,
                    "only the two racers' targets are possible outcomes" + story);
            assertNotEquals("DELIVERED", orderStatus,
                    "A CANCELLED DELIVERY MUST NOT LEAVE THE ORDER DELIVERED. The customer's "
                            + "screen would say their goods arrived, and the shop's would agree, "
                            + "while the delivery itself says nobody ever handed anything over."
                            + story);
            assertNotEquals("COD_RECEIVED", paymentStatus,
                    "AND IT MUST NOT LEAVE THE CASH MARKED COLLECTED. This is money in the "
                            + "shop's books that is not in the shop's till." + story);
            assertNull(deliveredAt,
                    "a cancelled delivery has no moment of delivery" + story);
        }
    }

    @Test
    @DisplayName("two riders both pressing Delivered settle the cash once")
    void theCashIsTakenOnce() {
        Long deliveryId = anAssignedDeliveryAt("OUT_FOR_DELIVERY");

        List<Outcome<Object>> outcomes = raceAll(List.of(
                () -> inShop(() -> deliveryService.updateDeliveryStatus(
                        deliveryId, "DELIVERED", null, true)),
                () -> inShop(() -> deliveryService.updateDeliveryStatus(
                        deliveryId, "DELIVERED", null, true))));

        String paymentStatus = jdbc.queryForObject(
                "SELECT payment_status FROM payments WHERE id = ?", String.class, paymentId);
        assertEquals("COD_RECEIVED", paymentStatus,
                "the delivery happened, so the cash is in" + describe(outcomes));

        long moves = jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE entity_type = 'Delivery' "
                        + "AND entity_id = ? AND action = 'DELIVERY_STATUS_DELIVERED'",
                Long.class, deliveryId);
        assertEquals(1L, moves,
                "ONE DELIVERY HAPPENED, SO ONE MOVE TO DELIVERED IS RECORDED. Two entries is "
                        + "two 'your order has arrived' notifications for one doorstep, and an "
                        + "accountability trail that says the same delivery was completed twice."
                        + describe(outcomes));
    }

    // ------------------------------------------------------------- fixtures

    /** A delivery already on the road, created the way assignment creates it. */
    private Long anAssignedDeliveryAt(String status) {
        Long id = inShop(() -> deliveryService.assignDelivery(orderId, riderOne)).getDeliveryId();
        jdbc.update("UPDATE deliveries SET delivery_status = ? WHERE id = ?", status, id);
        jdbc.update("UPDATE orders SET order_status = 'OUT_FOR_DELIVERY' WHERE id = ?", orderId);
        return id;
    }

    /**
     * Every racing task carries its own scope: a tenant scope is a
     * ThreadLocal, and a worker thread that inherited nothing would read
     * across every shop and write into none.
     */
    private <T> T inShop(java.util.function.Supplier<T> work) {
        return TenantContext.runWithin(TenantScope.ofShop(shopOne), work::get);
    }

    /**
     * Blocks until the after-commit pool has nothing left to do.
     *
     * <p>Same shape as WorkerDeliveryStatusTest and WorkerPackScanTest, which
     * need it for the same reason. Checks twice with a pause between, because
     * a task can be handed from the queue to a thread between the two reads
     * and a single check would call that idle.
     */
    private void awaitSideEffectsIdle() {
        if (!(orderSideEffectsExecutor instanceof java.util.concurrent.ThreadPoolExecutor pool)) {
            return;
        }
        for (int attempt = 0; attempt < 200; attempt++) {
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
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

}
