package com.gpstore.repository;

import com.gpstore.entity.DeliveryPricingSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeliveryPricingSettingsRepository
        extends JpaRepository<DeliveryPricingSettings, Long> {

    /**
     * This shop's settings row.
     *
     * BY SHOP, NOT BY A CONSTANT. These were singletons found by findById(1),
     * which is both wrong with more than one shop and invisible to the
     * Hibernate filter - a load by primary key is not a query. Finding by
     * shop_id makes the predicate explicit, so it holds whether or not a
     * filter happens to be enabled on the session.
     */
    Optional<DeliveryPricingSettings> findByShopId(Long shopId);
}
