package com.gpstore.service;

import com.gpstore.entity.Address;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.AddressRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AddressService {

    private final AddressRepository repository;

    public AddressService(AddressRepository repository) {
        this.repository = repository;
    }

    public Address save(Address address) {
        return repository.save(address);
    }

    public List<Address> getAll() {
        return repository.findAll();
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