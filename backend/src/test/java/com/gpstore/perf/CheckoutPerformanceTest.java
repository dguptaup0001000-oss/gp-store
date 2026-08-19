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
        assertTrue(result.queryCount() <= CART_SIZE + 30,
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
