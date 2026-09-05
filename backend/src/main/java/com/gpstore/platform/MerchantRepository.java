package com.gpstore.platform;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MerchantRepository extends JpaRepository<Merchant, Long> {

    List<Merchant> findByStatus(MerchantStatus status);

    /** Demo merchants, so nothing ever counts them as real traction (§22). */
    List<Merchant> findByIsDemoTrue();
}
