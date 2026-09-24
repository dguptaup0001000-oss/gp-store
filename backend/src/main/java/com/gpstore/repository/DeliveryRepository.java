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

    /**
     * WHICH ORDER THIS DELIVERY BELONGS TO, WITHOUT LOADING THE DELIVERY.
     *
     * The one thing a status change needs before it may take any lock. The
     * project-wide lock ordering starts at the order row (ORDER -> PAYMENT ->
     * INVENTORY, and ORDER -> DELIVERY in the pack scan), so a change that
     * arrives holding only a delivery id has to find its order first - and it
     * must do that WITHOUT putting the Delivery, or its Order, or its Payment,
     * into the persistence context.
     *
     * That last part is the whole reason this is a scalar projection rather
     * than {@code findById(id).getOrder()}. Hibernate will not overwrite an
     * entity it has already loaded in this session when a later query - even a
     * locking one - returns the same row. So an unlocked read taken before the
     * lock is not a harmless head start: it is the value the locked re-read
     * will keep, and the lock then guards a decision made on stale data.
     * Reading one column returns a Long and leaves the session empty.
     */
    @Query("select d.order.id from Delivery d where d.id = :id")
    Optional<Long> findOrderIdById(@Param("id") Long id);

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
    List<Delivery> findLateNotYetFlagged(@Param("now") LocalDateTime now,
                                        org.springframework.data.domain.Pageable pageable);

    List<Delivery> findByGuaranteeBreachedTrueOrderByEstimatedDeliveryTimeDesc();

    /**
     * Live orders in one permanent territory, regardless of who is carrying
     * them.
     *
     * This is what "is Z7A overloaded" means: the load belongs to the
     * TERRITORY, not to the rider. Counting the primary partner's own
     * deliveries instead would give the wrong answer the moment an overflow
     * order went to a neighbour - the territory would look quieter precisely
     * because it was busy enough to need help.
     */
    @Query("select count(d) from Delivery d where d.subzone.id = :subzoneId "
            + "and d.deliveryStatus not in ('DELIVERED', 'CANCELLED')")
    long countActiveBySubzoneId(@Param("subzoneId") Long subzoneId);

    /**
     * Live orders a rider is carrying, counted from the DELIVERY rather than
     * through its batch.
     *
     * countActiveDeliveriesPerPartner above reaches the partner through
     * d.batch.deliveryPartner, so a delivery whose batch is null is invisible
     * to it. That is fine for load-balancing across a roster but not for the
     * capacity gate, where undercounting a rider's load is exactly the error
     * that hands an overloaded person one more drop.
     */
    @Query("select count(d) from Delivery d where d.batch.deliveryPartner.id = :partnerId "
            + "and d.deliveryStatus not in ('DELIVERED', 'CANCELLED')")
    long countActiveByPartnerId(@Param("partnerId") Long partnerId);

    /**
     * The live orders in one territory that are not yet on the road, oldest
     * first - the candidates for batching into a single route.
     */
    @Query("select d from Delivery d where d.subzone.id = :subzoneId "
            + "and d.deliveryStatus in ('ASSIGNED', 'PENDING') "
            + "order by d.assignedAt asc")
    List<Delivery> findBatchableBySubzoneId(@Param("subzoneId") Long subzoneId);

    /** A delivery partner's own currently-active (not delivered/cancelled) assignments - what their app screen shows. */
    @Query("select d from Delivery d where d.batch.deliveryPartner.id = :partnerId " +
            "and d.deliveryStatus not in ('DELIVERED', 'CANCELLED') " +
            "order by d.assignedAt asc")
    List<Delivery> findActiveByPartnerId(@Param("partnerId") Long partnerId);

    /** Bounded scalar projection for the merchant's Worker 360 history. */
    @Query("select d.id, d.order.orderNumber, d.deliveryStatus, d.assignedAt, d.deliveredAt "
            + "from Delivery d where d.batch.deliveryPartner.id = :partnerId "
            + "order by d.assignedAt desc, d.id desc")
    List<Object[]> workerHistory(@Param("partnerId") Long partnerId,
                                 org.springframework.data.domain.Pageable pageable);

    @Query("select d.id, d.order.orderNumber, d.deliveryStatus, d.assignedAt, d.deliveredAt "
            + "from Delivery d where d.batch.deliveryPartner.id = :partnerId "
            + "and d.deliveryStatus not in ('DELIVERED', 'CANCELLED') "
            + "order by d.assignedAt asc, d.id asc")
    List<Object[]> workerCurrentWork(@Param("partnerId") Long partnerId,
                                     org.springframework.data.domain.Pageable pageable);

    @Query("select d.deliveryStatus, count(d) from Delivery d "
            + "where d.batch.deliveryPartner.id = :partnerId group by d.deliveryStatus")
    List<Object[]> workerDeliveryCounts(@Param("partnerId") Long partnerId);
}
