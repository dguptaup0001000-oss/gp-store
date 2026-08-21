package com.gpstore.service;

import com.gpstore.dto.request.PlaceOrderRequest;
import com.gpstore.dto.response.PlaceOrderResponse;
import com.gpstore.entity.*;
import com.gpstore.enums.PaymentStatus;
import com.gpstore.exception.ConflictException;
import com.gpstore.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TASK 8, the half that had no coverage.
 *
 * Placing an order is protected by an Idempotency-Key and a unique
 * constraint, and that is tested twice over already. CONFIRMING A PAYMENT is
 * protected differently - by a state guard under a row lock - and nothing
 * tested it.
 *
 * It matters because confirmation is the step a human does twice. Somebody
 * checks the shop's UPI app, taps Confirm, the request is slow, they tap
 * again. Money has already changed hands; what must not happen is the second
 * tap being accepted as a second payment or corrupting the order's record of
 * having been paid.
 *
 * NO WEBHOOK EXISTS. The brief asks for webhook retry, delayed webhook and
 * duplicate callback cases. This app takes direct UPI - the customer pays
 * from their own UPI app and a human confirms receipt - so there is no
 * gateway callback to retry, and no test here pretends otherwise. If a
 * gateway is ever integrated, that is where those cases belong.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class PaymentConfirmationIdempotencyTest {

    @Autowired private OrderService orderService;
    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository productVariantRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private AddressRepository addressRepository;
    @Autowired private CartRepository cartRepository;
    @Autowired private CartItemRepository cartItemRepository;

    @Value("${store.latitude}") private double storeLatitude;
    @Value("${store.longitude}") private double storeLongitude;

    @Test
    @DisplayName("Confirming a COD payment twice is refused the second time")
    void codCompletionIsNotRepeatable() {
        Long orderId = placeOrder("COD");

        paymentService.completeCodPayment(orderId);
        assertEquals(PaymentStatus.COD_RECEIVED, statusOf(orderId));

        assertThrows(ConflictException.class, () -> paymentService.completeCodPayment(orderId),
                "A second confirmation must be refused, not silently accepted as another payment");
        assertEquals(PaymentStatus.COD_RECEIVED, statusOf(orderId),
                "The refused attempt must leave the payment exactly as it was");
    }

    @Test
    @DisplayName("Confirming a UPI payment twice is refused the second time")
    void upiConfirmationIsNotRepeatable() {
        Long orderId = placeOrder("UPI");

        paymentService.confirmUpiPayment(orderId, "TXN-" + System.nanoTime());
        assertEquals(PaymentStatus.SUCCESS, statusOf(orderId));

        assertThrows(ConflictException.class,
                () -> paymentService.confirmUpiPayment(orderId, "TXN-" + System.nanoTime()),
                "An already-confirmed payment must not accept a second transaction id");
        assertEquals(PaymentStatus.SUCCESS, statusOf(orderId));
    }

    @Test
    @DisplayName("The same UPI transaction id cannot be spent on two orders")
    void aTransactionIdIsSpentOnce() {
        // Otherwise one real UPI transfer could be used to mark several
        // orders paid - the shop hands over several baskets for one payment.
        String sharedTransactionId = "TXN-SHARED-" + System.nanoTime();

        Long firstOrder = placeOrder("UPI");
        paymentService.confirmUpiPayment(firstOrder, sharedTransactionId);

        Long secondOrder = placeOrder("UPI");
        assertThrows(ConflictException.class,
                () -> paymentService.confirmUpiPayment(secondOrder, sharedTransactionId));

        assertEquals(PaymentStatus.PENDING, statusOf(secondOrder),
                "The second order must still be awaiting a real payment");
    }

    @Test
    @DisplayName("Two staff confirming the same COD at once: one applies")
    void concurrentCodConfirmationsApplyOnce() throws InterruptedException {
        // The delivery partner marks it collected while the shop owner does
        // the same from the admin screen. The payment row is locked, so the
        // loser re-reads COD_RECEIVED and fails its guard.
        Long orderId = placeOrder("COD");

        int contenders = 6;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(contenders);
        AtomicInteger succeeded = new AtomicInteger();

        for (int i = 0; i < contenders; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    paymentService.completeCodPayment(orderId);
                    succeeded.incrementAndGet();
                } catch (Exception expectedForLosers) {
                    // ConflictException once the winner has committed.
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, succeeded.get(), "Exactly one confirmation may apply");
        assertEquals(PaymentStatus.COD_RECEIVED, statusOf(orderId));
    }

    private PaymentStatus statusOf(Long orderId) {
        return paymentRepository.findByOrderId(orderId).orElseThrow().getPaymentStatus();
    }

    private Long placeOrder(String paymentMethod) {
        Customer customer = new Customer();
        customer.setFullName("Payment Idempotency Customer");
        customer.setEmail("pay-idem-" + System.nanoTime() + "@example.com");
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
        address = addressRepository.save(address);

        Category category = new Category();
        category.setName("Payment Category " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        category = categoryRepository.save(category);

        Product product = new Product();
        product.setName("Payment Item " + System.nanoTime());
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
        variant = productVariantRepository.save(variant);

        Inventory inventory = new Inventory();
        inventory.setProductVariant(variant);
        inventory.setStock(50);
        inventoryRepository.save(inventory);

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

        PlaceOrderRequest request = new PlaceOrderRequest();
        request.setAddressId(address.getId());
        request.setPaymentMethod(paymentMethod);

        PlaceOrderResponse response = orderService.placeOrder(
                request, customer.getId(), "pay-idem-" + System.nanoTime());
        assertTrue(response.isSuccess(), "Setup order must be placed successfully");
        return response.getOrderId();
    }
}
