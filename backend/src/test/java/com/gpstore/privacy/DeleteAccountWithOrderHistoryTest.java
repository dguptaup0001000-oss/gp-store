package com.gpstore.privacy;

import com.gpstore.entity.Address;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Order;
import com.gpstore.entity.Role;
import com.gpstore.enums.OrderStatus;
import com.gpstore.repository.AddressRepository;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.service.CustomerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deleting an account when that account has actually bought something.
 *
 * WHAT WAS BROKEN. deleteOwnAccount bulk-deletes the customer's addresses,
 * and orders.address_id is a foreign key to that table with NO ACTION on
 * delete. So the moment a customer had ever placed an order, deleting the
 * address was refused by Postgres, the whole transaction rolled back, and the
 * customer saw "That refers to something that no longer exists. Please
 * refresh and try again." on the Profile screen.
 *
 * That is every real customer. A shopper who never ordered could delete their
 * account; a shopper who had ordered - the only kind a shop has - could not.
 * It is also a Play Store commitment: the listing says an account can be
 * deleted in the app, and it could not be.
 *
 * WHY THE ADDRESS CANNOT SIMPLY GO. The order still needs somewhere it was
 * delivered - that is the shop's own record of a sale, and it has to survive
 * for accounting long after the customer has left. So the address stays
 * attached to the order and is SCRUBBED instead, exactly the way the customer
 * row itself is anonymised rather than dropped.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("Deleting an account that has order history")
class DeleteAccountWithOrderHistoryTest {

    private static final String PASSWORD = "a-real-passphrase";

    @Autowired private CustomerService customerService;
    @Autowired private CustomerRepository customers;
    @Autowired private AddressRepository addresses;
    @Autowired private OrderRepository orders;
    @Autowired private PasswordEncoder encoder;

    @Test
    @DisplayName("a customer who has ordered can still delete their account")
    void deletionSurvivesAnOrderPointingAtTheAddress() {
        Customer customer = newCustomer();
        Address address = newAddress(customer);
        Order order = newOrder(customer, address);

        // THE FAILING CASE. Before the fix this threw
        // DataIntegrityViolationException - orders.address_id still pointed at
        // the row being deleted - and the caller saw a 400 that read as if
        // they had done something wrong.
        assertDoesNotThrow(() -> customerService.deleteOwnAccount(customer.getId(), PASSWORD),
                "a customer with order history could not delete their account");

        assertTrue(orders.findById(order.getId()).isPresent(),
                "the shop's record of a sale must outlive the customer's account");
    }

    @Test
    @DisplayName("the address survives for the order, with the person scrubbed out of it")
    void theAddressIsAnonymisedRatherThanDeleted() {
        Customer customer = newCustomer();
        Address address = newAddress(customer);
        newOrder(customer, address);

        customerService.deleteOwnAccount(customer.getId(), PASSWORD);

        Address after = addresses.findById(address.getId()).orElseThrow(
                () -> new AssertionError("the order's delivery address was deleted from under it"));

        // NOTHING IDENTIFYING LEFT. Deleting an account has to mean the
        // personal data is gone even where the row cannot be.
        assertNull(after.getCustomer(), "the address is still joined to the deleted account");
        assertNotEquals("Deepak Gupta", after.getFullName());
        assertNotEquals("9876543210", after.getMobileNumber());
        assertNull(after.getLatitude(), "a precise location outlived the account");
        assertNull(after.getLongitude(), "a precise location outlived the account");
        assertNull(after.getDeliveryInstructions(),
                "directions to somebody's door outlived the account");
    }

    @Test
    @DisplayName("an address no order needs is deleted outright")
    void unreferencedAddressesStillGo() {
        Customer customer = newCustomer();
        Address spare = newAddress(customer);

        customerService.deleteOwnAccount(customer.getId(), PASSWORD);

        assertTrue(addresses.findById(spare.getId()).isEmpty(),
                "an address nothing references should not be kept at all");
    }

    // ------------------------------------------------------------- fixtures

    private Customer newCustomer() {
        Customer c = new Customer();
        c.setFullName("Deepak Gupta");
        c.setEmail("del-" + System.nanoTime() + "@example.com");
        c.setMobileNumber("9" + String.format("%09d", System.nanoTime() % 1_000_000_000L));
        c.setPassword(encoder.encode(PASSWORD));
        c.setRole(Role.CUSTOMER);
        c.setActive(true);
        c.setEnabled(true);
        return customers.save(c);
    }

    private Address newAddress(Customer customer) {
        Address a = new Address();
        a.setCustomer(customer);
        a.setFullName("Deepak Gupta");
        a.setMobileNumber("9876543210");
        a.setHouseNo("12");
        a.setStreet("Hanuman Mandir Road");
        a.setCity("Kanpur");
        a.setPincode("208001");
        a.setLatitude(26.4499);
        a.setLongitude(80.3319);
        a.setDeliveryInstructions("green house, second gali");
        return addresses.save(a);
    }

    private Order newOrder(Customer customer, Address address) {
        Order o = new Order();
        o.setOrderNumber("DEL-" + System.nanoTime());
        o.setCustomer(customer);
        o.setAddress(address);
        o.setTotalAmount(new BigDecimal("250.00"));
        o.setOrderStatus(OrderStatus.DELIVERED);
        o.setOrderDate(LocalDateTime.now().minusDays(2));
        o.setActive(true);
        return orders.save(o);
    }
}
