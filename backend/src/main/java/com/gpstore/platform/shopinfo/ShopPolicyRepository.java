package com.gpstore.platform.shopinfo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * This shop's policies. JPQL over a shop-owned entity, so the filter narrows
 * it - which is why nothing here takes a shop id, and why nothing here can be
 * asked for another shop's promises by passing one.
 */
public interface ShopPolicyRepository extends JpaRepository<ShopPolicy, Long> {

    List<ShopPolicy> findAllByOrderByKindAsc();

    Optional<ShopPolicy> findByKind(String kind);
}
