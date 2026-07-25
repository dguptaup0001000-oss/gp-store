package com.gpstore.repository;

import com.gpstore.entity.DeliveryPartner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeliveryPartnerRepository extends JpaRepository<DeliveryPartner, Long> {

    List<DeliveryPartner> findByAvailable(Boolean available);

    /** Resolves a logged-in Customer (role=DELIVERY_BOY) back to their operational roster record. */
    Optional<DeliveryPartner> findByAccountId(Long customerId);
}