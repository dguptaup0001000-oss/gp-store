package com.gpstore.platform;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShopRepository extends JpaRepository<Shop, Long> {

    Optional<Shop> findByCode(String code);

    List<Shop> findByMerchantId(Long merchantId);

    List<Shop> findByStatus(ShopStatus status);

    List<Shop> findByIsDemoTrue();

    /**
     * Whether this deployment is operating more than one real storefront.
     *
     * <p>The production database can gain its second shop before an external
     * {@code platform.mode} setting is changed. Product isolation is a data
     * boundary, so it must follow the shops that actually exist rather than
     * trusting deployment configuration to be updated at exactly the same
     * moment.
     */
    long countByDeletedAtIsNull();
}
