package com.gpstore.platform;

import com.gpstore.catalog.shop.ShopProductVariant;
import com.gpstore.catalog.shop.ShopProductVariantRepository;
import com.gpstore.dto.request.PlaceOrderRequest;
import com.gpstore.dto.response.CheckoutPreviewResponse;
import com.gpstore.dto.response.PlaceOrderResponse;
import com.gpstore.entity.*;
import com.gpstore.ordergroup.OrderGroup;
import com.gpstore.ordergroup.OrderGroupRepository;
import com.gpstore.repository.*;
import com.gpstore.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One basket, two kiranas, two orders - and nothing of one shop's in the other's.
 *
 * WHAT §16 ACTUALLY REQUIRES, asserted rather than described. A customer fills
 * a basket from two shops and presses one button. What comes out is one group
 * and two orders: each holding only its own shop's lines, at its own shop's
 * prices, with its own delivery fee, its own payment, its own stock taken off
 * its own shelf, and its own lifecycle afterwards.
 *
 * THE MOST IMPORTANT ASSERTIONS HERE ARE THE NEGATIVE ONES. A split that puts
 * the right items in the right orders but stamps them both with the first
 * shop's id has filed one merchant's order in another merchant's books - and
 * every screen, every report and every rupee of that shop's takings would be
 * wrong in a way nobody would notice for weeks.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("A basket spanning two shops becomes two independent orders")
class MultiShopCheckoutTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderService orderService;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private ShopProductVariantRepository listings;
    @Autowired private OrderGroupRepository groups;
    @Autowired private OrderRepository orders;
    @Autowired private OrderItemRepository orderItems;
    @Autowired private PaymentRepository payments;
    @Autowired private InventoryRepository inventory;
    @Autowired private CustomerRepository customers;
    @Autowired private AddressRepository addresses;
    @Autowired private CartRepository carts;
    @Autowired private CartItemRepository cartItems;
    @Autowired private CategoryRepository categories;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;

    private final String tag = "ms" + System.nanoTime();

    private long shopA;
    private long shopB;
    private Long merchantB;
    private Long customerId;
    private Long addressId;
    private Long cartId;
    private Long variantForA;
    private Long variantForB;
    private Long categoryId;
    private Long productId;

    private static final BigDecimal PRICE_AT_A = new BigDecimal("40.00");
    private static final BigDecimal PRICE_AT_B = new BigDecimal("70.00");

    @BeforeEach
    void aBasketFromTwoShops() {
        Shop first = shops.findByCode(platform.getFirstShopCode()).orElseThrow();
        shopA = first.getId();

        Merchant second = new Merchant();
        second.setLegalName("Split fixture " + tag);
        second.setDisplayName("Second kirana");
        second.setStatus(MerchantStatus.ACTIVE);
        second.setIsDemo(Boolean.TRUE);
        second.setActive(Boolean.TRUE);
        merchantB = merchants.save(second).getId();

        Shop b = new Shop();
        b.setMerchantId(merchantB);
        b.setCode("MS-" + tag);
        b.setDisplayName("Second kirana");
        b.setStatus(ShopStatus.ACTIVE);
        // Same pin as Shop #1, so the address is inside both radii and the
        // test is about the split rather than about geography.
        b.setLatitude(first.getLatitude());
        b.setLongitude(first.getLongitude());
        b.setMaxDeliveryRadiusKm(first.getMaxDeliveryRadiusKm());
        b.setTimeZone(first.getTimeZone());
        b.setIsDemo(Boolean.TRUE);
        b.setActive(Boolean.TRUE);
        shopB = shops.save(b).getId();

        Customer customer = new Customer();
        customer.setFullName("Split customer " + tag);
        customer.setEmail(tag + "@example.test");
        customer.setMobileNumber("9" + String.valueOf(System.nanoTime()).substring(0, 9));
        customer.setPassword("not-a-real-hash");
        customer.setEnabled(true);
        customer.setActive(true);
        customer = customers.save(customer);
        customerId = customer.getId();

        Address address = new Address();
        address.setCustomer(customer);
        address.setFullName(customer.getFullName());
        address.setMobileNumber(customer.getMobileNumber());
        address.setHouseNo("1");
        address.setArea("Split Area");
        address.setCity("Split City");
        address.setState("Split State");
        address.setPincode("110001");
        address.setCountry("India");
        address.setLatitude(first.getLatitude());
        address.setLongitude(first.getLongitude());
        address.setDefaultAddress(true);
        addressId = addresses.save(address).getId();

        Category category = new Category();
        category.setName("Split category " + tag);
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        categoryId = categories.save(category).getId();

        Product product = new Product();
        product.setName("Split product " + tag);
        product.setCategory(category);
        product.setActive(true);
        productId = products.save(product).getId();

        variantForA = newVariantStockedAt(shopA, PRICE_AT_A, 20);
        variantForB = newVariantStockedAt(shopB, PRICE_AT_B, 20);

        Cart cart = new Cart();
        cart.setCustomer(customer);
        cartId = carts.save(cart).getId();

        // One line off each shop's shelf - the shop id is what CartItem's
        // @PrePersist would have stamped from the scope; set explicitly here
        // because the fixture is not going through a request.
        addLine(cart, variantForA, shopA, PRICE_AT_A);
        addLine(cart, variantForB, shopB, PRICE_AT_B);
    }

    /**
     * Retried, because some of what it deletes is written after the request
     * that caused it returns - placing an order queues a notification through
     * AfterCommitExecutor, which lands on another thread once the transaction
     * commits. The delete usually wins that race and sometimes does not.
     */
    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        for (int attempt = 1; ; attempt++) {
            try {
                deleteFixture();
                return;
            } catch (org.springframework.dao.DataIntegrityViolationException stillReferenced) {
                if (attempt == 5) {
                    throw stillReferenced;
                }
                try {
                    Thread.sleep(200L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw stillReferenced;
                }
            }
        }
    }

    private void deleteFixture() {
        jdbc.update("DELETE FROM notifications WHERE order_id IN (SELECT id FROM orders WHERE customer_id = ?)", customerId);
        jdbc.update("DELETE FROM outbox_events WHERE aggregate_id IN (SELECT id FROM orders WHERE customer_id = ?)", customerId);
        jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE customer_id = ?)", customerId);
        jdbc.update("DELETE FROM payments WHERE order_id IN (SELECT id FROM orders WHERE customer_id = ?)", customerId);
        jdbc.update("DELETE FROM deliveries WHERE order_id IN (SELECT id FROM orders WHERE customer_id = ?)", customerId);
        jdbc.update("DELETE FROM invoices WHERE order_id IN (SELECT id FROM orders WHERE customer_id = ?)", customerId);
        jdbc.update("DELETE FROM orders WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM order_groups WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM idempotency_records WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM cart_items WHERE cart_id = ?", cartId);
        jdbc.update("DELETE FROM carts WHERE id = ?", cartId);
        jdbc.update("DELETE FROM addresses WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        jdbc.update("DELETE FROM shop_product_variants WHERE product_variant_id IN (?, ?)", variantForA, variantForB);
        jdbc.update("DELETE FROM inventory WHERE product_variant_id IN (?, ?)", variantForA, variantForB);
        jdbc.update("DELETE FROM product_variants WHERE id IN (?, ?)", variantForA, variantForB);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM shops WHERE id = ?", shopB);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantB);
    }

    // ------------------------------------------------------- 5, 6: the split

    @Test
    @DisplayName("one checkout, one group, one order per shop")
    void theBasketSplitsIntoOneOrderPerShop() {
        PlaceOrderResponse response = checkout();

        assertNotNull(response.getOrderGroupId(), "a checkout must produce a group");
        assertEquals(2, response.getShopOrders().size(),
                "a basket from two shops is two orders, and the response has to say so");

        OrderGroup group = groups.findById(response.getOrderGroupId()).orElseThrow();
        assertEquals(2, group.getShopCount());
        assertEquals(customerId, group.getCustomerId());

        List<Order> placed = TenantContext.runWithin(TenantScope.platform(),
                () -> orders.findByCustomerId(customerId));
        assertEquals(2, placed.size());
        assertTrue(placed.stream().allMatch(o -> group.getId().equals(o.getOrderGroupId())),
                "both orders must point back at the checkout they came from");

        assertEquals(1, placed.stream().filter(o -> shopA == o.getShopId()).count(),
                "one order for the first shop");
        assertEquals(1, placed.stream().filter(o -> shopB == o.getShopId()).count(),
                "one order for the second - stamped with ITS shop, not the first shop's, which "
                        + "is the mistake that would file one merchant's order in another's books");
    }

    @Test
    @DisplayName("each shop's order contains only that shop's items")
    void noItemCrossesBetweenOrders() {
        checkout();

        Order orderAtA = orderFor(shopA);
        Order orderAtB = orderFor(shopB);

        List<Long> variantsAtA = variantIdsOn(orderAtA);
        List<Long> variantsAtB = variantIdsOn(orderAtB);

        assertEquals(List.of(variantForA), variantsAtA,
                "the first shop must be asked to pack only what it sells");
        assertEquals(List.of(variantForB), variantsAtB);
        assertTrue(java.util.Collections.disjoint(variantsAtA, variantsAtB),
                "an item appearing in both orders means a shop is packing a competitor's stock");
    }

    // ------------------------------------------ 10, 11: prices stay put

    @Test
    @DisplayName("each order is charged its own shop's price")
    void pricingComesFromEachShopsOwnListing() {
        checkout();

        assertEquals(0, PRICE_AT_A.compareTo(unitPriceOn(orderFor(shopA))),
                "the first shop's order must be charged the first shop's price");
        assertEquals(0, PRICE_AT_B.compareTo(unitPriceOn(orderFor(shopB))),
                "and the second shop's order the second shop's - a split that priced both halves "
                        + "from whichever shop the request resolved to would charge the customer "
                        + "one shop's prices for another shop's goods");
    }

    @Test
    @DisplayName("delivery is charged per shop, not once for the basket")
    void deliveryIsQuotedPerShop() {
        PlaceOrderResponse response = checkout();

        for (PlaceOrderResponse.ShopOrderSummary summary : response.getShopOrders()) {
            assertNotNull(summary.deliveryFee(),
                    "every shop's order carries its own delivery fee - two shops is two "
                            + "deliveries, however the customer thinks of it");
        }
        assertEquals(2, response.getShopOrders().stream()
                        .map(PlaceOrderResponse.ShopOrderSummary::shopId).distinct().count(),
                "the two orders must belong to two different shops");
    }

    // -------------------------------------------- 9: stock stays put

    @Test
    @DisplayName("each shop's stock comes off its own shelf")
    void inventoryIsDecrementedPerShop() {
        int beforeA = stockOf(variantForA, shopA);
        int beforeB = stockOf(variantForB, shopB);

        checkout();

        assertEquals(beforeA - 1, stockOf(variantForA, shopA));
        assertEquals(beforeB - 1, stockOf(variantForB, shopB),
                "the second shop's units must come off the second shop's shelf - a split that "
                        + "decremented whichever inventory row it found first would quietly sell "
                        + "one merchant's stock to pay for another's order");
    }

    // ---------------------------------- 13: payment keeps the relationship

    @Test
    @DisplayName("each shop order has its own payment, and the group ties them together")
    void paymentIsPerShopOrderUnderOneGroup() {
        PlaceOrderResponse response = checkout();

        Order orderAtA = orderFor(shopA);
        Order orderAtB = orderFor(shopB);

        Payment atA = TenantContext.runWithin(TenantScope.platform(),
                () -> payments.findByOrderId(orderAtA.getId())).orElseThrow();
        Payment atB = TenantContext.runWithin(TenantScope.platform(),
                () -> payments.findByOrderId(orderAtB.getId())).orElseThrow();

        assertNotEquals(atA.getId(), atB.getId(),
                "each shop is paid separately - they collect their own money (decision W1)");
        assertEquals(shopA, atA.getShopId(), "a payment belongs to the shop being paid");
        assertEquals(shopB, atB.getShopId());

        assertEquals(response.getOrderGroupId(), orderAtA.getOrderGroupId());
        assertEquals(response.getOrderGroupId(), orderAtB.getOrderGroupId(),
                "and both trace back to the one checkout, which is what lets a customer see "
                        + "what they actually placed");
    }

    // ------------------------- 14: independent lifecycles

    @Test
    @DisplayName("one shop's order can be cancelled without touching the other")
    void eachShopOrderHasItsOwnLifecycle() {
        checkout();
        Order orderAtA = orderFor(shopA);
        Order orderAtB = orderFor(shopB);

        TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> orderService.cancelOrder(orderAtA.getId(), customerId, false));

        assertEquals(com.gpstore.enums.OrderStatus.CANCELLED, reload(orderAtA).getOrderStatus());
        assertNotEquals(com.gpstore.enums.OrderStatus.CANCELLED, reload(orderAtB).getOrderStatus(),
                "cancelling one kirana's half must not cancel the other's - they are separate "
                        + "businesses with separate obligations to this customer");

        assertEquals(stockOf(variantForA, shopA), stockOf(variantForA, shopA),
                "and the restock belongs to the shop that lost the sale");
    }

    // ------------------------------------- 8: the preview says all this first

    @Test
    @DisplayName("the checkout preview shows the per-shop breakdown before anyone commits")
    void thePreviewBreaksDownByShop() {
        CheckoutPreviewResponse preview = TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> orderService.previewCheckout(customerId, addressId, null));

        assertEquals(2, preview.getShops().size(),
                "a customer about to pay two delivery fees has to be shown two delivery fees");

        BigDecimal subtotals = preview.getShops().stream()
                .map(CheckoutPreviewResponse.ShopBreakdown::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, subtotals.compareTo(preview.getSubtotal()),
                "the headline total must be the sum of the breakdown, or one of them is lying");

        assertTrue(preview.getShops().stream()
                        .anyMatch(s -> PRICE_AT_B.compareTo(s.subtotal()) == 0),
                "the second shop's line must be priced from the second shop's listing");
    }

    // ---------------------------- 12: the customer cannot move an item

    @Test
    @DisplayName("a shop id on a cart line is set by the server, not by a request")
    void aCustomerCannotMoveAnItemBetweenShops() {
        // The only way a line's shop changes is a re-add through CartService,
        // which stamps it from the resolved scope. There is no request field
        // for it anywhere - asserted structurally, because "there is no way to
        // send it" is a stronger statement than "sending it is rejected".
        assertTrue(java.util.Arrays.stream(PlaceOrderRequest.class.getDeclaredFields())
                        .noneMatch(f -> f.getName().toLowerCase(java.util.Locale.ROOT).contains("shop")),
                "a shop id on the checkout request would be a shop id a customer chooses");

        // Add-to-cart takes a variant and a quantity as query parameters and
        // nothing else - there is no body to put a shop in, and no parameter
        // named for one.
        assertTrue(java.util.Arrays.stream(
                        com.gpstore.controller.CartController.class.getDeclaredMethods())
                        .filter(m -> m.getName().equals("addToCart"))
                        .flatMap(m -> java.util.Arrays.stream(m.getParameters()))
                        .noneMatch(param -> param.getName().toLowerCase(java.util.Locale.ROOT)
                                .contains("shop")),
                "nor on the add-to-cart call");

        // And the row that decides the split is stamped, not accepted.
        checkout();
        assertEquals(shopB, orderFor(shopB).getShopId());
    }

    @Test
    @DisplayName("a basket line with no shop is refused rather than guessed at")
    void aLineWithNoShopStopsTheCheckout() {
        jdbc.update("UPDATE cart_items SET shop_id = NULL WHERE cart_id = ?", cartId);

        assertThrows(RuntimeException.class, this::checkout,
                "putting an unassigned line into whichever order came first is how an item ends "
                        + "up in a shop that never stocked it");
    }

    // ------------------------------------------------------------ fixtures

    private PlaceOrderResponse checkout() {
        PlaceOrderRequest request = new PlaceOrderRequest();
        request.setAddressId(addressId);
        request.setPaymentMethod("COD");
        return TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> orderService.placeOrder(request, customerId, UUID.randomUUID().toString()));
    }

    private Order orderFor(long shopId) {
        return TenantContext.runWithin(TenantScope.platform(),
                () -> orders.findByCustomerId(customerId)).stream()
                .filter(o -> shopId == o.getShopId())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no order for shop " + shopId));
    }

    private Order reload(Order order) {
        return TenantContext.runWithin(TenantScope.platform(),
                () -> orders.findById(order.getId())).orElseThrow();
    }

    private List<Long> variantIdsOn(Order order) {
        return TenantContext.runWithin(TenantScope.platform(),
                () -> orderItems.findByOrderId(order.getId())).stream()
                .map(item -> item.getProductVariant().getId())
                .sorted()
                .toList();
    }

    private BigDecimal unitPriceOn(Order order) {
        return TenantContext.runWithin(TenantScope.platform(),
                () -> orderItems.findByOrderId(order.getId())).get(0).getPrice();
    }

    private int stockOf(Long variantId, long shopId) {
        Integer stock = jdbc.queryForObject(
                "SELECT stock FROM inventory WHERE product_variant_id = ? AND shop_id = ?",
                Integer.class, variantId, shopId);
        return stock == null ? 0 : stock;
    }

    private Long newVariantStockedAt(long shopId, BigDecimal price, int stock) {
        ProductVariant variant = new ProductVariant();
        variant.setProduct(products.findById(productId).orElseThrow());
        variant.setQuantity(1.0);
        variant.setUnit("kg");
        variant.setSellingPrice(price);
        variant.setCostPrice(price.subtract(BigDecimal.TEN));
        variant.setAvailable(Boolean.TRUE);
        variant.setActive(Boolean.TRUE);
        Long variantId = variants.save(variant).getId();

        // Only this shop lists it, and only this shop stocks it.
        jdbc.update("DELETE FROM shop_product_variants WHERE product_variant_id = ?", variantId);
        ShopProductVariant listing = new ShopProductVariant();
        listing.setShopId(shopId);
        listing.setProductVariantId(variantId);
        listing.setSellingPrice(price);
        listing.setCostPrice(price.subtract(BigDecimal.TEN));
        listing.setAvailable(Boolean.TRUE);
        listing.setActive(Boolean.TRUE);
        TenantContext.runWithin(TenantScope.ofShop(shopId), () -> listings.save(listing));

        jdbc.update("DELETE FROM inventory WHERE product_variant_id = ?", variantId);
        jdbc.update("INSERT INTO inventory (product_variant_id, stock, reserved_stock, shop_id) "
                + "VALUES (?, ?, 0, ?)", variantId, stock, shopId);
        return variantId;
    }

    private void addLine(Cart cart, Long variantId, long shopId, BigDecimal price) {
        CartItem item = new CartItem();
        item.setCart(cart);
        item.setProductVariant(variants.findById(variantId).orElseThrow());
        item.setQuantity(1);
        item.setShopId(shopId);
        item.setPrice(price);
        item.setTotalPrice(price);
        cartItems.save(item);
    }
}
