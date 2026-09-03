package com.gpstore.engagement;

import com.gpstore.dto.response.AdminCustomerDetailResponse;
import com.gpstore.entity.Address;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Role;
import com.gpstore.repository.AddressRepository;
import com.gpstore.repository.CustomerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The customer file has to actually contain the customer.
 *
 * The authorization test next door proves the right people can open this and
 * the wrong ones cannot. It checks that the SHAPE is there - "addresses",
 * "cart", "wishlist" - which an endpoint returning five empty lists would
 * also satisfy. This checks the contents.
 *
 * The address assembly gets the attention because it is the part that can be
 * quietly wrong: the columns are seven separate fields that have to be joined
 * back into something a person would say out loud, the front-door directions
 * a customer typed in their own words are the single most useful line on the
 * screen for a rider, and the coordinates sitting right beside them are the
 * one thing that must NOT come out.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("What the customer file actually contains")
class CustomerDetailContentTest {

    @Autowired private AdminCustomerDetailService detail;
    @Autowired private CustomerRepository customers;
    @Autowired private AddressRepository addresses;
    @Autowired private AppSessionService sessions;

    private Customer newCustomer() {
        Customer customer = new Customer();
        customer.setFullName("Content Probe");
        customer.setEmail("content-" + UUID.randomUUID() + "@example.com");
        customer.setMobileNumber("9" + (100000000 + (int) (Math.random() * 899999999)));
        customer.setRole(Role.CUSTOMER);
        customer.setActive(true);
        customer.setEnabled(true);
        return customers.save(customer);
    }

    @Test
    @DisplayName("an address comes back readable, with the customer's own directions")
    void addressIsReadable() {
        Customer customer = newCustomer();

        Address address = new Address();
        address.setCustomer(customer);
        address.setLabel("HOME");
        address.setFullName("Content Probe");
        address.setMobileNumber(customer.getMobileNumber());
        address.setHouseNo("12");
        address.setStreet("Gali No 4");
        address.setArea("Shastri Nagar");
        address.setCity("Meerut");
        address.setState("Uttar Pradesh");
        address.setPincode("250004");
        address.setLandmark("Hanuman Mandir");
        // The whole point of the free-text field: a rider finds this house by
        // these words, not by the columns above.
        address.setDeliveryInstructions("hanuman mandir ke piche 2 gali chhod ke green colour ki house hai mere");
        address.setLatitude(28.9845);
        address.setLongitude(77.7064);
        address.setDefaultAddress(true);
        addresses.save(address);

        AdminCustomerDetailResponse file = detail.of(customer.getId());

        assertEquals(1, file.addresses().size());
        AdminCustomerDetailResponse.AddressLine line = file.addresses().get(0);

        // Joined back into one line, in the order a person would say it.
        assertEquals("12, Gali No 4, Shastri Nagar, Meerut, Uttar Pradesh", line.address());
        assertEquals("HOME", line.label());
        assertEquals("250004", line.pincode());
        assertEquals("Hanuman Mandir", line.landmark());
        assertTrue(line.isDefault());

        // Verbatim. Tidying this up would destroy what makes it useful.
        assertEquals("hanuman mandir ke piche 2 gali chhod ke green colour ki house hai mere",
                line.directions());

        // A pin exists, and the screen may say so - but not where it is.
        assertTrue(line.hasLocation());
    }

    @Test
    @DisplayName("the coordinates are not in the file at all")
    void coordinatesNeverLeave() {
        Customer customer = newCustomer();

        Address address = new Address();
        address.setCustomer(customer);
        address.setHouseNo("7");
        address.setCity("Meerut");
        address.setLatitude(28.98451234);
        address.setLongitude(77.70641234);
        addresses.save(address);

        AdminCustomerDetailResponse file = detail.of(customer.getId());
        AdminCustomerDetailResponse.AddressLine line = file.addresses().get(0);

        // AddressLine has no latitude or longitude component - there is
        // nowhere to put them - and the readable line must not have smuggled
        // them in through the free-text fields either. A staff screen gets
        // screenshotted; a home to eight decimal places should not travel
        // with it.
        assertTrue(line.hasLocation(), "whether a pin exists is still useful");
        assertFalse(line.address().contains("28.98"), line.address());
        assertFalse(line.address().contains("77.70"), line.address());
    }

    @Test
    @DisplayName("a missing part of the address does not leave a dangling comma")
    void gapsDoNotShow() {
        Customer customer = newCustomer();

        Address address = new Address();
        address.setCustomer(customer);
        address.setHouseNo("12");
        // No street, no building, no area - a real shape for a village address.
        address.setCity("Meerut");
        address.setState("  ");   // blank, not null - the other way this breaks
        addresses.save(address);

        AdminCustomerDetailResponse file = detail.of(customer.getId());
        String readable = file.addresses().get(0).address();

        assertEquals("12, Meerut", readable);
        assertFalse(readable.startsWith(","), readable);
        assertFalse(readable.endsWith(","), readable);
        assertFalse(readable.contains(",,"), readable);
    }

    @Test
    @DisplayName("a customer with nothing yet reads as empty, not as broken")
    void emptyCustomerIsEmptyNotNull() {
        Customer customer = newCustomer();

        AdminCustomerDetailResponse file = detail.of(customer.getId());

        assertNotNull(file.addresses());
        assertTrue(file.addresses().isEmpty());
        assertNotNull(file.cart());
        assertEquals(0, file.cart().totalItems());
        assertTrue(file.cart().items().isEmpty());
        assertNotNull(file.wishlist());
        assertTrue(file.wishlist().isEmpty());
        assertEquals(0, file.orders().count());
        assertEquals(0, file.engagement().totalSeconds());
        assertNull(file.engagement().lastSeen(), "never seen is not the same as seen at epoch");
        // firstOrderDate is deliberately not a sign-up date - the customers
        // table has never recorded one, and inventing it would be a
        // plausible-looking lie on a staff screen.
        assertNull(file.orders().firstOrderDate());
    }

    @Test
    @DisplayName("time in the app shows up on the file")
    void engagementReachesTheFile() {
        Customer customer = newCustomer();

        sessions.record(customer, 600);
        sessions.record(customer, 300);

        AdminCustomerDetailResponse file = detail.of(customer.getId());

        assertEquals(900, file.engagement().totalSeconds());
        assertEquals(2, file.engagement().sessionCount());
        assertNotNull(file.engagement().lastSeen());
    }

    @Test
    @DisplayName("no such customer is a 404, not an empty file")
    void unknownCustomerIsNotFound() {
        // An empty file for an id that does not exist would let a typo look
        // like a real customer with nothing on record.
        assertThrows(com.gpstore.exception.ResourceNotFoundException.class,
                () -> detail.of(-1L));
    }
}
