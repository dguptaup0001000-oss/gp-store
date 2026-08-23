package com.gpstore.service;

import com.gpstore.entity.Address;
import com.gpstore.entity.DeliverySubzone;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.AddressRepository;
import com.gpstore.repository.DeliverySubzoneRepository;
import com.gpstore.territory.TerritoryResolver;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AddressService {

    private final AddressRepository repository;
    private final TerritoryResolver territoryResolver;
    private final DeliverySubzoneRepository subzoneRepository;

    public AddressService(AddressRepository repository,
                          TerritoryResolver territoryResolver,
                          DeliverySubzoneRepository subzoneRepository) {
        this.repository = repository;
        this.territoryResolver = territoryResolver;
        this.subzoneRepository = subzoneRepository;
    }

    public Address save(Address address) {
        stampTerritory(address);
        return repository.save(address);
    }

    /**
     * Fixes this address into its permanent delivery territory.
     *
     * WHY AT SAVE TIME AND NOWHERE ELSE. The territory a customer belongs to
     * has to be stable: a rider learns Z7B by delivering to the same houses
     * week after week, and that only works if those houses stay in Z7B.
     * Resolving on every read would mean an administrator nudging a boundary
     * silently reshuffles existing customers between riders, with no record
     * that it happened. Stamping the answer here makes the territory a fact
     * about the address rather than a fact about today's map.
     *
     * It also keeps the point-in-polygon test off the checkout path entirely.
     * Preview runs on every cart change; addresses are saved once.
     *
     * A LOCKED ADDRESS IS NEVER TOUCHED. When an administrator has placed an
     * address by hand - the house on the wrong side of the line, the colony
     * whose only gate opens into the next territory - that judgement outranks
     * anything the polygons say, including after the customer edits their
     * coordinates.
     *
     * NO MATCH LEAVES IT NULL, deliberately. An address outside every drawn
     * territory keeps no subzone rather than being pushed into the nearest
     * one. "We do not know" is answerable - it shows up as a FALLBACK
     * assignment an administrator can go and fix - whereas a wrong territory
     * is a rider quietly sent across a river with nothing anywhere saying so.
     */
    private void stampTerritory(Address address) {
        if (address == null || Boolean.TRUE.equals(address.getSubzoneLocked())) {
            return;
        }

        DeliverySubzone resolved = territoryResolver
                .resolveSubzoneId(address.getLatitude(), address.getLongitude())
                .flatMap(subzoneRepository::findById)
                .orElse(null);

        address.setSubzone(resolved);
    }

    /**
     * Admin listing, PAGED and sorted - never findAll().
     *
     * The unbounded version loaded every address in the shop into memory to
     * serialise them all in one response. At a hundred thousand customers
     * that is an OutOfMemoryError on a 512 MB instance, triggered by one
     * admin clicking once, and it takes the whole application down with it -
     * customers browsing and checking out included.
     *
     * Sorted by id because an unsorted paged query has no defined order in
     * Postgres: page 2 can repeat rows from page 1 and skip others entirely.
     * id is the primary key, so this needs no new index - it uses the one
     * every table already has.
     */
    public org.springframework.data.domain.Page<Address> getAll(
            org.springframework.data.domain.Pageable pageable) {
        return repository.findAll(pageable);
    }

    public List<Address> getCustomerAddresses(Long customerId) {
        return repository.findByCustomerId(customerId);
    }

    public Address getById(Long id) {
        return repository.findById(id).orElse(null);
    }

    /** Throws if the address doesn't exist or doesn't belong to this customer - prevents IDOR. */
    public Address getOwnedAddress(Long id, Long customerId) {
        Address address = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Address not found"));

        if (address.getCustomer() == null || !address.getCustomer().getId().equals(customerId)) {
            throw new ResourceNotFoundException("Address not found");
        }
        return address;
    }

    public Address updateAddress(Long id, Address updatedAddress) {

    Address address = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Address not found"));

    address.setFullName(updatedAddress.getFullName());
    address.setMobileNumber(updatedAddress.getMobileNumber());
    address.setHouseNo(updatedAddress.getHouseNo());
    address.setArea(updatedAddress.getArea());
    address.setLandmark(updatedAddress.getLandmark());
    address.setCity(updatedAddress.getCity());
    address.setDistrict(updatedAddress.getDistrict());
    address.setState(updatedAddress.getState());
    address.setPincode(updatedAddress.getPincode());
    address.setCountry(updatedAddress.getCountry());
    // These were missing entirely before - an edited address would keep its
    // OLD coordinates even if the customer corrected a wrong location,
    // silently breaking delivery distance/ETA/radius calculations for that
    // address going forward.
    address.setLatitude(updatedAddress.getLatitude());
    address.setLongitude(updatedAddress.getLongitude());
    address.setDefaultAddress(updatedAddress.getDefaultAddress());

    // Re-stamped because the coordinates just changed. A customer correcting
    // a pin that was two streets out has genuinely moved territory, and the
    // stamp has to follow - permanence is about boundaries not moving under a
    // customer, not about an address being stuck with a wrong answer forever.
    // subzoneLocked and the customer's own subzone assignment are read from
    // the stored row, never from the request body: an address edit arrives as
    // a plain entity from the client, and letting it carry a territory would
    // let a customer choose their own rider.
    stampTerritory(address);

    return repository.save(address);
}
public void deleteAddress(Long id) {
    if (!repository.existsById(id)) {
        throw new ResourceNotFoundException("Address not found");
    }

    try {
        repository.deleteById(id);
    } catch (org.springframework.dao.DataIntegrityViolationException ex) {
        // Past orders reference this address directly (not a copy) - deleting
        // it would orphan their delivery-address record. Without this catch,
        // the customer would just see a generic "unexpected error" with no
        // idea why - this tells them the real, actionable reason.
        throw new com.gpstore.exception.ConflictException(
                "This address is used in a past order and can't be deleted");
    }
}
}