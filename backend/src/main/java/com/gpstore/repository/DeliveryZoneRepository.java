package com.gpstore.repository;

import com.gpstore.entity.DeliveryZone;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeliveryZoneRepository extends JpaRepository<DeliveryZone, Long> {

    Optional<DeliveryZone> findByCodeIgnoreCase(String code);

    List<DeliveryZone> findByActiveTrueOrderByDisplayOrderAscIdAsc();
}
