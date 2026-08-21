package com.gpstore.service;

import com.gpstore.dto.request.PlaceOrderRequest;
import com.gpstore.dto.response.PlaceOrderResponse;
import com.gpstore.entity.*;
import com.gpstore.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE LAST UNIT ON THE SHELF. Stock is 1, two customers press Place Order at
 * the same instant, and exactly one of them may get it.
 *
 * WHY THIS EXISTS ALONGSIDE ConcurrencyIntegrationTest, which already proves
 * overselling is impossible: that test calls
 * InventoryService.decrementForPurchase, an extracted helper. Real checkout
 * does NOT go through it - OrderService.placeOrder locks and decrements
 * inline, with its own copy of the read-check-write sequence. So the helper
 * was proven safe while the path an actual customer takes was only believed
 * safe by reading it. Two implementations of the same invariant need two
 * proofs, and this is the one that runs when somebody buys something.
 *
 * Stock of 1 is the sharpest case: with 10 units and 20 buyers a broken lock
 * still usually lands near zero and looks plausible. With one unit, either
 * the invariant holds exactly or the shop has sold something it does not
 * have.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class LastUnitCheckoutRaceTest {

    @Autowired private OrderService orderService;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository productVariantRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private AddressRepository addressRepository;
    @Autowired private CartRepository cartRepository;
    @Autowired private CartItemRepository cartItemRepository;
    @Autowired private OrderRepository orderRepository;

    @Value("${store.latitude}") private double storeLatitude;
    @Value("${store.longitude}") private double storeLongitude;

    @Test
    @DisplayName("Stock 1, two simultaneous buyers: exactly one order, stock lands at 0")
    void twoBuyersOneUnit() throws InterruptedException {
        ProductVariant variant = createVariant();
        Inventory inventory = new Inventory();
        inventory.setProductVariant(variant);
        inventory.setStock(1);
        inventory = inventoryRepository.save(inventory);

        // Two genuinely separate customers, each with their own cart holding
        // the same last unit - which is exactly how this happens in a shop.
        Customer first = newCustomerWithCart(variant);
        Customer second = newCustomerWithCart(variant);

        List<PlaceOrderResponse> outcomes = raceCheckout(List.of(first, second));

        long succeeded = outcomes.stream().filter(r -> r != null && r.isSuccess()).count();
        assertEquals(1, succeeded, "Exactly one of two buyers may get the last unit");

        Inventory after = inventoryRepository.findById(inventory.getId()).orElseThrow();
        assertEquals(0, after.getStock(),
                "Stock must land at exactly 0 - never -1, which is the shop owing someone an item it does not have");

        long ordersForVariant = orderRepository.findAll().stream()
                .filter(o -> o.getCustomer() != null
                        && (o.getCustomer().getId().equals(first.getId())
                            || o.getCustomer().getId().equals(second.getId())))
                .count();
        assertEquals(1, ordersForVariant, "The losing buyer must not end up with an order at all");
    }

    @Test
    @DisplayName("Stock 1, five simultaneous buyers: still exactly one")
    void fiveBuyersOneUnit() throws InterruptedException {
        ProductVariant variant = createVariant();
        Inventory inventory = new Inventory();
        inventory.setProductVariant(variant);
        inventory.setStock(1);
        inventory = inventoryRepository.save(inventory);

        List<Customer> buyers = List.of(
                newCustomerWithCart(variant), newCustomerWithCart(variant),
                newCustomerWithCart(variant), newCustomerWithCart(variant),
                newCustomerWithCart(variant));

        List<PlaceOrderResponse> outcomes = raceCheckout(buyers);

        assertEquals(1, outcomes.stream().filter(r -> r != null && r.isSuccess()).count(),
                "One unit means one winner, however many people are pressing the button");

        Inventory after = inventoryRepository.findById(inventory.getId()).orElseThrow();
        assertEquals(0, after.getStock(), "Stock must never go below zero");
    }

    @Test
    @DisplayName("Stock 3, ten buyers wanting 1 each: three orders, no overselling")
    void partialAvailabilityIsExact() {
        // Not just "never negative" - the shop must also sell everything it
        // does have. A lock that is too coarse could reject buyers while
        // stock remains.
        ProductVariant variant = createVariant();
        Inventory inventory = new Inventory();
        inventory.setProductVariant(variant);
        inventory.setStock(3);
        inventory = inventoryRepository.save(inventory);

        List<Customer> buyers = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) buyers.add(newCustomerWithCart(variant));

        List<PlaceOrderResponse> outcomes;
        try {
            outcomes = raceCheckout(buyers);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }

        assertEquals(3, outcomes.stream().filter(r -> r != null && r.isSuccess()).count(),
                "All three units must sell - rejecting a buyer while stock remains loses a real sale");

        Inventory after = inventoryRepository.findById(inventory.getId()).orElseThrow();
        assertEquals(0, after.getStock());
    }

    /** Releases every checkout at the same instant, so they genuinely contend. */
    private List<PlaceOrderResponse> raceCheckout(List<Customer> buyers) throws InterruptedException {
        int n = buyers.size();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        List<PlaceOrderResponse> outcomes = new CopyOnWriteArrayList<>();
        AtomicInteger rejected = new AtomicInteger();

        for (Customer buyer : buyers) {
            pool.submit(() -> {
                try {
                    PlaceOrderRequest request = new PlaceOrderRequest();
                    request.setAddressId(addressIdFor(buyer));
                    request.setPaymentMethod("COD");
                    ready.countDown();
                    go.await();
                    outcomes.add(orderService.placeOrder(
                            request, buyer.getId(), "race-" + buyer.getId() + "-" + System.nanoTime()));
                } catch (Exception expectedForTheLosers) {
                    // ConflictException for out of stock, or a lock timeout.
                    // Either way this buyer did not get the item, which is
                    // what the stock assertion actually checks.
                    rejected.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "Every checkout attempt should settle within 60s");
        pool.shutdown();
        return outcomes;
    }

    private Long addressIdFor(Customer customer) {
        return addressRepository.findByCustomerId(customer.getId()).get(0).getId();
    }

    private Customer newCustomerWithCart(ProductVariant variant) {
        Customer customer = new Customer();
        customer.setFullName("Race Buyer");
        customer.setEmail("race-" + System.nanoTime() + "@example.com");
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
        address.setArea("Test Area");
        address.setCity("Test City");
        address.setState("Test State");
        address.setPincode("110001");
        address.setCountry("India");
        address.setLatitude(storeLatitude);
        address.setLongitude(storeLongitude);
        address.setDefaultAddress(true);
        addressRepository.save(address);

        Cart cart = new Cart();
        cart.setCustomer(customer);
        cart = cartRepository.save(cart);

        CartItem item = new CartItem();
        item.setCart(cart);
        item.setProductVariant(variant);
        item.setQuantity(1);
        item.setPrice(variant.getSellingPrice());
        item.setTotalPrice(variant.getSellingPrice());
        cartItemRepository.save(item);

        return customer;
    }

    private ProductVariant createVariant() {
        Category category = new Category();
        category.setName("Race Category " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        category = categoryRepository.save(category);

        Product product = new Product();
        product.setName("Last Unit Item " + System.nanoTime());
        product.setBrand("TestBrand");
        product.setActive(true);
        product.setCategory(category);
        product = productRepository.save(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setQuantity(1.0);
        variant.setUnit("pc");
        variant.setMrp(new BigDecimal("100.00"));
        variant.setSellingPrice(new BigDecimal("90.00"));
        variant.setAvailable(true);
        variant.setActive(true);
        return productVariantRepository.save(variant);
    }
}
