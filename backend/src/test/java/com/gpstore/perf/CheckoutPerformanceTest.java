package com.gpstore.perf;

import com.gpstore.dto.request.PlaceOrderRequest;
import com.gpstore.entity.Address;
import com.gpstore.entity.Cart;
import com.gpstore.entity.CartItem;
import com.gpstore.entity.Category;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Inventory;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.repository.AddressRepository;
import com.gpstore.repository.CartItemRepository;
import com.gpstore.repository.CartRepository;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.InventoryRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;
import com.gpstore.service.CartService;
import com.gpstore.service.OrderService;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance regression tests for the checkout path, expressed as SQL
 * QUERY COUNTS rather than wall-clock time.
 *
 * Why counts and not milliseconds: an N+1 is invisible in a small test
 * database. Ten cart items resolve fast enough locally that nothing looks
 * wrong, so a timing assertion would pass while the defect is fully present.
 * The same code against a managed database across a network pays real
 * latency PER round trip, which is where "checkout takes 8 seconds" comes
 * from. A count is also stable enough to assert on in CI, where wall-clock
 * time is not.
 *
 * The thresholds below are deliberately "constant-ish, not proportional to
 * cart size". The specific numbers are headroom above what the code
 * currently does, so ordinary refactoring does not trip them, but a
 * reintroduced per-item query does - which is the actual thing being
 * defended.
 *
 * These measure APPLICATION+LOCAL-DB time. They are not a claim about
 * production latency; production adds per-query network cost, which is
 * precisely why reducing the count matters more than the local millisecond
 * figure.
 */
@SpringBootTest(properties = {
        // Background schedulers OFF for this class, and this is not
        // cosmetic - it is what makes the measurement valid at all.
        //
        // Hibernate's Statistics are SessionFactory-wide, not per-thread, so
        // any query a @Scheduled job issues while the measured block runs is
        // counted against it. The first version of this test reported 165
        // queries for a 10-item placeOrder; inspecting the actual SQL showed
        // 498 of the run's 647 statements belonged to the outbox worker
        // draining leftover test events on its 30-second tick. The headline
        // number was mostly background noise.
        //
        // Pushed far into the future rather than disabled outright so the
        // beans still exist exactly as in production - only their timers are
        // silenced.
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "outbox.purge-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "payment.expiry-interval-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "idempotency.cleanup-interval-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-interval-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000",
        "delivery.late-flag-interval-ms=3600000"
})
class CheckoutPerformanceTest {

    private static final int CART_SIZE = 10;

    @Autowired private OrderService orderService;
    @Autowired private CartService cartService;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private AddressRepository addressRepository;
    @Autowired private CartRepository cartRepository;
    @Autowired private CartItemRepository cartItemRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository productVariantRepository;
    @Autowired private InventoryRepository inventoryRepository;

    @Value("${store.latitude}") private double storeLatitude;
    @Value("${store.longitude}") private double storeLongitude;

    /**
     * Checkout preview over a 10-item cart.
     *
     * The defect this guards: previewCheckout loaded cart items with a plain
     * findByCartId, then walked them touching item.getProductVariant() and
     * variant.getProduct() - both LAZY. That is 1 query for the items plus up
     * to 2 more PER ITEM, so the cost grew with basket size on a screen the
     * customer stares at before paying.
     */
    @Test
    void checkoutPreviewDoesNotScaleWithCartSize() {
        Fixture fixture = newCustomerWithCart(CART_SIZE);

        // Warm up: first call in a JVM pays one-off costs (query plan
        // creation, metamodel init) that would otherwise be attributed to
        // the measured run.
        orderService.previewCheckout(fixture.customerId, fixture.addressId, null);

        QueryCounter.Result result = QueryCounter.measure(entityManagerFactory,
                () -> orderService.previewCheckout(fixture.customerId, fixture.addressId, null));

        System.out.println("[PERF] checkout-preview (" + CART_SIZE + " items): " + result);

        // Measured: 5 queries before the fetch-join fix, 3 after. 6 leaves
        // headroom for ordinary refactoring while still failing if a
        // per-item lazy load comes back (which would make this grow with
        // CART_SIZE).
        assertTrue(result.queryCount() <= 6,
                "Checkout preview should cost a small constant number of queries regardless of "
                        + "cart size - a per-item lazy load reappears here as a count that grows "
                        + "with CART_SIZE. Was: " + result);
    }

    /**
     * Place order over a 10-item cart.
     *
     * Necessarily costs more than preview - it locks each inventory row
     * individually, which is REQUIRED for correctness and must not be
     * optimised away. The threshold allows for that per-item locking while
     * still catching the avoidable work: duplicate cart reads, re-loading
     * variants already in the persistence context, and redundant saves on
     * managed entities.
     */
    @Test
    void placeOrderStaysWithinItsQueryBudget() {
        Fixture fixture = newCustomerWithCart(CART_SIZE);

        PlaceOrderRequest request = new PlaceOrderRequest();
        request.setAddressId(fixture.addressId);
        request.setPaymentMethod("COD");

        QueryCounter.Result result = QueryCounter.measure(entityManagerFactory,
                () -> orderService.placeOrder(request, fixture.customerId, UUID.randomUUID().toString()));

        System.out.println("[PERF] place-order (" + CART_SIZE + " items): " + result);

        // Per-item inventory locking is deliberate and correct, so the budget
        // scales with cart size - but only linearly in the LOCK, not in
        // three separate lazy loads per item as well.
        // Measured: 63 queries before, 33 after, for CART_SIZE=10.
        // Budget = CART_SIZE (the per-item inventory lock, which is
        // deliberate and must not be optimised away) + 30 fixed. At
        // CART_SIZE=10 that is 40, comfortably above the current 33 but well
        // below the 63 the pre-fix code needed.
        // Measured: 63 -> 33 (fetch joins, bulk cart clear, dirty checking)
        // -> 24 after OrderItem moved from IDENTITY to a sequence, which is
        // what finally let the ten order_item INSERTs batch into one round
        // trip. Budget = CART_SIZE (the per-item inventory lock, which is
        // required and must not be optimised away) + 20 fixed = 30 at
        // CART_SIZE=10, above the current 24 but well under the old 33.
        assertTrue(result.queryCount() <= CART_SIZE + 20,
                "Place order exceeded its query budget. Inventory locking is per-item by design; "
                        + "anything beyond that is avoidable work. Was: " + result);
    }

    /** Adding to an existing cart must not re-read the whole cart repeatedly. */
    @Test
    void cartAddStaysWithinItsQueryBudget() {
        Fixture fixture = newCustomerWithCart(CART_SIZE);
        Long extraVariant = createVariantWithStock();

        cartService.addToCart(fixture.customerId, extraVariant, 1);

        Long anotherVariant = createVariantWithStock();
        QueryCounter.Result result = QueryCounter.measure(entityManagerFactory,
                () -> cartService.addToCart(fixture.customerId, anotherVariant, 1));

        System.out.println("[PERF] cart-add (cart of " + CART_SIZE + "): " + result);

        // Measured: 10 queries, unchanged by this pass - cart add was
        // already reasonable. Asserted so it stays that way.
        assertTrue(result.queryCount() <= 14,
                "Adding one item should not cost a query per existing cart item. Was: " + result);
    }


    /**
     * Order status update.
     *
     * The defect this guards: notifyOrderStatusChange writes a notification
     * row and then calls FirebaseMessaging.send() - a blocking network round
     * trip to Google - and it did so INSIDE the transaction, while this
     * order's row lock was held. That put an external service's latency in
     * both the customer's response time and the critical section every other
     * request for this order queues behind.
     *
     * A query count cannot see a network call, so this asserts the thing a
     * count CAN see: the notification INSERT no longer happens in the
     * measured (transactional) window, because the whole notification step
     * moved after commit and off the request thread.
     */
    @Test
    void orderStatusUpdateDoesNotDoNotificationWorkInline() {
        Fixture fixture = newCustomerWithCart(1);
        PlaceOrderRequest request = new PlaceOrderRequest();
        request.setAddressId(fixture.addressId);
        request.setPaymentMethod("COD");
        Long orderId = orderService.placeOrder(request, fixture.customerId, UUID.randomUUID().toString()).getOrderId();

        QueryCounter.Result result = QueryCounter.measure(entityManagerFactory,
                () -> orderService.updateOrderStatus(orderId, com.gpstore.enums.OrderStatus.PACKING));

        System.out.println("[PERF] order-status-update: " + result);

        assertTrue(result.queryCount() <= 10,
                "Status update should be order + payment state and little else - notification "
                        + "work (and its FCM network call) belongs after commit. Was: " + result);
    }

    /**
     * Cancellation.
     *
     * Must still do its correctness work synchronously: lock the order, move
     * the payment, restore inventory exactly once, write the audit row, and
     * record the durable outbox event for invoice cancellation. Only the FCM
     * push moved off the request path.
     *
     * The budget therefore allows the real work while still catching a
     * regression that puts notification or invoice work back inline.
     */
    @Test
    void cancellationDoesNotDoNotificationWorkInline() {
        Fixture fixture = newCustomerWithCart(3);
        PlaceOrderRequest request = new PlaceOrderRequest();
        request.setAddressId(fixture.addressId);
        request.setPaymentMethod("COD");
        Long orderId = orderService.placeOrder(request, fixture.customerId, UUID.randomUUID().toString()).getOrderId();

        QueryCounter.Result result = QueryCounter.measure(entityManagerFactory,
                () -> orderService.cancelOrder(orderId, fixture.customerId, false));

        System.out.println("[PERF] order-cancel (3 items): " + result);

        // Measured: 25 queries before the detail fetch-join, lower after.
        // The floor here is real work that must stay synchronous - order
        // lock, payment lock + transition, one lock and one update per item
        // for inventory restore, the order update, the audit row, the
        // durable outbox row, and the response read.
        assertTrue(result.queryCount() <= 22,
                "Cancellation should be lock + payment + inventory restore + audit + outbox row. "
                        + "Was: " + result);
    }


    /**
     * Cart quantity update and removal - the two most-tapped mutations in the
     * app. Both go through CartService's fetchWithItems, which uses the
     * fetch-joined cart query, so their cost must not scale with basket size.
     */
    @Test
    void cartUpdateAndRemoveDoNotScaleWithCartSize() {
        Fixture fixture = newCustomerWithCart(CART_SIZE);
        var items = cartItemRepository.findByCartId(fixture.cartId);
        var firstItem = items.get(0);
        var secondItem = items.get(1);

        QueryCounter.Result update = QueryCounter.measure(entityManagerFactory,
                () -> cartService.updateItemQuantity(fixture.customerId, firstItem.getId(), 3));
        System.out.println("[PERF] cart-update (cart of " + CART_SIZE + "): " + update);

        QueryCounter.Result remove = QueryCounter.measure(entityManagerFactory,
                () -> cartService.removeItem(fixture.customerId, secondItem.getId()));
        System.out.println("[PERF] cart-remove (cart of " + CART_SIZE + "): " + remove);

        assertTrue(update.queryCount() <= 14,
                "Cart quantity update should not cost a query per existing item. Was: " + update);
        assertTrue(remove.queryCount() <= 14,
                "Cart removal should not cost a query per existing item. Was: " + remove);
    }

    /**
     * Order detail - the endpoint behind "track my order".
     *
     * Guards the fetch join added for OrderDetailResponse: without it this
     * lazily loaded the items collection, then each item's variant and
     * product, then the address, so its cost grew with the number of lines on
     * the order.
     */
    @Test
    void orderDetailDoesNotScaleWithOrderSize() {
        Fixture fixture = newCustomerWithCart(CART_SIZE);
        PlaceOrderRequest request = new PlaceOrderRequest();
        request.setAddressId(fixture.addressId);
        request.setPaymentMethod("COD");
        Long orderId = orderService.placeOrder(request, fixture.customerId, UUID.randomUUID().toString()).getOrderId();

        // Warm up so one-off query-plan costs are not attributed here.
        orderService.getOwnedOrderDetail(orderId, fixture.customerId, false);

        QueryCounter.Result result = QueryCounter.measure(entityManagerFactory,
                () -> orderService.getOwnedOrderDetail(orderId, fixture.customerId, false));

        System.out.println("[PERF] order-detail (" + CART_SIZE + " items): " + result);

        assertTrue(result.queryCount() <= 6,
                "Order detail should be a small constant number of queries regardless of how "
                        + "many lines the order has. Was: " + result);
    }

    // ---------- fixtures ----------

    private record Fixture(Long customerId, Long addressId, Long cartId) {
    }

    private Fixture newCustomerWithCart(int items) {
        Customer customer = new Customer();
        customer.setFullName("Perf Test Customer");
        customer.setEmail("perf-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber("9" + String.valueOf(System.nanoTime()).substring(0, 9));
        customer.setPassword("irrelevant-for-this-test");
        customer.setEnabled(true);
        customer.setActive(true);
        customer = customerRepository.save(customer);

        Address address = new Address();
        address.setCustomer(customer);
        address.setFullName(customer.getFullName());
        address.setMobileNumber(customer.getMobileNumber());
        address.setHouseNo("1");
        address.setArea("Perf Area");
        address.setCity("Perf City");
        address.setState("Perf State");
        address.setPincode("110001");
        address.setCountry("India");
        address.setLatitude(storeLatitude);
        address.setLongitude(storeLongitude);
        address.setDefaultAddress(true);
        address = addressRepository.save(address);

        Cart cart = new Cart();
        cart.setCustomer(customer);
        cart = cartRepository.save(cart);

        List<CartItem> cartItems = new ArrayList<>();
        for (int i = 0; i < items; i++) {
            CartItem item = new CartItem();
            item.setCart(cart);
            ProductVariant variant = productVariantRepository.findById(createVariantWithStock()).orElseThrow();
            item.setProductVariant(variant);
            item.setQuantity(1);
            // Real cart items always carry these (CartService sets them on
            // add); leaving them null made the fixture NPE inside the cart
            // total recalculation rather than exercising anything real.
            item.setPrice(variant.getSellingPrice());
            item.setTotalPrice(variant.getSellingPrice());
            cartItems.add(item);
        }
        cartItemRepository.saveAll(cartItems);

        return new Fixture(customer.getId(), address.getId(), cart.getId());
    }

    private Long createVariantWithStock() {
        Category category = new Category();
        category.setName("Perf Category " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        category = categoryRepository.save(category);

        Product product = new Product();
        product.setName("Perf Item " + System.nanoTime());
        product.setBrand("TestBrand");
        product.setActive(true);
        product.setCategory(category);
        product = productRepository.save(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setQuantity(1.0);
        variant.setUnit("pc");
        variant.setMrp(new BigDecimal("100"));
        variant.setSellingPrice(new BigDecimal("90"));
        variant.setCostPrice(new BigDecimal("60"));
        variant.setAvailable(true);
        variant.setActive(true);
        variant = productVariantRepository.save(variant);

        Inventory inventory = new Inventory();
        inventory.setProductVariant(variant);
        inventory.setStock(500);
        inventoryRepository.save(inventory);

        return variant.getId();
    }
}
