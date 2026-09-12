package com.gpstore.platform;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShopRepository extends JpaRepository<Shop, Long> {

    Optional<Shop> findByCode(String code);

    List<Shop> findByMerchantId(Long merchantId);

    List<Shop> findByStatus(ShopStatus status);

    List<Shop> findByIsDemoTrue();
}
