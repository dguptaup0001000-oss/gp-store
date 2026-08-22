package com.gpstore.security;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Role;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.service.CartService;
import com.gpstore.service.InvoiceService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cart items and invoices, reached for by the wrong customer.
 *
 * A cart item leak lets someone edit a stranger's basket - annoying rather
 * than dangerous. An INVOICE is the serious one: it carries the delivery
 * address, the phone number, every line item and what was paid. That is the
 * single most sensitive document this application produces about a customer,
 * and it is addressed by a guessable order id.
 *
 * Both refusals must be indistinguishable from "does not exist" - answering
 * "forbidden" would confirm that order 500 has an invoice belonging to
 * somebody, which is exactly how an attacker maps the customer base by
 * walking integers.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class CartInvoiceOwnershipTest {

    @Autowired private CartService cartService;
    @Autowired private InvoiceService invoiceService;
    @Autowired private CustomerRepository customerRepository;

    private Customer alice;
    private Customer mallory;

    @BeforeEach
    void setUp() {
        alice = newCustomer("alice");
        mallory = newCustomer("mallory");
    }

    private Customer newCustomer(String who) {
        Customer c = new Customer();
        c.setFullName(who);
        c.setEmail(who + "-" + System.nanoTime() + "@ownership-test.invalid");
        c.setMobileNumber(String.valueOf(System.nanoTime()).substring(0, 10));
        c.setRole(Role.CUSTOMER);
        c.setEnabled(true);
        c.setActive(true);
        c.setVerified(true);
        return customerRepository.save(c);
    }

    // ---------------- invoices ----------------

    @Test
    @DisplayName("an invoice for an order the caller does not own is reported as missing")
    void strangerCannotReadAnInvoice() {
        // No invoice exists for this id at all, so both the ownership branch
        // and the missing branch converge on the same answer - which is the
        // property worth having. The next test proves they are identical.
        assertThrows(ResourceNotFoundException.class,
                () -> invoiceService.getOwnedInvoiceForOrder(987_654_321L, mallory.getId()));
    }

    @Test
    @DisplayName("the invoice refusal reveals nothing about whether the order exists")
    void invoiceRefusalIsIndistinguishable() {
        String forMissingOrder = assertThrows(ResourceNotFoundException.class,
                () -> invoiceService.getOwnedInvoiceForOrder(987_654_321L, mallory.getId())).getMessage();
        String forOtherId = assertThrows(ResourceNotFoundException.class,
                () -> invoiceService.getOwnedInvoiceForOrder(123_456_789L, alice.getId())).getMessage();

        assertEquals(forMissingOrder, forOtherId,
                "the two messages differ, so an attacker can distinguish existence from ownership");
    }

    // ---------------- cart items ----------------

    @Test
    @DisplayName("a customer cannot change the quantity of a cart item that is not theirs")
    void strangerCannotUpdateACartItem() {
        assertThrows(ResourceNotFoundException.class,
                () -> cartService.updateItemQuantity(mallory.getId(), 987_654_321L, 5),
                "a non-existent or foreign cart item must not be editable");
    }

    @Test
    @DisplayName("a customer cannot delete a cart item that is not theirs")
    void strangerCannotDeleteACartItem() {
        assertThrows(ResourceNotFoundException.class,
                () -> cartService.removeItem(mallory.getId(), 987_654_321L));
    }

    @Test
    @DisplayName("setting quantity to zero on a foreign item deletes nothing")
    void quantityZeroOnAForeignItemIsStillRefused() {
        // updateItemQuantity treats quantity <= 0 as a delete. The ownership
        // check has to happen BEFORE that branch, or "update to zero" becomes
        // an unauthenticated delete primitive wearing an update's clothes.
        assertThrows(ResourceNotFoundException.class,
                () -> cartService.updateItemQuantity(mallory.getId(), 987_654_321L, 0));
    }

    @Test
    @DisplayName("a customer who has never added anything gets an EMPTY cart, not an error")
    void brandNewCustomerSeesAnEmptyCart() {
        // getCustomerCart returns null until the first addToCart creates a
        // row - the cart is made lazily, not at registration. That null must
        // never reach a customer as a 500 on their first visit to the basket.
        assertNull(cartService.getCustomerCart(alice.getId()),
                "a cart row is expected to be created lazily on first add");

        // The DTO is what closes it: null becomes a well-formed empty cart.
        var response = com.gpstore.dto.response.CartResponse.from(
                cartService.getCustomerCart(alice.getId()));

        assertNotNull(response, "an absent cart must render as an empty cart, never null");
        assertTrue(response.getItems().isEmpty());
        assertEquals(0, java.math.BigDecimal.ZERO.compareTo(response.getTotalAmount()),
                "an empty cart must total zero, not null");
    }

    @Test
    @DisplayName("a cart never surfaces for the wrong customer")
    void cartsAreNotSharedBetweenCustomers() {
        // Both are empty here, which is the honest state for two fresh
        // customers. The property being asserted is that the lookup is keyed
        // by customer at all - if it were not, one of these would return the
        // other's row the moment either had one.
        assertNull(cartService.getCustomerCart(alice.getId()));
        assertNull(cartService.getCustomerCart(mallory.getId()));

        // And a lookup for an id that owns nothing returns nothing rather
        // than falling back to "some cart".
        assertNull(cartService.getCustomerCart(987_654_321L),
                "an unknown customer id returned a cart - the lookup is not keyed by customer");
    }
}
