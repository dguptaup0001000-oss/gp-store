package com.gpstore.service;

import com.gpstore.entity.Category;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Inventory;
import com.gpstore.entity.Order;
import com.gpstore.entity.OrderItem;
import com.gpstore.entity.Payment;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.enums.OrderStatus;
import com.gpstore.enums.PaymentMethod;
import com.gpstore.enums.PaymentStatus;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.InventoryRepository;
import com.gpstore.repository.OrderItemRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.PaymentRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Abandoned Cashfree (ONLINE) checkout must expire the same way UPI does:
 * payment FAILED, stock restored, order CANCELLED. Without this sweep,
 * inventory stays reserved forever when the customer closes the gateway.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "outbox.purge-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "payment.expiry-interval-ms=3600000"
})
class OnlinePaymentExpiryTest {

    private static final int STOCK_AFTER_ORDER_PLACED = 7;
    private static final int ORDERED_QUANTITY = 3;

    @Autowired private PaymentService paymentService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository productVariantRepository;

    @Test
    @DisplayName("stale ONLINE payment: FAILED, stock restored, order CANCELLED")
    void abandonedOnlineCheckoutExpiresCompletely() {
        Long variantId = createProductVariant();

        Inventory inventory = new Inventory();
        inventory.setProductVariant(productVariantRepository.findById(variantId).orElseThrow());
        inventory.setStock(STOCK_AFTER_ORDER_PLACED);
        inventoryRepository.save(inventory);

        Customer customer = new Customer();
        customer.setFullName("Online Expiry Customer");
        customer.setEmail("online-expiry-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber("8" + String.valueOf(System.nanoTime()).substring(0, 9));
        customer.setPassword("irrelevant-for-this-test");
        customer.setEnabled(true);
        customer.setActive(true);
        customer = customerRepository.save(customer);

        Order order = new Order();
        order.setOrderNumber("ONLEXP-" + System.nanoTime());
        order.setCustomer(customer);
        order.setTotalAmount(new BigDecimal("270.00"));
        order.setOrderStatus(OrderStatus.PENDING_CONFIRMATION);
        order.setOrderDate(LocalDateTime.now());
        order.setActive(true);
        order = orderRepository.save(order);

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProductVariant(productVariantRepository.findById(variantId).orElseThrow());
        item.setQuantity(ORDERED_QUANTITY);
        item.setPrice(new BigDecimal("90.00"));
        item.setTotalPrice(new BigDecimal("270.00"));
        item.setActive(true);
        orderItemRepository.save(item);

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.ONLINE);
        payment.setPaymentStatus(PaymentStatus.PENDING);
        payment.setAmount(new BigDecimal("270.00"));
        payment.setPaymentDate(LocalDateTime.now().minusDays(1));
        payment.setActive(true);
        payment = paymentRepository.save(payment);

        paymentService.expireOneStalePayment(payment.getId());

        assertEquals(PaymentStatus.FAILED,
                paymentRepository.findById(payment.getId()).orElseThrow().getPaymentStatus());
        assertEquals(OrderStatus.CANCELLED,
                orderRepository.findById(order.getId()).orElseThrow().getOrderStatus());
        assertEquals(STOCK_AFTER_ORDER_PLACED + ORDERED_QUANTITY,
                inventoryRepository.findByProductVariantId(variantId).orElseThrow().getStock());
    }

    private Long createProductVariant() {
        Category category = new Category();
        category.setName("Online Expiry Category " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        category = categoryRepository.save(category);

        Product product = new Product();
        product.setName("Online Expiry Item " + System.nanoTime());
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
        variant.setAvailable(true);
        variant.setActive(true);
        variant = productVariantRepository.save(variant);

        return variant.getId();
    }
}
