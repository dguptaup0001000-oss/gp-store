package com.gpstore.repository;

import com.gpstore.entity.DeliveryBatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DeliveryBatchRepository extends JpaRepository<DeliveryBatch, Long> {

    List<DeliveryBatch> findByStatus(String status);

    List<DeliveryBatch> findByStatus(String status, org.springframework.data.domain.Pageable pageable);

    /**
     * Locks the most recent OPEN batch for this partner+area so two orders being
     * assigned at the same moment can't both squeeze past the 20-order cap
     * (same class of race condition as inventory oversell).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from DeliveryBatch b where b.deliveryPartner.id = :partnerId " +
            "and b.area = :area and b.status = 'OPEN' order by b.id desc")
    List<DeliveryBatch> findOpenBatchForUpdate(Long partnerId, String area);

    Optional<DeliveryBatch> findByBatchNumber(String batchNumber);
}
