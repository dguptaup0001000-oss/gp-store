package com.gpstore.repository;

import com.gpstore.entity.OrderScanEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderScanEventRepository extends JpaRepository<OrderScanEvent, Long> {

    /**
     * The replay lookup for a retried scan. Scoped to the worker, because a
     * client request id is only unique within the app that generated it.
     */
    Optional<OrderScanEvent> findByPartnerIdAndClientRequestId(Long partnerId, String clientRequestId);

    /** Everything that happened to one order, newest first. */
    List<OrderScanEvent> findByOrderIdOrderByScannedAtDesc(Long orderId);

    /**
     * What this worker did in a window - the accountability question the whole
     * feature exists to answer. Paged, because a worker's history grows without
     * bound and the instance has 512 MB.
     */
    @Query("select e from OrderScanEvent e where e.partnerId = :partnerId "
            + "and e.scannedAt >= :from order by e.scannedAt desc")
    List<OrderScanEvent> findByPartnerSince(@Param("partnerId") Long partnerId,
                                            @Param("from") LocalDateTime from,
                                            Pageable pageable);

    /** Successful pack scans by this worker since a moment - "today's orders". */
    @Query("select count(e) from OrderScanEvent e where e.partnerId = :partnerId "
            + "and e.outcome = 'ACCEPTED' and e.action = 'PACKED' and e.scannedAt >= :from")
    long countAcceptedPacksSince(@Param("partnerId") Long partnerId, @Param("from") LocalDateTime from);
}
