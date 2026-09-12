package com.gpstore.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * What each tier costs, and when.
 *
 * <p>PLATFORM DATA, NOT SHOP-OWNED, and deliberately not tenant-filtered: a
 * plan belongs to GP-STORE and applies across merchants. That is why the
 * services above it are the ones that check who is asking.
 */
public interface BillingPlanRepository extends JpaRepository<BillingPlan, Long> {

    /**
     * The plan in force for a tier on a day.
     *
     * <p>Newest effective_from that is not in the future, so a past week's
     * invoice is still reproducible - the whole reason plans are versioned
     * rather than edited.
     */
    @Query("""
            select p from BillingPlan p
            where p.tier = :tier
              and p.effectiveFrom <= :on
              and (p.effectiveTo is null or p.effectiveTo > :on)
            order by p.effectiveFrom desc
            """)
    List<BillingPlan> inForce(@Param("tier") MerchantTier tier, @Param("on") LocalDate on);

    List<BillingPlan> findAllByOrderByTierAscEffectiveFromDesc();
}
