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
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.repository.AddressRepository;
import com.gpstore.repository.CartItemRepository;
import com.gpstore.repository.CartRepository;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.InventoryRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6/7/18: an actual end-to-end checkout, against a real Postgres -
 * not just the individual pieces (inventory locking, idempotency) each
 * already covered separately in ConcurrencyIntegrationTest. This proves
 * the whole placeOrder() flow together: an authoritative total computed
 * entirely server-side (PlaceOrderRequest carries no price at all - the
 * client structurally cannot supply one), a real inventory decrement,
 * cart clearing, and that replaying the same Idempotency-Key afterward
 * returns the original order instead of placing (and paying for/
 * decrementing stock on) a second one.
 */
@SpringBootTest
class CheckoutEndToEndTest {

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
    @Autowired private com.gpstore.pricing.DeliveryPricingService deliveryPricingService;

    @Value("${store.latitude}") private double storeLatitude;
    @Value("${store.longitude}") private double storeLongitude;

    @Test
    void placeOrderComputesAuthoritativeTotalDecrementsInventoryAndClearsCart() {
        Customer customer = customerRepository.findById(createCustomer()).orElseThrow();
        Address address = createAddress(customer);

        BigDecimal sellingPrice = new BigDecimal("90.00");
        int quantity = 3;
        int startingStock = 20;
        ProductVariant variant = createVariant(sellingPrice);
        Inventory inventory = new Inventory();
        inventory.setProductVariant(variant);
        inventory.setStock(startingStock);
        inventoryRepository.save(inventory);

        Long cartId = createCartWithItem(customer, variant, quantity, sellingPrice);

        PlaceOrderRequest request = new PlaceOrderRequest();
        request.setAddressId(address.getId());
        request.setPaymentMethod("COD");

        String idempotencyKey = "checkout-e2e-" + System.nanoTime();
        PlaceOrderResponse response = orderService.placeOrder(request, customer.getId(), idempotencyKey);

        assertTrue(response.isSuccess(), "A valid checkout with in-stock items and a serviceable address must succeed");
        assertNotNull(response.getOrderId());

        Order order = orderRepository.findById(response.getOrderId()).orElseThrow();

        BigDecimal subtotal = sellingPrice.multiply(BigDecimal.valueOf(quantity));

        // The delivery charge is asserted from the SAME service the checkout
        // used, not from a number pasted in here. This test is about the total
        // being computed server-side from real prices - the client never sends
        // one - and hard-coding the fee would make it fail every time the shop
        // legitimately changes its pricing, which trains whoever hits it to
        // edit the expectation rather than think about it.
        //
        // The address sits at the shop's own coordinates, so this is the
        // first distance tier with no weight surcharge and no margin (the
        // fixture variant has no cost price).
        // The delivery charge this fixture must produce, derived from the
        // configured rules rather than pasted in as a number:
        //
        //   distance - the address sits at the shop's own coordinates, so the
        //              first tier
        //   weight   - the variant is sold by the piece, so nothing derivable
        //   margin   - no cost price on the variant, so no profit to subsidise
        //              with, which is why free delivery does NOT apply here
        //              despite a Rs 270 basket. That is the whole point of
        //              pricing on margin rather than order value.
        //
        // Read from the settings so this keeps passing when the shop
        // legitimately changes its prices - a hard-coded rupee figure would
        // fail on every tariff change and train whoever hits it to edit the
        // expectation instead of thinking.
        BigDecimal expectedDeliveryFee =
                deliveryPricingService.settings().getDistanceTier1Charge();

        assertEquals(0, expectedDeliveryFee.compareTo(order.getDeliveryFee()),
                "the first distance tier, with no weight surcharge and no margin to subsidise it");
        assertFalse(Boolean.TRUE.equals(order.getFreeDeliveryApplied()),
                "a Rs 270 basket with no recorded cost price has no margin, so it must NOT get "
                        + "free delivery - order value alone never earns it");

        // The stored breakdown has to reconcile, or the admin screen is lying
        // about where the money went.
        assertEquals(0, order.getDeliveryNormalCharge().compareTo(
                        order.getDeliverySubsidy().add(order.getDeliveryFee())),
                "normal charge must equal subsidy + what the customer paid");

        BigDecimal expectedTotal = subtotal.add(expectedDeliveryFee);

        assertEquals(0, expectedTotal.compareTo(order.getTotalAmount()),
                "Total must be computed entirely server-side from the variant's real selling price "
                        + "and the delivery pricing rules - the client never sends a price at all");

        Inventory afterFirstOrder = inventoryRepository.findByProductVariantId(variant.getId()).orElseThrow();
        assertEquals(startingStock - quantity, afterFirstOrder.getStock(),
                "Inventory must be decremented by exactly the ordered quantity");

        List<CartItem> remainingItems = cartItemRepository.findByCartId(cartId);
        assertTrue(remainingItems.isEmpty(), "The cart must be cleared after a successful checkout");

        // Replay: the exact same Idempotency-Key sent again (client retry
        // after a timeout, or a double-tap on Place Order) must return the
        // SAME order, not create a second one or touch inventory again.
        PlaceOrderResponse replay = orderService.placeOrder(request, customer.getId(), idempotencyKey);
        assertEquals(response.getOrderId(), replay.getOrderId(),
                "A replayed request with the same Idempotency-Key must return the original order, not a new one");

        Inventory afterReplay = inventoryRepository.findByProductVariantId(variant.getId()).orElseThrow();
        assertEquals(startingStock - quantity, afterReplay.getStock(),
                "A replayed checkout must not decrement inventory a second time");
    }

    private Long createCustomer() {
        Customer customer = new Customer();
        customer.setFullName("Checkout Test Customer");
        customer.setEmail("checkout-e2e-" + System.nanoTime() + "@example.com");
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
        // Exactly the store's own coordinates - zero distance, guaranteed
        // to be within the serviceable radius regardless of what that
        // radius is configured to (see store.max-delivery-radius-km).
        address.setLatitude(storeLatitude);
        address.setLongitude(storeLongitude);
        address.setDefaultAddress(true);
        return addressRepository.save(address);
    }

    private ProductVariant createVariant(BigDecimal sellingPrice) {
        Category category = new Category();
        category.setName("Checkout Test Category " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        category = categoryRepository.save(category);

        Product product = new Product();
        product.setName("Checkout Test Item " + System.nanoTime());
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

    private Long createCartWithItem(Customer customer, ProductVariant variant, int quantity, BigDecimal sellingPrice) {
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

        return cart.getId();
    }
}
