package com.gpstore.repository;

import com.gpstore.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByCustomerIdAndIdempotencyKey(Long customerId, String idempotencyKey);

    /**
     * Deletes one bounded batch of expired records, oldest first.
     *
     * Batched rather than a single `DELETE WHERE created_at < cutoff`: this
     * table gets a row per checkout attempt forever, so the first run after
     * this retention policy ships could match a very large number of rows.
     * One statement over all of them takes a long-held lock on a table that
     * live checkout inserts into, which is precisely the thing a cleanup job
     * must not do.
     *
     * The subquery-with-LIMIT shape is deliberate - JPQL has no LIMIT on
     * DELETE, and the alternative (select ids, then delete by id list)
     * requires shipping ids to the JVM and back for no benefit.
     *
     * @return rows actually deleted, so the caller can stop as soon as a
     *         batch comes back short instead of guessing when it is done.
     */
    @Modifying
    @Query(value = "DELETE FROM idempotency_records WHERE id IN ("
            + "SELECT id FROM idempotency_records WHERE created_at < :cutoff "
            + "ORDER BY id LIMIT :batchSize)", nativeQuery = true)
    int deleteExpiredBatch(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);

    long countByCreatedAtBefore(LocalDateTime cutoff);
}
