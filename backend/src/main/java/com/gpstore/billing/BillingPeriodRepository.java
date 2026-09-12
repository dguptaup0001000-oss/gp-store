package com.gpstore.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** A merchant's billing weeks. Platform data: every method names its merchant. */
public interface BillingPeriodRepository extends JpaRepository<BillingPeriod, Long> {

    Optional<BillingPeriod> findByMerchantIdAndStartsOn(Long merchantId, LocalDate startsOn);

    List<BillingPeriod> findByMerchantIdOrderByStartsOnDesc(Long merchantId);
}
