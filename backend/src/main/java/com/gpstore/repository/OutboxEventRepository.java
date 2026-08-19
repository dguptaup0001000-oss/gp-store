package com.gpstore.repository;

import com.gpstore.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claims up to {@code batchSize} due events for this worker.
     *
     * FOR UPDATE SKIP LOCKED is the important part. Without SKIP LOCKED, two
     * instances sweeping at once would block on each other's rows and
     * serialize the whole thing; with it, each instance takes a different
     * slice and they run in parallel safely. This also means the design
     * survives multiple instances without relying on ShedLock for
     * correctness - ShedLock reduces duplicate work, SKIP LOCKED is what
     * makes concurrent work correct.
     *
     * Bounded by LIMIT on purpose: a backlog (an outage, a deploy pause, a
     * handler that was failing for an hour) must be drained in steady
     * batches rather than loaded into memory at once.
     *
     * Ordered by next_attempt_at so the oldest due work goes first and a
     * repeatedly-retrying event cannot starve fresh ones.
     */
    @Query(value = "SELECT * FROM outbox_events "
            + "WHERE status = 'PENDING' AND next_attempt_at <= :now "
            + "ORDER BY next_attempt_at, id "
            + "LIMIT :batchSize "
            + "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> claimDueBatch(@Param("now") LocalDateTime now, @Param("batchSize") int batchSize);

    /**
     * Purges old successfully-processed rows. FAILED rows are deliberately
     * never purged here - those represent real business work that never
     * happened (a missing invoice) and must stay visible until a human
     * resolves them.
     */
    @Modifying
    @Query(value = "DELETE FROM outbox_events WHERE id IN ("
            + "SELECT id FROM outbox_events "
            + "WHERE status = 'PROCESSED' AND processed_at < :cutoff "
            + "ORDER BY id LIMIT :batchSize)", nativeQuery = true)
    int deleteProcessedBatch(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);

    long countByStatus(OutboxEvent.Status status);
}
