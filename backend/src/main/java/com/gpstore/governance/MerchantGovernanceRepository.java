package com.gpstore.governance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Reads are by merchant, explicitly, because these rows are not shop-scoped.
 *
 * <p>THAT PUTS THE BURDEN ON THE CALLER, and the caller is
 * {@link MerchantGovernance}, which derives the merchant from the shop in
 * scope rather than from anything a request carried. This interface is the
 * one place in the system where "pass the owner id in" is the right shape,
 * and it is worth saying so out loud: it is not an invitation to pass a
 * merchant id through from a controller.
 */
public interface MerchantGovernanceRepository extends JpaRepository<MerchantGovernanceAction, Long> {

    List<MerchantGovernanceAction> findByMerchantIdOrderByIssuedAtDesc(Long merchantId);

    Page<MerchantGovernanceAction> findByMerchantIdOrderByIssuedAtDesc(
            Long merchantId, Pageable pageable);

    Page<MerchantGovernanceAction> findByAppealedAtIsNotNullAndAppealOutcomeIsNullOrderByAppealedAtAsc(
            Pageable pageable);

    Page<MerchantGovernanceAction> findAllByOrderByIssuedAtDesc(Pageable pageable);
}
