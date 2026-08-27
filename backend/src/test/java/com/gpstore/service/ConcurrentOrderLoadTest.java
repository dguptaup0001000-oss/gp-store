package com.gpstore.service;

import com.gpstore.dto.request.PlaceOrderRequest;
import com.gpstore.dto.response.PlaceOrderResponse;
import com.gpstore.entity.Address;
import com.gpstore.entity.Cart;
import com.gpstore.entity.CartItem;
import com.gpstore.entity.Category;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Inventory;
import com.gpstore.entity.Order;
import com.gpstore.entity.Payment;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.repository.AddressRepository;
import com.gpstore.repository.CartItemRepository;
import com.gpstore.repository.CartRepository;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.InventoryRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.PaymentRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 10 / 25 / 50 / 100 concurrent COD checkouts. No real money.
 *
 * Production-like pool is overridden here so this class measures ORDER
 * INTEGRITY under contention (lost/duplicate/negative stock), not the
 * surefire 5-connection cap that exists to keep the rest of the suite
 * under Postgres max_connections.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=20",
        "spring.datasource.hikari.minimum-idle=4",
        "spring.datasource.hikari.connection-timeout=60000",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class ConcurrentOrderLoadTest {

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
    @Autowired private PaymentRepository paymentRepository;

    @Value("${store.latitude}") private double storeLatitude;
    @Value("${store.longitude}") private double storeLongitude;

    @Test
    @DisplayName("10 concurrent COD checkouts: no lost, duplicate, or negative stock")
    void tenConcurrentOrders() throws InterruptedException {
        runWave(10);
    }

    @Test
    @DisplayName("25 concurrent COD checkouts: no lost, duplicate, or negative stock")
    void twentyFiveConcurrentOrders() throws InterruptedException {
        runWave(25);
    }

    @Test
    @DisplayName("50 concurrent COD checkouts: no lost, duplicate, or negative stock")
    void fiftyConcurrentOrders() throws InterruptedException {
        runWave(50);
    }

    @Test
    @DisplayName("100 concurrent COD checkouts: no lost, duplicate, or negative stock")
    void oneHundredConcurrentOrders() throws InterruptedException {
        runWave(100);
    }

    private void runWave(int n) throws InterruptedException {
        ProductVariant variant = createVariant();
        Inventory inventory = new Inventory();
        inventory.setProductVariant(variant);
        inventory.setStock(n + 50);
        inventory = inventoryRepository.save(inventory);
        int startingStock = inventory.getStock();

        List<Customer> buyers = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            buyers.add(newCustomerWithCart(variant));
        }

        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        List<PlaceOrderResponse> ok = new CopyOnWriteArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        AtomicInteger rejected = new AtomicInteger();
        long startedNanos = System.nanoTime();

        for (Customer buyer : buyers) {
            pool.submit(() -> {
                try {
                    PlaceOrderRequest request = new PlaceOrderRequest();
                    request.setAddressId(addressRepository.findByCustomerId(buyer.getId()).get(0).getId());
                    request.setPaymentMethod("COD");
                    ready.countDown();
                    go.await();
                    PlaceOrderResponse response = orderService.placeOrder(
                            request, buyer.getId(), "load-" + n + "-" + buyer.getId() + "-" + UUID.randomUUID());
                    if (response != null && response.isSuccess()) {
                        ok.add(response);
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (Exception e) {
                    failures.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(30, TimeUnit.SECONDS), "buyers failed to arm");
        go.countDown();
        assertTrue(done.await(120, TimeUnit.SECONDS), n + " checkouts did not finish in 120s");
        pool.shutdown();
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;

        assertTrue(failures.isEmpty(),
                n + " concurrent checkouts threw: " + failures.stream().map(Throwable::toString).limit(5).toList());
        assertEquals(0, rejected.get(), "every buyer with stock must succeed");
        assertEquals(n, ok.size(), "lost orders: expected " + n + " succeeded, got " + ok.size());

        Set<Long> orderIds = new HashSet<>();
        for (PlaceOrderResponse response : ok) {
            assertTrue(orderIds.add(response.getOrderId()), "duplicate order id " + response.getOrderId());
        }
        assertEquals(n, orderIds.size());

        List<Long> buyerIds = buyers.stream().map(Customer::getId).toList();
        List<Order> stored = orderRepository.findAll().stream()
                .filter(o -> o.getCustomer() != null && buyerIds.contains(o.getCustomer().getId()))
                .toList();
        assertEquals(n, stored.size(), "database order count must match successful checkouts");

        int payments = 0;
        int mismatches = 0;
        for (Order order : stored) {
            var row = paymentRepository.findByOrderId(order.getId());
            if (row.isEmpty()) {
                mismatches++;
                continue;
            }
            payments++;
            Payment payment = row.get();
            if (payment.getAmount() == null
                    || payment.getAmount().compareTo(order.getTotalAmount()) != 0) {
                mismatches++;
            }
        }
        assertEquals(n, payments, "each order must have exactly one payment row");
        assertEquals(0, mismatches, "payment/order mismatches");

        Inventory after = inventoryRepository.findById(inventory.getId()).orElseThrow();
        assertTrue(after.getStock() >= 0, "stock must never go negative");
        assertEquals(startingStock - n, after.getStock(),
                "stock must drop by exactly the number of successful orders");

        System.out.println("CONCURRENT_ORDER_WAVE n=" + n
                + " elapsed_ms=" + elapsedMs
                + " success=" + ok.size()
                + " duplicates=0 lost=0 negative_stock=0 payment_mismatch=0");
    }

    private Customer newCustomerWithCart(ProductVariant variant) {
        Customer customer = new Customer();
        customer.setFullName("Load Buyer");
        customer.setEmail("load-" + System.nanoTime() + "-" + UUID.randomUUID() + "@example.com");
        customer.setMobileNumber("9" + String.valueOf(Math.abs(System.nanoTime())).substring(0, 9));
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
        category.setName("Load Category " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        category = categoryRepository.save(category);

        Product product = new Product();
        product.setName("Load Item " + System.nanoTime());
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
