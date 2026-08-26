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
import com.gpstore.enums.PaymentMethod;
import com.gpstore.enums.PaymentStatus;
import com.gpstore.exception.BadRequestException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "cashfree.app-id=test-app-id",
        "cashfree.secret-key=test-secret-key",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000"
})
class OnlinePlaceOrderTest {

    @Autowired private OrderService orderService;
    @Autowired private PaymentService paymentService;
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
    @DisplayName("placeOrder accepts ONLINE when Cashfree is configured")
    void placeOrderAcceptsOnlineWhenCashfreeIsConfigured() {
        Customer customer = customerRepository.findById(createCustomer()).orElseThrow();
        Address address = createAddress(customer);
        ProductVariant variant = createVariant(new BigDecimal("90.00"));
        Inventory inventory = new Inventory();
        inventory.setProductVariant(variant);
        inventory.setStock(10);
        inventoryRepository.save(inventory);
        createCartWithItem(customer, variant, 1, new BigDecimal("90.00"));

        PlaceOrderRequest request = new PlaceOrderRequest();
        request.setAddressId(address.getId());
        request.setPaymentMethod("ONLINE");

        PlaceOrderResponse response = orderService.placeOrder(
                request, customer.getId(), "online-e2e-" + System.nanoTime());

        assertTrue(response.isSuccess());
        Order order = orderRepository.findById(response.getOrderId()).orElseThrow();
        Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertEquals(PaymentMethod.ONLINE, payment.getPaymentMethod());
        assertEquals(PaymentStatus.PENDING, payment.getPaymentStatus());
    }

    @Test
    @DisplayName("parsePaymentMethod accepts ONLINE when Cashfree is on")
    void parseOnlineIsAllowed() {
        assertEquals(PaymentMethod.ONLINE, paymentService.parsePaymentMethod("ONLINE"));
    }

    private Long createCustomer() {
        Customer customer = new Customer();
        customer.setFullName("Online Checkout Customer");
        customer.setEmail("online-e2e-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber("9" + String.valueOf(System.nanoTime()).substring(0, 9));
        customer.setPassword("irrelevant-for-this-test");
        customer.setEnabled(true);
        customer.setActive(true);
        return customerRepository.save(customer).getId();
    }

    private Address createAddress(Customer customer) {
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
        return addressRepository.save(address);
    }

    private ProductVariant createVariant(BigDecimal sellingPrice) {
        Category category = new Category();
        category.setName("Online Test Category " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        category = categoryRepository.save(category);

        Product product = new Product();
        product.setName("Online Test Item " + System.nanoTime());
        product.setBrand("TestBrand");
        product.setActive(true);
        product.setCategory(category);
        product = productRepository.save(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setQuantity(1.0);
        variant.setUnit("pc");
        variant.setMrp(new BigDecimal("100.00"));
        variant.setSellingPrice(sellingPrice);
        variant.setAvailable(true);
        variant.setActive(true);
        return productVariantRepository.save(variant);
    }

    private void createCartWithItem(Customer customer, ProductVariant variant, int quantity, BigDecimal sellingPrice) {
        Cart cart = new Cart();
        cart.setCustomer(customer);
        cart = cartRepository.save(cart);

        CartItem item = new CartItem();
        item.setCart(cart);
        item.setProductVariant(variant);
        item.setQuantity(quantity);
        item.setPrice(sellingPrice);
        item.setTotalPrice(sellingPrice.multiply(BigDecimal.valueOf(quantity)));
        cartItemRepository.save(item);
    }
}
