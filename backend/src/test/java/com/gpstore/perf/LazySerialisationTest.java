package com.gpstore.perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpstore.entity.Address;
import com.gpstore.entity.Customer;
import com.gpstore.entity.DeliverySubzone;
import com.gpstore.entity.DeliveryZone;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.DeliverySubzoneRepository;
import com.gpstore.repository.DeliveryZoneRepository;
import com.gpstore.service.AddressService;
import com.gpstore.territory.TerritoryResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Responses still serialise now that a database connection is no longer held
 * open for the whole request.
 *
 * WHAT CHANGED AND WHY IT NEEDS A TEST. open-session-in-view is off (see
 * spring.jpa.open-in-view), because holding a pooled connection from the
 * filter chain until the response has been written is how ten connections get
 * spent on threads waiting for each other rather than on queries. The cost of
 * turning it off is that any lazy association touched during serialisation
 * stops silently issuing a query and starts throwing.
 *
 * The integration suite caught one of those - the admin inventory list, which
 * failed loudly and was fixed by mapping inside the service. THIS FILE IS FOR
 * THE ONE IT COULD NOT CATCH.
 *
 * Address.subzone is lazy and AddressController returns the entity. Every
 * existing test passes because no territories have been drawn yet, so every
 * subzone is null - and a null needs no proxy. The first polygon saved would
 * have turned "my addresses" into a 500 for every customer inside a
 * territory, with a stack trace pointing at Jackson and nothing pointing at
 * territories.
 *
 * WHAT THIS TEST ACTUALLY FOUND when it was first run, which is worth
 * recording because it is not what it was written to find: the chain does not
 * stop at the subzone. It goes Address -> subzone -> neighbours, a lazy
 * collection, and past that to the subzone's assigned delivery partner and
 * its polygon boundary. So the response was not merely fragile, it was
 * shipping the shop's dispatch internals - including a rider's phone number -
 * to every customer who opened their address book. The fix was @JsonIgnore on
 * Address.subzone: no client has ever read it (the Flutter AddressModel has
 * no such field), the territory is still stamped on the row, and it is now
 * the server's business only.
 *
 * So this test draws a territory first, and only then asks the question.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("Responses serialise with no session open")
class LazySerialisationTest {

    private static final String PREFIX = "LS-";
    private static final String MARKER = "LAZY_SERIALISATION_TEST";

    private static final String BOUNDARY = "[[28.60,77.20],[28.60,77.22],[28.62,77.22],[28.62,77.20]]";
    private static final double INSIDE_LAT = 28.610;
    private static final double INSIDE_LNG = 77.210;

    @Autowired private AddressService addressService;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private DeliveryZoneRepository zoneRepository;
    @Autowired private DeliverySubzoneRepository subzoneRepository;
    @Autowired private TerritoryResolver resolver;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.gpstore.service.CartService cartService;

    private Customer customer;

    @BeforeEach
    void drawATerritoryAndPutSomebodyInIt() {
        cleanUp();

        DeliveryZone zone = new DeliveryZone();
        zone.setCode(PREFIX + "Z1");
        zone.setName("Lazy serialisation zone");
        zone.setActive(true);
        zone = zoneRepository.save(zone);

        DeliverySubzone subzone = new DeliverySubzone();
        subzone.setZone(zone);
        subzone.setCode(PREFIX + "Z1A");
        subzone.setName("Lazy serialisation subzone");
        subzone.setBoundary(BOUNDARY);
        subzone.setActive(true);
        subzoneRepository.save(subzone);
        resolver.invalidate();

        customer = new Customer();
        customer.setFullName(MARKER);
        customer.setEmail("lazy-serialisation-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber("9" + String.valueOf(System.nanoTime()).substring(0, 9));
        customer.setPassword("irrelevant-for-this-test");
        customer.setEnabled(true);
        customer.setActive(true);
        customer = customerRepository.save(customer);

        Address address = new Address();
        address.setCustomer(customer);
        address.setFullName(MARKER);
        address.setMobileNumber("9000000002");
        address.setHouseNo("12");
        address.setArea("Test Colony");
        address.setCity("Testville");
        address.setPincode("110001");
        address.setLatitude(INSIDE_LAT);
        address.setLongitude(INSIDE_LNG);

        Address saved = addressService.save(address);
        assertNotNull(saved.getSubzone(),
                "The fixture is wrong if the address was not stamped - with a null subzone this whole "
                        + "file would pass without testing anything.");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("UPDATE addresses SET subzone_id = NULL WHERE subzone_id IN "
                + "(SELECT id FROM delivery_subzones WHERE code LIKE ?)", PREFIX + "%");
        jdbc.update("UPDATE deliveries SET subzone_id = NULL WHERE subzone_id IN "
                + "(SELECT id FROM delivery_subzones WHERE code LIKE ?)", PREFIX + "%");
        jdbc.update("UPDATE delivery_batches SET subzone_id = NULL WHERE subzone_id IN "
                + "(SELECT id FROM delivery_subzones WHERE code LIKE ?)", PREFIX + "%");
        jdbc.update("DELETE FROM addresses WHERE full_name = ?", MARKER);
        jdbc.update("DELETE FROM subzone_neighbours WHERE subzone_id IN "
                + "(SELECT id FROM delivery_subzones WHERE code LIKE ?)", PREFIX + "%");
        jdbc.update("DELETE FROM delivery_subzones WHERE code LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM delivery_zones WHERE code LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM customers WHERE full_name = ?", MARKER);
        resolver.invalidate();
    }

    /**
     * The exact sequence a request performs: the service returns, the
     * transaction closes, and only then is the response written. Calling the
     * service from a test method with no transaction of its own reproduces
     * that faithfully - which is the point, because doing this inside
     * @Transactional would pass whether or not the fix exists.
     */
    @Test
    @DisplayName("a customer's own addresses serialise after the transaction has closed")
    void customerAddressesSerialise() {
        List<Address> addresses = addressService.getCustomerAddresses(customer.getId());
        assertFalse(addresses.isEmpty(), "fixture did not produce an address");

        String json = assertDoesNotThrow(() -> objectMapper.writeValueAsString(addresses),
                "Serialising the address touched a lazy association with no session open. On a real "
                        + "request that is a 500 on 'my addresses' for every customer inside a drawn "
                        + "territory.");

        // AND THE TERRITORY IS NOT IN THE RESPONSE AT ALL. Not surviving
        // serialisation - excluded from it. A subzone drags its polygon, its
        // zone and its assigned rider's name and phone; none of that belongs
        // on a customer's phone, and no client has ever read it.
        assertFalse(json.contains("\"subzone\""),
                "The address response is carrying its delivery territory again. That is dispatch "
                        + "internals - boundary, zone, assigned partner and their phone number - being "
                        + "sent to a customer.");
        assertFalse(json.contains("\"boundary\""),
                "A territory polygon is being sent to a customer's phone.");
    }

    @Test
    @DisplayName("a single address serialises after the transaction has closed")
    void oneAddressSerialises() {
        Address address = addressService.getCustomerAddresses(customer.getId()).get(0);
        Address reloaded = addressService.getById(address.getId());

        assertDoesNotThrow(() -> objectMapper.writeValueAsString(reloaded));
    }

    @Test
    @DisplayName("the admin address listing serialises after the transaction has closed")
    void theAdminListingSerialises() {
        var page = addressService.getAll(PageRequest.of(0, 20));
        assertDoesNotThrow(() -> objectMapper.writeValueAsString(page.getContent()),
                "The paged admin listing is the same trap with pagination on top.");
    }

    @Test
    @DisplayName("the customer's cart serialises after the transaction has closed")
    void theCartSerialises() {
        // The busiest authenticated read in the application, and the one with
        // the deepest lazy chain: cart -> items -> variant -> product. A
        // customer with no cart row still gets an empty CartResponse rather
        // than null, which is the existing contract and is asserted here so
        // the refactor that moved this mapping into the service cannot have
        // quietly changed it.
        var cart = cartService.getCustomerCartResponse(customer.getId());

        assertNotNull(cart, "A customer with no cart must still get a CartResponse, not null.");
        assertNotNull(cart.getItems(), "An empty cart still has an items array.");
        assertDoesNotThrow(() -> objectMapper.writeValueAsString(cart));
    }
}
