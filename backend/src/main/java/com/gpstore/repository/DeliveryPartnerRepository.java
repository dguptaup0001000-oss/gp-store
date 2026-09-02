package com.gpstore.repository;

import com.gpstore.entity.DeliveryPartner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeliveryPartnerRepository extends JpaRepository<DeliveryPartner, Long> {

    List<DeliveryPartner> findByAvailable(Boolean available);

    org.springframework.data.domain.Page<DeliveryPartner> findByAvailable(Boolean available, org.springframework.data.domain.Pageable pageable);

    /** Resolves a logged-in Customer (role=DELIVERY_BOY) back to their operational roster record. */
    Optional<DeliveryPartner> findByAccountId(Long customerId);

    // ------------------------------------------------- the worker's own login
    //
    // ALL OF THESE EXCLUDE DELETED ROWS, and that is not an optimisation. A
    // removed worker keeps their row so finished orders still show who
    // delivered them - so every lookup that decides "can this person sign in"
    // or "is this address already taken" has to skip them, or a rider who has
    // left keeps working and their address can never be reissued.

    Optional<DeliveryPartner> findByLoginEmailIgnoreCaseAndDeletedAtIsNull(String loginEmail);

    Optional<DeliveryPartner> findByMobileAndDeletedAtIsNull(String mobile);

    List<DeliveryPartner> findByDeletedAtIsNull(org.springframework.data.domain.Sort sort);

    org.springframework.data.domain.Page<DeliveryPartner> findByDeletedAtIsNull(
            org.springframework.data.domain.Pageable pageable);
}