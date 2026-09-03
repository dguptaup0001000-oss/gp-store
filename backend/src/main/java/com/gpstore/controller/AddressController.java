package com.gpstore.controller;

import com.gpstore.entity.Address;
import com.gpstore.security.CurrentUser;
import com.gpstore.service.AddressService;
import com.gpstore.service.CustomerService;
import com.gpstore.service.DeliveryEstimateService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/addresses")
public class AddressController {

    private final AddressService addressService;
    private final CustomerService customerService;
    private final CurrentUser currentUser;
    private final DeliveryEstimateService deliveryEstimateService;
    private final com.gpstore.geo.ReverseGeocoder reverseGeocoder;

    public AddressController(AddressService addressService, CustomerService customerService,
                              CurrentUser currentUser, DeliveryEstimateService deliveryEstimateService,
                              com.gpstore.geo.ReverseGeocoder reverseGeocoder) {
        this.addressService = addressService;
        this.customerService = customerService;
        this.currentUser = currentUser;
        this.deliveryEstimateService = deliveryEstimateService;
        this.reverseGeocoder = reverseGeocoder;
    }

    /**
     * What the dropped pin probably means, so the customer edits instead of types.
     *
     * A SUGGESTION AND NOTHING MORE. Nothing here is saved, nothing here
     * decides a delivery fee - the fee is computed server-side from the
     * coordinates whatever the customer eventually writes in these boxes. It
     * exists so that "Location captured" is followed by a form that is already
     * mostly right, which in a village is the difference between an address
     * that finds the house and one nobody bothered to finish.
     *
     * ALWAYS 200, even when we could not ask. An empty object means "type it
     * yourself", which is precisely what the customer does today, and turning
     * a third party's bad afternoon into an error banner on our checkout would
     * be our bug, not theirs.
     */
    @GetMapping("/reverse-geocode")
    public java.util.Map<String, String> reverseGeocode(
            @RequestParam double latitude,
            @RequestParam double longitude) {
        return reverseGeocoder.suggest(latitude, longitude)
                .orElseGet(java.util.Map::of);
    }

    // Creates an address for the logged-in customer (ownership is never taken from the client).
    @PostMapping
    public Address createAddress(@RequestBody Address address) {
        // setId(null) is load-bearing. Binding the body onto Address would
        // otherwise let a client POST someone else's id and Hibernate-merge
        // that row (including rewriting customer_id). createOwned also
        // drops a client-supplied territory lock.
        return addressService.createOwned(
                customerService.getById(currentUser.customerId()), address);
    }

    /**
     * Admin listing. Paged, and the page size is capped SERVER-SIDE at 100 -
     * a client asking for size=1000000 gets 100, because a cap the caller
     * chooses is not a cap. Same convention as OrderController.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public org.springframework.data.domain.Page<Address> getAllAddresses(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return addressService.getAll(org.springframework.data.domain.PageRequest.of(
                Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                org.springframework.data.domain.Sort.by("id")));
    }

    // Returns only the addresses of the logged-in customer.
    @GetMapping("/mine")
    public List<Address> getMyAddresses() {
        return addressService.getCustomerAddresses(currentUser.customerId());
    }

    // Lets the app check "can this address even be delivered to" up front,
    // instead of only finding out when checkout rejects it.
    @GetMapping("/{id}/deliverable")
    public Map<String, Object> checkDeliverable(@PathVariable Long id) {
        Address address = addressService.getOwnedAddress(id, currentUser.customerId());
        double distanceKm = deliveryEstimateService.distanceFromStoreKm(address.getLatitude(), address.getLongitude());
        boolean deliverable = deliveryEstimateService.isWithinServiceableRadius(address.getLatitude(), address.getLongitude());

        return Map.of(
                "deliverable", deliverable,
                "distanceKm", Double.isNaN(distanceKm) ? null : Math.round(distanceKm * 10) / 10.0,
                "maxDeliveryRadiusKm", deliveryEstimateService.getMaxDeliveryRadiusKm()
        );
    }

    @PutMapping("/{id}")
    public Address updateAddress(@PathVariable Long id, @RequestBody Address address) {
        addressService.getOwnedAddress(id, currentUser.customerId()); // throws if not the caller's address
        return addressService.updateAddress(id, address);
    }

    /**
     * Makes this address the customer's default.
     *
     * A POST rather than a PUT of the whole address, because "make this one
     * the default" is one intent and PUT /{id} rewrites every field from the
     * body - a client sending a partial address to flip one flag would wipe
     * the rest, which is the trap address_repository.dart already warns about
     * for coordinates.
     *
     * Ownership first, as everywhere else here: a customer may only default
     * an address that is theirs.
     */
    @PostMapping("/{id}/default")
    public Address setDefaultAddress(@PathVariable Long id) {
        addressService.setDefault(id, currentUser.customerId());
        return addressService.getOwnedAddress(id, currentUser.customerId());
    }

    @DeleteMapping("/{id}")
    public String deleteAddress(@PathVariable Long id) {
        addressService.getOwnedAddress(id, currentUser.customerId()); // throws if not the caller's address
        addressService.deleteAddress(id);
        return "Address deleted successfully";
    }
}
