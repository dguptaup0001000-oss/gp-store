package com.gpstore.service;

import com.gpstore.entity.Address;
import com.gpstore.address.AddressValidator;
import com.gpstore.entity.Customer;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.AddressRepository;
import com.gpstore.territory.AddressTerritory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AddressService {

    private final AddressRepository repository;
    private final AddressTerritory territory;

    public AddressService(AddressRepository repository, AddressTerritory territory) {
        this.repository = repository;
        this.territory = territory;
    }

    /**
     * Customer address create. Always INSERTs: a body that carries someone
     * else's id must not become an UPDATE of that row.
     */
    public Address createOwned(Customer owner, Address address) {
        address.setId(null);
        address.setCustomer(owner);

        // Checked here rather than in the controller so every path that
        // creates a customer address goes through the same rule - a check
        // that lives in one endpoint is a check the next endpoint forgets.
        AddressValidator.validateForSave(address);
        address.setPincode(AddressValidator.normalisePincode(address.getPincode()));

        // Provenance is the server's to state. A client claiming its pin came
        // from a provider it never called would make an unverified coordinate
        // look geocoded.
        address.setGeocodingProvider(null);

        Address saved = repository.save(address);
        applyDefaultExclusivity(saved);
        territory.stampForCurrentShop(saved);
        return saved;
    }

    public Address save(Address address) {
        // A CLIENT CANNOT CARRY A TERRITORY ANY MORE, and not because this
        // method checks: the stamp lives in its own shop-owned table, so there
        // is no field on the request body that could name one. That is the
        // second thing moving it off the address bought - the first being that
        // two shops stopped overwriting each other's answer.
        Address saved = repository.save(address);
        territory.stampForCurrentShop(saved);
        return saved;
    }

    /**
     * WHERE THE STAMPING WENT. It used to be a private method here that wrote
     * {@code addresses.subzone_id} - one shop's answer, on a row that belongs
     * to the customer and is read by every shop. It now lives in
     * {@link AddressTerritory}, against a table with one row per shop, and the
     * reasons it exists at all are written there: permanence for a rider's
     * round, a hand-placed pin outranking the map, and "no territory" being a
     * real answer rather than a guess at the nearest one.
     */

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
        return repository.findAllPaged(pageable);
    }

    public List<Address> getCustomerAddresses(Long customerId) {
        return repository.findByCustomerId(customerId);
    }

    public Address getById(Long id) {
        return repository.findByIdForRead(id).orElse(null);
    }

    /** Throws if the address doesn't exist or doesn't belong to this customer - prevents IDOR. */
    public Address getOwnedAddress(Long id, Long customerId) {
        Address address = repository.findByIdForRead(id)
                .orElseThrow(() -> new ResourceNotFoundException("Address not found"));

        if (address.getCustomer() == null || !address.getCustomer().getId().equals(customerId)) {
            throw new ResourceNotFoundException("Address not found");
        }
        return address;
    }

    public Address updateAddress(Long id, Address updatedAddress) {

    Address address = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Address not found"));

    AddressValidator.validateForSave(updatedAddress);
    updatedAddress.setPincode(AddressValidator.normalisePincode(updatedAddress.getPincode()));

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

    // The V34 detail fields. Copied for the same reason the coordinates are:
    // this method rewrites the row from the request body, so a field it does
    // not copy is a field an edit silently wipes.
    address.setLabel(updatedAddress.getLabel());
    address.setBuildingName(updatedAddress.getBuildingName());
    address.setFloor(updatedAddress.getFloor());
    address.setStreet(updatedAddress.getStreet());
    address.setFormattedAddress(updatedAddress.getFormattedAddress());
    address.setDeliveryInstructions(updatedAddress.getDeliveryInstructions());
    address.setLocationAccuracy(updatedAddress.getLocationAccuracy());
    address.setPlaceId(updatedAddress.getPlaceId());
    address.setConfirmedAt(updatedAddress.getConfirmedAt());

    Address saved = repository.save(address);
    applyDefaultExclusivity(saved);

    // THIS SHOP RE-STAMPS, AND SAYS NOTHING TO ANYBODY ELSE. If the customer
    // moved their pin, every other shop's stamp is now about somewhere else -
    // and each of them finds that out from its own row, because a stamp
    // records the point it was resolved from (AddressTerritoryStamp). One
    // shop's request is not responsible for telling the others.
    //
    // Permanence is about boundaries not moving under a customer. It was never
    // about an address being stuck with an answer the customer has just told
    // us is wrong.
    territory.stampForCurrentShop(saved);
    return saved;
}

/**
 * Makes one address the customer's default, and the others not.
 *
 * ONE DEFAULT, ENFORCED IN CODE. Nothing before this unset the flag on the
 * other rows, so a customer who ticked "default" on a second address simply
 * had two - and checkout's auto-select takes whichever the query returned
 * first, which is to say whichever Postgres felt like. Two defaults is not a
 * harmless inconsistency when the consequence is a basket quietly addressed
 * to the wrong house.
 *
 * Not a database constraint, because a partial unique index on
 * (customer_id) WHERE default_address would reject the intermediate state of
 * any change that sets the new default before clearing the old one. The
 * ordering is easier to get right here, inside the transaction.
 */
public void setDefault(Long addressId, Long customerId) {
    Address target = getOwnedAddress(addressId, customerId);
    target.setDefaultAddress(true);
    repository.save(target);
    applyDefaultExclusivity(target);
}

/** Clears the default flag on every OTHER address of the same customer. */
private void applyDefaultExclusivity(Address chosen) {
    if (!Boolean.TRUE.equals(chosen.getDefaultAddress()) || chosen.getCustomer() == null) {
        return;
    }
    for (Address other : repository.findByCustomerId(chosen.getCustomer().getId())) {
        if (!other.getId().equals(chosen.getId())
                && Boolean.TRUE.equals(other.getDefaultAddress())) {
            other.setDefaultAddress(false);
            repository.save(other);
        }
    }
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