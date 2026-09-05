package com.gpstore.platform;

import com.gpstore.entity.Cart;
import com.gpstore.entity.CartItem;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Role;
import com.gpstore.catalog.shop.ShopProductVariant;
import com.gpstore.catalog.shop.ShopProductVariantRepository;
import com.gpstore.entity.Category;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.repository.*;
import com.gpstore.security.AuthenticatedUser;
import com.gpstore.security.RolePermissions;
import com.gpstore.service.CartService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Who works where, and what a basket looks like when it spans two of them.
 *
 * THREE THINGS THAT ONLY BREAK ONCE THERE IS A SECOND SHOP, which is why they
 * are together: a rider's shop comes off their roster row rather than a staff
 * list they are not on; a shop may hire, but only within limits that stop it
 * reaching into another merchant's roster; and a basket screen has to ask each
 * line's OWN shop for its price and stock, or half the basket goes grey.
 *
 * That last one is a bug the previous slice introduced and this one found: the
 * split made baskets multi-shop before the basket screen knew.
 */
@SpringBootTest(properties = {
        "platform.mode=MULTI_SHOP_PRODUCTION",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("Riders, hiring, and a basket that spans two shops")
class ShopStaffAndRidersTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantResolver resolver;
    @Autowired private ShopMembership membership;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private CartService cartService;
    @Autowired private CustomerRepository customers;
    @Autowired private CartRepository carts;
    @Autowired private CartItemRepository cartItems;
    @Autowired private CategoryRepository categories;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;
    @Autowired private ShopProductVariantRepository listings;

    private final String tag = "staff" + System.nanoTime();

    private long shopA;
    private long shopB;
    private Long merchantB;
    private Long riderId;
    private Long customerId;
    private Long cartId;
    private Long variantAtA;
    private Long variantAtB;
    private Long categoryId;
    private Long productId;

    private static final BigDecimal PRICE_AT_A = new BigDecimal("31.00");
    private static final BigDecimal PRICE_AT_B = new BigDecimal("62.00");

    @BeforeEach
    void twoShops() {
        installDefaultsFor(PlatformMode.MULTI_SHOP_PRODUCTION);
        shopA = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();

        Merchant m = new Merchant();
        m.setLegalName("Roster fixture " + tag);
        m.setDisplayName("Second kirana");
        m.setStatus(MerchantStatus.ACTIVE);
        m.setIsDemo(Boolean.TRUE);
        m.setActive(Boolean.TRUE);
        merchantB = merchants.save(m).getId();

        Shop b = new Shop();
        b.setMerchantId(merchantB);
        b.setCode("RST-" + tag);
        b.setDisplayName("Second kirana");
        b.setStatus(ShopStatus.ACTIVE);
        b.setIsDemo(Boolean.TRUE);
        b.setActive(Boolean.TRUE);
        shopB = shops.save(b).getId();

        jdbc.update("""
                INSERT INTO delivery_partners (name, mobile, available, active, shop_id)
                VALUES (?, ?, false, true, ?)
                """, "Rider " + tag,
                "8" + String.valueOf(System.nanoTime()).substring(0, 9), shopB);
        riderId = jdbc.queryForObject("SELECT id FROM delivery_partners WHERE name = ?",
                Long.class, "Rider " + tag);

        Customer customer = new Customer();
        customer.setFullName("Basket " + tag);
        customer.setEmail(tag + "@example.test");
        customer.setMobileNumber("9" + String.valueOf(System.nanoTime()).substring(0, 9));
        customer.setPassword("not-a-real-hash");
        customer.setEnabled(true);
        customer.setActive(true);
        customer.setRole(Role.CUSTOMER);
        customer = customers.save(customer);
        customerId = customer.getId();

        Category category = new Category();
        category.setName("Roster category " + tag);
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        categoryId = categories.save(category).getId();

        Product product = new Product();
        product.setName("Roster product " + tag);
        product.setCategory(category);
        product.setActive(true);
        productId = products.save(product).getId();

        variantAtA = newVariantStockedAt(shopA, PRICE_AT_A, 15);
        variantAtB = newVariantStockedAt(shopB, PRICE_AT_B, 15);

        Cart cart = new Cart();
        cart.setCustomer(customer);
        cartId = carts.save(cart).getId();
        addLine(cart, variantAtA, shopA, PRICE_AT_A);
        addLine(cart, variantAtB, shopB, PRICE_AT_B);
    }

    @AfterEach
    void tidyUp() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        jdbc.update("DELETE FROM cart_items WHERE cart_id = ?", cartId);
        jdbc.update("DELETE FROM carts WHERE id = ?", cartId);
        jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        jdbc.update("DELETE FROM delivery_partners WHERE id = ?", riderId);
        jdbc.update("DELETE FROM shop_staff WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM shop_product_variants WHERE product_variant_id in (?, ?)",
                variantAtA, variantAtB);
        jdbc.update("DELETE FROM inventory WHERE product_variant_id in (?, ?)", variantAtA, variantAtB);
        jdbc.update("DELETE FROM product_variants WHERE id in (?, ?)", variantAtA, variantAtB);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM shops WHERE id = ?", shopB);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantB);
        installDefaultsFor(PlatformMode.SINGLE_SHOP);
    }

    // ------------------------------------------------------- a rider's shop

    @Test
    @DisplayName("a rider works in the shop on their roster row, not on a staff list")
    void aRidersShopComesOffTheirRosterRow() {
        signedInAsRider();

        TenantScope scope = resolver.resolve();

        assertEquals(shopB, scope.requireShopId(),
                "a worker session carries a workerId and no customer id at all - their "
                        + "credentials live on the roster, so the roster is where their shop is");
    }

    @Test
    @DisplayName("a rider taken off every roster has no shop to work in")
    void aRiderWithNoShopIsRefused() {
        jdbc.update("UPDATE delivery_partners SET shop_id = NULL WHERE id = ?", riderId);
        signedInAsRider();

        assertThrows(IllegalStateException.class, resolver::resolve,
                "picking a shop for a rider nobody hired would be inventing an authorization");
    }

    // -------------------------------------------------- a shop hiring its own

    @Test
    @DisplayName("a shop may hire somebody who works nowhere else")
    void aShopMayHireAFreeAgent() {
        Long newHire = newStaffAccount("hire", Role.MANAGER);
        try {
            ShopStaff added = membership.addToOwnShop(shopB, newHire, this::roleOf);

            assertEquals(shopB, added.getShopId());
            assertTrue(membership.permits(newHire, shopB));
        } finally {
            jdbc.update("DELETE FROM shop_staff WHERE customer_id = ?", newHire);
            jdbc.update("DELETE FROM customers WHERE id = ?", newHire);
        }
    }

    @Test
    @DisplayName("a shop cannot hire somebody who already works for another merchant")
    void aShopCannotPoachAnotherMerchantsStaff() {
        Long theirManager = newStaffAccount("theirs", Role.MANAGER);
        try {
            membership.grant(shopA, theirManager, true);

            assertThrows(RuntimeException.class,
                    () -> membership.addToOwnShop(shopB, theirManager, this::roleOf),
                    "a shop that could add any account could attach a competitor's owner to its "
                            + "own roster, and shop-switching would do the rest");
            assertFalse(membership.permits(theirManager, shopB));
        } finally {
            jdbc.update("DELETE FROM shop_staff WHERE customer_id = ?", theirManager);
            jdbc.update("DELETE FROM customers WHERE id = ?", theirManager);
        }
    }

    @Test
    @DisplayName("a shop cannot hire a platform administrator")
    void aShopCannotHireThePlatform() {
        Long operator = newStaffAccount("operator", Role.PLATFORM_ADMIN);
        try {
            assertThrows(RuntimeException.class,
                    () -> membership.addToOwnShop(shopB, operator, this::roleOf),
                    "a platform operator's scope spans the marketplace, and a shop must not be "
                            + "able to reach for it");
        } finally {
            jdbc.update("DELETE FROM shop_staff WHERE customer_id = ?", operator);
            jdbc.update("DELETE FROM customers WHERE id = ?", operator);
        }
    }

    @Test
    @DisplayName("a shop cannot turn a customer into staff")
    void aShopCannotHireACustomer() {
        assertThrows(RuntimeException.class,
                () -> membership.addToOwnShop(shopB, customerId, this::roleOf),
                "a customer added to a roster would silently gain a shop scope they never "
                        + "applied for");
        assertFalse(membership.permits(customerId, shopB));
    }

    // ---------------------------------- the basket, across two shops

    @Test
    @DisplayName("a basket spanning two shops shows both lines, each at its own shop's price")
    void aMultiShopBasketIsNotHalfGrey() {
        // THE BUG THIS PINS. The basket used to be read in whichever shop's
        // scope the request arrived with, so the other shop's lines came back
        // with no stock and a stale price - drawn as out of stock in a basket
        // that checkout would happily have taken.
        var response = TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> cartService.getCustomerCartResponse(customerId));

        assertEquals(2, response.getItems().size());

        var atA = lineFor(response, variantAtA);
        var atB = lineFor(response, variantAtB);

        assertEquals(0, PRICE_AT_A.compareTo(atA.getPrice()));
        assertEquals(0, PRICE_AT_B.compareTo(atB.getPrice()),
                "the second shop's line must be priced from the second shop's listing, even "
                        + "though the request arrived in the first shop's scope");
        assertTrue(Boolean.TRUE.equals(atA.getAvailable()), "the first shop's line is in stock");
        assertTrue(Boolean.TRUE.equals(atB.getAvailable()),
                "and so is the second's - reading it in the wrong shop's scope found no stock "
                        + "row and drew a perfectly orderable item as unavailable");
    }

    @Test
    @DisplayName("a line whose shop has delisted it is shown as unavailable, in the basket")
    void aDelistedLineIsGreyBeforeCheckoutNotAfter() {
        TenantContext.runWithin(TenantScope.ofShop(shopB), () -> {
            ShopProductVariant listing = listings.findByProductVariantId(variantAtB).orElseThrow();
            listing.setActive(Boolean.FALSE);
            listing.setAvailable(Boolean.FALSE);
            return listings.save(listing);
        });

        var response = TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> cartService.getCustomerCartResponse(customerId));

        assertTrue(Boolean.TRUE.equals(lineFor(response, variantAtA).getAvailable()));
        assertFalse(Boolean.TRUE.equals(lineFor(response, variantAtB).getAvailable()),
                "checkout already refuses a delisted line, so nobody is overcharged - but "
                        + "finding out after choosing an address and a payment method is the "
                        + "surprise this flag exists to prevent");
    }

    // ------------------------------------------------------------ fixtures

    private com.gpstore.dto.response.CartResponse.CartItemResponse lineFor(
            com.gpstore.dto.response.CartResponse response, Long variantId) {
        return response.getItems().stream()
                .filter(item -> variantId.equals(item.getVariantId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no basket line for variant " + variantId));
    }

    private String roleOf(Long accountId) {
        return jdbc.queryForObject("SELECT role FROM customers WHERE id = ?", String.class, accountId);
    }

    private void installDefaultsFor(PlatformMode mode) {
        Long shopOne = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();
        TenantDefaults.install(mode, () -> shopOne);
    }

    private Long newStaffAccount(String kind, Role role) {
        String email = tag + "-" + kind + "@example.test";
        jdbc.update("""
                INSERT INTO customers (full_name, email, mobile_number, password, role, active)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, true)
                """, kind + " " + tag, email,
                "7" + String.valueOf(System.nanoTime()).substring(0, 9), role.name());
        return jdbc.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
    }

    private Long newVariantStockedAt(long shopId, BigDecimal price, int stock) {
        ProductVariant variant = new ProductVariant();
        variant.setProduct(products.findById(productId).orElseThrow());
        variant.setQuantity(1.0);
        variant.setUnit("kg");
        variant.setSellingPrice(price);
        variant.setCostPrice(price.subtract(BigDecimal.ONE));
        variant.setAvailable(Boolean.TRUE);
        variant.setActive(Boolean.TRUE);
        Long variantId = variants.save(variant).getId();

        jdbc.update("DELETE FROM shop_product_variants WHERE product_variant_id = ?", variantId);
        ShopProductVariant listing = new ShopProductVariant();
        listing.setShopId(shopId);
        listing.setProductVariantId(variantId);
        listing.setSellingPrice(price);
        listing.setCostPrice(price.subtract(BigDecimal.ONE));
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

    /** Exactly the principal JwtFilter builds for a worker token. */
    private void signedInAsRider() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String authority : RolePermissions.authorityNames(Role.DELIVERY_BOY)) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedUser(null, tag + "-rider@example.test",
                                Role.DELIVERY_BOY.name(), riderId),
                        null, authorities));
    }
}
