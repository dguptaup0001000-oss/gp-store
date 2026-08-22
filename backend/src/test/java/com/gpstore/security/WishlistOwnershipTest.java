package com.gpstore.security;

import com.gpstore.dto.request.WishlistRequest;
import com.gpstore.dto.response.WishlistResponse;
import com.gpstore.entity.*;
import com.gpstore.repository.*;
import com.gpstore.service.WishlistService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The wishlist, end to end, including the contract bug that made the feature
 * look broken in the app.
 *
 * THE BUG THIS PINS. WishlistResponse used to carry only productId and
 * productName. The app's model expects a nested `product`, so that field
 * parsed as null on every item - and every downstream check reads through it.
 * The heart never filled (isWishlisted compares item.product?.id), a second
 * tap added again instead of removing (toggle found no existing entry), and
 * My Wishlist rendered nothing. One missing key, three broken behaviours,
 * and nothing on the server logged a thing because the server was fine.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class WishlistOwnershipTest {

    @Autowired private WishlistService wishlistService;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductVariantRepository variantRepository;

    private Customer alice;
    private Customer bob;
    private Product product;

    @BeforeEach
    void setUp() {
        alice = newCustomer("alice");
        bob = newCustomer("bob");
        product = newProductWithVariant();
    }

    private Customer newCustomer(String who) {
        Customer c = new Customer();
        c.setFullName(who);
        c.setEmail(who + "-" + System.nanoTime() + "@wishlist-test.invalid");
        c.setMobileNumber(String.valueOf(System.nanoTime()).substring(0, 10));
        c.setRole(Role.CUSTOMER);
        c.setEnabled(true);
        c.setActive(true);
        c.setVerified(true);
        return customerRepository.save(c);
    }

    private Product newProductWithVariant() {
        Category category = new Category();
        category.setName("Wishlist Test Aisle " + System.nanoTime());
        category.setActive(true);
        category = categoryRepository.save(category);

        Product p = new Product();
        p.setName("Wishlist Test Product " + System.nanoTime());
        p.setBrand("TestBrand");
        p.setCategory(category);
        p.setActive(true);
        p = productRepository.save(p);

        ProductVariant v = new ProductVariant();
        v.setProduct(p);
        v.setQuantity(1.0);
        v.setUnit("kg");
        v.setMrp(new BigDecimal("100.00"));
        v.setSellingPrice(new BigDecimal("90.00"));
        v.setAvailable(true);
        v.setActive(true);
        variantRepository.save(v);
        return p;
    }

    private WishlistResponse addFor(Customer customer, Product p) {
        WishlistRequest request = new WishlistRequest();
        request.setProductId(p.getId());
        return wishlistService.saveWishlist(customer.getId(), request);
    }

    // ---------------- the contract ----------------

    @Test
    @DisplayName("a saved item carries the FULL product, not just an id and a name")
    void responseNestsTheProduct() {
        addFor(alice, product);

        List<WishlistResponse> mine = wishlistService.getMyWishlist(alice.getId());
        assertEquals(1, mine.size());

        WishlistResponse item = mine.get(0);
        assertNotNull(item.getProduct(),
                "product is null - this is the exact bug that made the heart never fill "
                + "and My Wishlist render empty");
        assertEquals(product.getId(), item.getProduct().getId());
        assertNotNull(item.getProduct().getName());
    }

    @Test
    @DisplayName("the nested product carries what a product CARD needs to render")
    void nestedProductIsRenderable() {
        addFor(alice, product);
        var nested = wishlistService.getMyWishlist(alice.getId()).get(0).getProduct();

        // id and name alone cannot draw a card. A wishlist screen needs the
        // price and the pack size, which live on the variant.
        assertNotNull(nested.getCategory(), "no category - the card cannot show its aisle");
        assertFalse(nested.getVariants().isEmpty(),
                "no variants - the card has no price and no pack size to show");
        assertNotNull(nested.getVariants().get(0).getSellingPrice());
    }

    @Test
    @DisplayName("the legacy flat fields are still present, so an older app build keeps working")
    void backwardCompatibleFieldsRemain() {
        addFor(alice, product);
        WishlistResponse item = wishlistService.getMyWishlist(alice.getId()).get(0);

        assertEquals(product.getId(), item.getProductId());
        assertEquals(product.getName(), item.getProductName());
    }

    // ---------------- ownership ----------------

    @Test
    @DisplayName("one customer's wishlist never contains another's saved product")
    void wishlistsAreIsolated() {
        addFor(alice, product);

        assertEquals(1, wishlistService.getMyWishlist(alice.getId()).size());
        assertEquals(0, wishlistService.getMyWishlist(bob.getId()).size(),
                "bob can see alice's wishlist");
    }

    @Test
    @DisplayName("both customers can save the same product independently")
    void sameProductTwoCustomers() {
        addFor(alice, product);
        addFor(bob, product);

        assertEquals(1, wishlistService.getMyWishlist(alice.getId()).size());
        assertEquals(1, wishlistService.getMyWishlist(bob.getId()).size());

        // Different rows - removing one must not remove the other.
        Long aliceItem = wishlistService.getMyWishlist(alice.getId()).get(0).getId();
        Long bobItem = wishlistService.getMyWishlist(bob.getId()).get(0).getId();
        assertNotEquals(aliceItem, bobItem);
    }

    @Test
    @DisplayName("removing from one wishlist leaves the other customer's untouched")
    void removalIsScopedToTheOwner() {
        addFor(alice, product);
        addFor(bob, product);

        Long aliceItem = wishlistService.getMyWishlist(alice.getId()).get(0).getId();
        wishlistService.removeFromWishlist(aliceItem, alice.getId());

        assertEquals(0, wishlistService.getMyWishlist(alice.getId()).size());
        assertEquals(1, wishlistService.getMyWishlist(bob.getId()).size(),
                "removing alice's entry also removed bob's");
    }

    @Test
    @DisplayName("one customer cannot remove another's saved product")
    void strangerCannotRemoveSomebodyElsesItem() {
        addFor(alice, product);
        Long aliceItem = wishlistService.getMyWishlist(alice.getId()).get(0).getId();

        // removeFromWishlist takes the caller's id and looks the row up by
        // BOTH - so a guessed wishlist item id is not enough to delete it.
        assertThrows(com.gpstore.exception.ResourceNotFoundException.class,
                () -> wishlistService.removeFromWishlist(aliceItem, bob.getId()),
                "bob deleted alice's wishlist entry using its id");

        assertEquals(1, wishlistService.getMyWishlist(alice.getId()).size(),
                "alice's entry disappeared");
    }

    @Test
    @DisplayName("the refusal looks the same as a missing item, so ids cannot be probed")
    void removalRefusalLeaksNothing() {
        addFor(alice, product);
        Long aliceItem = wishlistService.getMyWishlist(alice.getId()).get(0).getId();

        String notYours = assertThrows(com.gpstore.exception.ResourceNotFoundException.class,
                () -> wishlistService.removeFromWishlist(aliceItem, bob.getId())).getMessage();
        String notThere = assertThrows(com.gpstore.exception.ResourceNotFoundException.class,
                () -> wishlistService.removeFromWishlist(987_654_321L, bob.getId())).getMessage();

        assertEquals(notThere, notYours);
    }

    @Test
    @DisplayName("a wishlist survives being read again - it is persisted, not in-memory")
    void wishlistPersists() {
        addFor(alice, product);

        // Two independent reads through the service, each its own transaction.
        assertEquals(1, wishlistService.getMyWishlist(alice.getId()).size());
        assertEquals(1, wishlistService.getMyWishlist(alice.getId()).size());
    }

    @Test
    @DisplayName("an empty wishlist is an empty list, never null")
    void emptyWishlistIsEmptyNotNull() {
        List<WishlistResponse> mine = wishlistService.getMyWishlist(bob.getId());
        assertNotNull(mine, "null would crash the app's list rendering");
        assertTrue(mine.isEmpty());
    }
}
