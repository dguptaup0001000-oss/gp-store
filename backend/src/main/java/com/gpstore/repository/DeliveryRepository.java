package com.gpstore.repository;

import com.gpstore.entity.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DeliveryRepository
        extends JpaRepository<Delivery, Long> {

    Optional<Delivery> findByOrderId(Long orderId);

    long countByBatchId(Long batchId);

    /**
     * Current active (not yet delivered) order count per delivery partner -
     * this is the load-balancing signal auto-assignment uses to pick whichever
     * available partner currently has the least on their plate, instead of an
     * admin manually picking one of 10 partners for every single order.
     */
    @Query("select d.batch.deliveryPartner.id, count(d) from Delivery d " +
            "where d.deliveryStatus <> 'DELIVERED' and d.batch is not null " +
            "group by d.batch.deliveryPartner.id")
    List<Object[]> countActiveDeliveriesPerPartner();

    /**
     * Still in transit (never DELIVERED/CANCELLED) but already past the ETA
     * promised at assignment time, and not yet flagged. This is the proactive
     * half of the delivery guarantee check - catches lateness WHILE it's
     * happening, not just after the fact.
     */
    @Query("select d from Delivery d where d.estimatedDeliveryTime < :now " +
            "and d.deliveryStatus not in ('DELIVERED', 'CANCELLED') " +
            "and (d.guaranteeBreached = false or d.guaranteeBreached is null)")
    List<Delivery> findLateNotYetFlagged(@Param("now") LocalDateTime now);

    List<Delivery> findByGuaranteeBreachedTrueOrderByEstimatedDeliveryTimeDesc();
}