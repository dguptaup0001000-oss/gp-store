package com.gpstore.security;

import com.gpstore.entity.Address;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Role;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.AddressRepository;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.service.AddressService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Customer A's data, reached for by customer B.
 *
 * OrderOwnershipTest already covers orders. This covers ADDRESSES, which are
 * the other resource a customer both owns and mutates, and which carry a
 * home address and a phone number - so a leak here is a privacy incident, not
 * an inconvenience.
 *
 * NOT-FOUND, NOT FORBIDDEN, is the correct refusal and is asserted as such.
 * Answering 403 would confirm that address 41 exists and belongs to someone
 * else, which lets an attacker enumerate the customer base by walking ids.
 * Answering 404 tells them nothing they did not already know.
 *
 * Real database, real repositories - the ownership check reads
 * address.getCustomer().getId(), and a mocked repository would let a lazy
 * association behave in a way it never behaves in production.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class CrossCustomerAccessTest {

    @Autowired private AddressService addressService;
    @Autowired private AddressRepository addressRepository;
    @Autowired private CustomerRepository customerRepository;

    private Customer alice;
    private Customer mallory;
    private Address aliceAddress;

    @BeforeEach
    void setUp() {
        alice = newCustomer("alice");
        mallory = newCustomer("mallory");
        aliceAddress = newAddressFor(alice);
    }

    private Customer newCustomer(String who) {
        Customer c = new Customer();
        c.setFullName(who);
        c.setEmail(who + "-" + System.nanoTime() + "@idor-test.invalid");
        c.setMobileNumber(String.valueOf(System.nanoTime()).substring(0, 10));
        c.setRole(Role.CUSTOMER);
        c.setEnabled(true);
        c.setActive(true);
        c.setVerified(true);
        return customerRepository.save(c);
    }

    private Address newAddressFor(Customer owner) {
        Address a = new Address();
        a.setCustomer(owner);
        a.setFullName(owner.getFullName());
        a.setMobileNumber("9000000000");
        a.setHouseNo("12");
        a.setArea("Test Area");
        a.setCity("Test City");
        a.setState("Test State");
        a.setPincode("110001");
        a.setCountry("India");
        return addressRepository.save(a);
    }

    @Test
    @DisplayName("the owner can read their own address")
    void ownerCanRead() {
        assertDoesNotThrow(() -> addressService.getOwnedAddress(aliceAddress.getId(), alice.getId()));
    }

    @Test
    @DisplayName("another customer cannot read it, and is told it does not exist")
    void strangerCannotRead() {
        assertThrows(ResourceNotFoundException.class,
                () -> addressService.getOwnedAddress(aliceAddress.getId(), mallory.getId()),
                "customer B read customer A's address");
    }

    @Test
    @DisplayName("the refusal is indistinguishable from a genuinely missing address")
    void refusalLeaksNothingAboutExistence() {
        // Same exception type AND same message for "not yours" and "not there"
        // - anything else turns id enumeration into a customer census.
        String notYours = assertThrows(ResourceNotFoundException.class,
                () -> addressService.getOwnedAddress(aliceAddress.getId(), mallory.getId())).getMessage();

        String notThere = assertThrows(ResourceNotFoundException.class,
                () -> addressService.getOwnedAddress(999_999_999L, mallory.getId())).getMessage();

        assertEquals(notThere, notYours,
                "the message differs, so an attacker can tell which addresses exist");
    }

    @Test
    @DisplayName("an address cannot be reassigned to another customer through an update")
    void updateCannotStealAnAddress() {
        // MASS ASSIGNMENT. The controller binds the request body straight onto
        // an Address entity, so a crafted body can carry a "customer" field.
        // updateAddress must copy an allowlist of fields and never the owner -
        // otherwise customer B edits their own address, names customer A as
        // the owner, and quietly takes it over.
        Address hostile = new Address();
        hostile.setCustomer(mallory);          // the attack
        hostile.setFullName("edited");
        hostile.setMobileNumber("9111111111");
        hostile.setHouseNo("99");
        hostile.setArea("Elsewhere");
        hostile.setCity("Elsewhere");
        hostile.setState("Elsewhere");
        hostile.setPincode("999999");
        hostile.setCountry("India");

        addressService.updateAddress(aliceAddress.getId(), hostile);

        Address reloaded = addressRepository.findById(aliceAddress.getId()).orElseThrow();
        assertEquals(alice.getId(), reloaded.getCustomer().getId(),
                "the address changed owner - updateAddress is copying the customer field");
        assertEquals("edited", reloaded.getFullName(), "the legitimate fields should still update");

        // And the original owner must still be able to reach it.
        assertDoesNotThrow(() -> addressService.getOwnedAddress(aliceAddress.getId(), alice.getId()));
        assertThrows(ResourceNotFoundException.class,
                () -> addressService.getOwnedAddress(aliceAddress.getId(), mallory.getId()));
    }

    @Test
    @DisplayName("an address with no owner is never treated as everyone's")
    void ownerlessAddressIsNotPubliclyOwned() {
        Address orphan = new Address();
        orphan.setFullName("orphan");
        orphan.setMobileNumber("9000000000");
        orphan.setHouseNo("1");
        orphan.setArea("A");
        orphan.setCity("C");
        orphan.setState("S");
        orphan.setPincode("110001");
        orphan.setCountry("India");
        orphan = addressRepository.save(orphan);

        // A null customer must fail closed. The check reads
        // address.getCustomer().getId(), so a missing owner has to be handled
        // before the dereference or this is an NPE instead of a refusal.
        Long id = orphan.getId();
        assertThrows(ResourceNotFoundException.class,
                () -> addressService.getOwnedAddress(id, mallory.getId()));
    }
}
