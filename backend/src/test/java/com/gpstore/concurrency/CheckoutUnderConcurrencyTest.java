package com.gpstore.concurrency;

import com.gpstore.support.TestMobileNumbers;
import com.gpstore.catalog.shop.ShopProductVariant;
import com.gpstore.catalog.shop.ShopProductVariantRepository;
import com.gpstore.dto.request.PlaceOrderRequest;
import com.gpstore.dto.response.PlaceOrderResponse;
import com.gpstore.entity.*;
import com.gpstore.platform.*;
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
import java.util.concurrent.Callable;

import static com.gpstore.concurrency.ConcurrencyHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Checkout, when the same customer presses the button twice at once.
 *
 * WHY SIMULTANEOUS RATHER THAN SEQUENTIAL. A repeat call made after the first
 * has finished is the easy half, and the existing suite already covers it: the
 * second request finds the idempotency record and replays. The half that
 * matters is two requests that BOTH pass the "have I seen this key" check
 * before either has written a row - a double-tap on a slow connection, or a
 * client retrying a request that had not actually failed. That case is decided
 * by the unique constraint on (customer_id, idempotency_key) and by what the
 * loser does with the violation, and nothing had ever exercised it.
 *
 * THE INVARIANT IS ALWAYS "HOW MANY ORDERS EXIST", never which thread won.
 * One purchase must produce one order, and the customer's stock must be
 * decremented once - a duplicate here is a second real order a kirana packs
 * and a customer pays for.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("Checkout, pressed twice at the same instant")
class CheckoutUnderConcurrencyTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orders;
    @Autowired private CustomerRepository customers;
    @Autowired private AddressRepository addresses;
    @Autowired private CartRepository carts;
    @Autowired private CartItemRepository cartItems;
    @Autowired private CategoryRepository categories;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;
    @Autowired private ShopProductVariantRepository listings;

    private final String tag = "cchk" + System.nanoTime();

    private long shopA;
    private long shopB;
    private Long merchantB;
    private Long customerId;
    private Long addressId;
    private Long cartId;
    private Long categoryId;
    private Long productId;
    private Long variantForA;
    private Long variantForB;

    private static final BigDecimal PRICE = new BigDecimal("100.00");

    @BeforeEach
    void aCustomerWithABasketFromTwoShops() {
        Shop first = shops.findByCode(platform.getFirstShopCode()).orElseThrow();
        shopA = first.getId();

        Merchant second = new Merchant();
        second.setLegalName("Checkout race " + tag);
        second.setDisplayName("Race B");
        second.setStatus(MerchantStatus.ACTIVE);
        second.setIsDemo(Boolean.TRUE);
        second.setActive(Boolean.TRUE);
        merchantB = merchants.save(second).getId();

        Shop b = new Shop();
        b.setMerchantId(merchantB);
        b.setCode("CCHK-" + tag);
        b.setDisplayName("Race shop B");
        b.setStatus(ShopStatus.ACTIVE);
        b.setLatitude(first.getLatitude());
        b.setLongitude(first.getLongitude());
        b.setMaxDeliveryRadiusKm(first.getMaxDeliveryRadiusKm());
        b.setTimeZone(first.getTimeZone());
        b.setIsDemo(Boolean.TRUE);
        b.setActive(Boolean.TRUE);
        shopB = shops.save(b).getId();

        Customer customer = new Customer();
        customer.setFullName("Race customer " + tag);
        customer.setEmail(tag + "@example.test");
        customer.setMobileNumber(TestMobileNumbers.unique());
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
        address.setArea("Race Area");
        address.setCity("Race City");
        address.setState("Race State");
        address.setPincode("110001");
        address.setCountry("India");
        address.setLatitude(first.getLatitude());
        address.setLongitude(first.getLongitude());
        address.setDefaultAddress(true);
        addressId = addresses.save(address).getId();

        Category category = new Category();
        category.setName("Race category " + tag);
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        categoryId = categories.save(category).getId();

        Product product = new Product();
        product.setName("Race product " + tag);
        product.setCategory(category);
        product.setActive(true);
        productId = products.save(product).getId();

        variantForA = newVariantStockedAt(shopA, 20);
        variantForB = newVariantStockedAt(shopB, 20);

        Cart cart = new Cart();
        cart.setCustomer(customer);
        cartId = carts.save(cart).getId();
        addLine(cart, variantForA, shopA);
        addLine(cart, variantForB, shopB);
    }

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        for (int attempt = 1; ; attempt++) {
            try {
                deleteFixture();
                return;
            } catch (org.springframework.dao.DataIntegrityViolationException stillReferenced) {
                if (attempt == 5) { throw stillReferenced; }
                try { Thread.sleep(200L * attempt); }
                catch (InterruptedException interrupted) {
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
        jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM shops WHERE id = ?", shopB);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantB);
    }

    // ------------------------------------------------- simultaneous duplicates

    @Test
    @DisplayName("the same idempotency key sent twice at once creates ONE checkout, not two")
    void aSimultaneousDoubleTapCreatesOneOrder() {
        String oneKey = UUID.randomUUID().toString();

        List<Callable<PlaceOrderResponse>> presses = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            presses.add(() -> checkoutWith(oneKey));
        }

        List<Outcome<PlaceOrderResponse>> outcomes = raceAll(presses);

        // Both callers may legitimately get a 200 - one placed it, the other
        // replayed the winner. What must never differ is how many orders the
        // shops actually have to pack.
        long groups = jdbc.queryForObject(
                "SELECT count(*) FROM order_groups WHERE customer_id = ?", Long.class, customerId);
        long placedOrders = jdbc.queryForObject(
                "SELECT count(*) FROM orders WHERE customer_id = ?", Long.class, customerId);

        assertEquals(1L, groups,
                "one press of Place Order is one checkout, however many requests it produced."
                        + describe(outcomes));
        assertEquals(2L, placedOrders,
                "two shops in the basket means two orders - and exactly two. Four here is a "
                        + "customer charged twice and two kiranas packing the same goods."
                        + describe(outcomes));

        // And the winner's stock moved once, not twice.
        assertEquals(19, stockOf(variantForA), "Shop A's stock must have moved exactly once");
        assertEquals(19, stockOf(variantForB), "Shop B's stock must have moved exactly once");

        assertTrue(succeeded(outcomes) >= 1,
                "at least one caller must be told what happened to their money."
                        + describe(outcomes));
    }

    @Test
    @DisplayName("both racing callers are answered - the loser replays rather than 409ing")
    void theLoserOfTheRaceIsGivenTheWinnersOrder() {
        String oneKey = UUID.randomUUID().toString();

        List<Outcome<PlaceOrderResponse>> outcomes = raceAll(List.of(
                () -> checkoutWith(oneKey), () -> checkoutWith(oneKey)));

        long answered = succeeded(outcomes);
        assertEquals(2, answered,
                "a customer whose retry lost a race must still be told their order exists. "
                        + "Failing the loser turns a successful purchase into an error screen, "
                        + "and the usual response to an error screen is to try again."
                        + describe(outcomes));

        Long firstGroup = outcomes.get(0).value().getOrderGroupId();
        Long secondGroup = outcomes.get(1).value().getOrderGroupId();
        assertEquals(firstGroup, secondGroup,
                "both answers must describe the SAME checkout." + describe(outcomes));

        // AND THE WHOLE OF IT, WHICHEVER CALLER IS BEING ANSWERED. The basket
        // spans two shops, so every answer owes the customer both shop
        // orders. Asserted over both outcomes rather than over a "winner" and
        // a "loser" because which thread won is not knowable from here - and
        // that is the point: the two answers must be indistinguishable.
        //
        // A REPLAY IS READ IN THE REQUEST'S OWN SHOP SCOPE, and a checkout
        // spans shops, so this is where the second shop's order went missing:
        // the customer paid two kiranas and their app was told about one.
        for (Outcome<PlaceOrderResponse> outcome : outcomes) {
            assertEquals(2, outcome.value().getShopOrders().size(),
                    "every answer to this checkout must list both shops' orders - an answer "
                            + "naming one of them is an app showing half a purchase."
                            + describe(outcomes));
        }
    }

    @Test
    @DisplayName("different keys are different checkouts, even sent together")
    void twoGenuinelyDifferentCheckoutsAreNotCollapsed() {
        // The other direction, and it matters just as much: idempotency must
        // not swallow a real second purchase. Only the first can succeed here
        // because the winner clears the cart - but they must not be merged.
        List<Outcome<PlaceOrderResponse>> outcomes = raceAll(List.of(
                () -> checkoutWith(UUID.randomUUID().toString()),
                () -> checkoutWith(UUID.randomUUID().toString())));

        long groups = jdbc.queryForObject(
                "SELECT count(*) FROM order_groups WHERE customer_id = ?", Long.class, customerId);
        assertTrue(groups >= 1, "at least one checkout must have gone through." + describe(outcomes));
        assertEquals(succeeded(outcomes), groups,
                "each request that succeeded must have produced its own checkout - two successes "
                        + "sharing one group would mean a purchase silently vanished."
                        + describe(outcomes));
    }

    // ------------------------------------------ ShopScopeSwitch under load

    @Test
    @DisplayName("concurrent multi-shop checkouts do not mix one shop's lines into another's order")
    void theScopeSwitchDoesNotLeakBetweenConcurrentCheckouts() {
        // ShopScopeSwitch re-points the Hibernate filter MID-TRANSACTION, which
        // is the newest and least-exercised concurrency surface in the system.
        // If the filter were shared rather than per-session, two checkouts
        // running at once would see each other's scope halfway through and
        // produce orders holding the wrong shop's lines.
        List<Callable<PlaceOrderResponse>> presses = List.of(
                () -> checkoutWith(UUID.randomUUID().toString()),
                () -> checkoutWith(UUID.randomUUID().toString()));

        List<Outcome<PlaceOrderResponse>> outcomes = raceAll(presses);
        assertTrue(succeeded(outcomes) >= 1, "at least one checkout must complete." + describe(outcomes));

        List<Order> placed = TenantContext.runWithin(TenantScope.platform(),
                () -> orders.findByCustomerId(customerId));

        for (Order order : placed) {
            Long orderShop = order.getShopId();
            List<Long> lineVariants = jdbc.queryForList(
                    "SELECT product_variant_id FROM order_items WHERE order_id = ?",
                    Long.class, order.getId());
            for (Long lineVariant : lineVariants) {
                Long expectedShop = lineVariant.equals(variantForA) ? shopA : shopB;
                assertEquals(expectedShop, orderShop,
                        "order " + order.getId() + " belongs to shop " + orderShop
                                + " but carries a line only shop " + expectedShop + " stocks. "
                                + "That is the scope switch leaking across concurrent "
                                + "transactions - one merchant packing another's goods");
            }
        }
    }

    @Test
    @DisplayName("a failed checkout leaves no half-order and no missing stock")
    void arollbackLeavesBothShopsConsistent() {
        // Shop B has nothing left, so its half of the split must fail - and
        // the whole checkout is one transaction, so neither half may persist.
        jdbc.update("UPDATE inventory SET stock = 0 WHERE product_variant_id = ?", variantForB);

        List<Outcome<PlaceOrderResponse>> outcomes = raceAll(List.of(
                () -> checkoutWith(UUID.randomUUID().toString()),
                () -> checkoutWith(UUID.randomUUID().toString())));

        assertEquals(0, succeeded(outcomes),
                "a basket the shops cannot fill must not check out." + describe(outcomes));

        long placedOrders = jdbc.queryForObject(
                "SELECT count(*) FROM orders WHERE customer_id = ?", Long.class, customerId);
        long groups = jdbc.queryForObject(
                "SELECT count(*) FROM order_groups WHERE customer_id = ?", Long.class, customerId);

        assertEquals(0L, placedOrders,
                "a rolled-back checkout must leave NO order behind - not even the shop whose "
                        + "half succeeded. Half a basket delivered is worse than none");
        assertEquals(0L, groups, "and no empty checkout group either");
        assertEquals(20, stockOf(variantForA),
                "Shop A's stock must be exactly back where it started - a decrement that "
                        + "survived a rolled-back order is stock the shop can never sell");
    }

    // ------------------------------------------------------------- fixtures

    private PlaceOrderResponse checkoutWith(String idempotencyKey) {
        PlaceOrderRequest request = new PlaceOrderRequest();
        request.setAddressId(addressId);
        request.setPaymentMethod("COD");
        return TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> orderService.placeOrder(request, customerId, idempotencyKey));
    }

    private int stockOf(Long variantId) {
        Integer stock = jdbc.queryForObject(
                "SELECT stock FROM inventory WHERE product_variant_id = ?", Integer.class, variantId);
        return stock == null ? -1 : stock;
    }

    private Long newVariantStockedAt(long shopId, int stock) {
        ProductVariant variant = new ProductVariant();
        variant.setProduct(products.findById(productId).orElseThrow());
        variant.setQuantity(1.0);
        variant.setUnit("kg");
        variant.setSellingPrice(PRICE);
        variant.setCostPrice(PRICE.subtract(BigDecimal.TEN));
        variant.setAvailable(Boolean.TRUE);
        variant.setActive(Boolean.TRUE);
        Long variantId = variants.save(variant).getId();

        jdbc.update("DELETE FROM shop_product_variants WHERE product_variant_id = ?", variantId);
        ShopProductVariant listing = new ShopProductVariant();
        listing.setShopId(shopId);
        listing.setProductVariantId(variantId);
        listing.setSellingPrice(PRICE);
        listing.setCostPrice(PRICE.subtract(BigDecimal.TEN));
        listing.setAvailable(Boolean.TRUE);
        listing.setActive(Boolean.TRUE);
        TenantContext.runWithin(TenantScope.ofShop(shopId), () -> listings.save(listing));

        jdbc.update("DELETE FROM inventory WHERE product_variant_id = ?", variantId);
        jdbc.update("INSERT INTO inventory (product_variant_id, stock, reserved_stock, shop_id) "
                + "VALUES (?, ?, 0, ?)", variantId, stock, shopId);
        return variantId;
    }

    private void addLine(Cart cart, Long variantId, long shopId) {
        CartItem item = new CartItem();
        item.setCart(cart);
        item.setProductVariant(variants.findById(variantId).orElseThrow());
        item.setQuantity(1);
        item.setShopId(shopId);
        item.setPrice(PRICE);
        item.setTotalPrice(PRICE);
        cartItems.save(item);
    }
}
