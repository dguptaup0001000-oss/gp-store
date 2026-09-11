package com.gpstore.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * A merchant's account with GP-STORE.
 *
 * <p>NO save-and-update METHODS BEYOND JpaRepository'S, and no delete that is
 * ever called: the table's trigger refuses UPDATE and DELETE outright. A
 * correction is an insert pointing at what it undoes.
 */
public interface MerchantLedgerRepository extends JpaRepository<MerchantLedgerEntry, Long> {

    List<MerchantLedgerEntry> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);

    List<MerchantLedgerEntry> findByBillingPeriodIdOrderByIdAsc(Long billingPeriodId);

    /**
     * What a merchant owes, as the SUM OF THE ROWS.
     *
     * <p>Computed, never stored. A balance column would be a second version of
     * the truth that can drift from the first - and the first is the one with
     * the dates, the reasons and the orders attached (§9).
     */
    @Query("select coalesce(sum(e.amount), 0) from MerchantLedgerEntry e "
            + "where e.merchantId = :merchantId")
    BigDecimal balanceOf(@Param("merchantId") Long merchantId);

    @Query("select coalesce(sum(e.amount), 0) from MerchantLedgerEntry e "
            + "where e.billingPeriodId = :periodId")
    BigDecimal totalForPeriod(@Param("periodId") Long periodId);

    /** Whether this order's commission has already been charged. */
    Optional<MerchantLedgerEntry> findByOrderIdAndEntryType(Long orderId, LedgerEntryType type);
}
