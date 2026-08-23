package com.gpstore.repository;

import com.gpstore.entity.DeliveryPricingSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryPricingSettingsRepository
        extends JpaRepository<DeliveryPricingSettings, Long> {
}
