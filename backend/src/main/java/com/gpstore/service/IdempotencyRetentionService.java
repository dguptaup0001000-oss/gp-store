package com.gpstore.service;

import com.gpstore.repository.IdempotencyRecordRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Keeps idempotency_records from growing forever.
 *
 * The table gains a row per checkout attempt and nothing ever removed them,
 * so it grew monotonically for the life of the deployment - unbounded
 * storage, and a steadily slower unique-constraint lookup on the hottest
 * path in the system (every single checkout does one).
 *
 * What retention must NOT do is delete a record that could still be needed
 * for real retry protection. A record is what stops a retried checkout from
 * creating a second order, so deleting one too early re-opens exactly the
 * duplicate-order hole the key exists to close. The retention window is
 * therefore set far longer than any plausible client retry: a client retries
 * a checkout over seconds or minutes, not days.
 */
@Service
public class IdempotencyRetentionService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyRetentionService.class);

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final IdempotencyRetentionService self;
    private final int retentionDays;
    private final int batchSize;
    private final int maxBatchesPerRun;

    public IdempotencyRetentionService(
            IdempotencyRecordRepository idempotencyRecordRepository,
            // Own proxy, so each batch below actually commits in its own
            // transaction - a plain this.call() would bypass the proxy and
            // silently collapse every batch back into one transaction,
            // defeating the batching entirely.
            @org.springframework.context.annotation.Lazy IdempotencyRetentionService self,
            @Value("${idempotency.retention-days:7}") int retentionDays,
            @Value("${idempotency.cleanup-batch-size:500}") int batchSize,
            @Value("${idempotency.cleanup-max-batches-per-run:40}") int maxBatchesPerRun) {

        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.self = self;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
    }

    /**
     * Runs hourly rather than on a tight loop - this is housekeeping, and
     * spreading it out keeps its impact on live traffic negligible.
     *
     * @SchedulerLock so only one instance sweeps once there is more than
     * one, the same way the payment expiry sweep is protected.
     *
     * Not @Transactional at this level on purpose: each batch commits
     * separately (see deleteOneBatch), so locks are released continuously
     * and an interrupted run keeps whatever it already deleted instead of
     * rolling the whole thing back.
     */
    @Scheduled(fixedDelay = 60 * 60 * 1000)
    @SchedulerLock(name = "cleanupExpiredIdempotencyRecords", lockAtMostFor = "30m", lockAtLeastFor = "1m")
    public void cleanupExpiredRecords() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);

        int totalDeleted = 0;
        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            int deleted = self.deleteOneBatch(cutoff);
            totalDeleted += deleted;

            // A short batch means the backlog is drained; stop rather than
            // spending another round trip to confirm it.
            if (deleted < batchSize) {
                break;
            }
        }

        if (totalDeleted > 0) {
            log.info("Deleted {} idempotency record(s) older than {} days", totalDeleted, retentionDays);
        }
    }

    /**
     * One bounded delete, in its own transaction, so a large backlog is
     * cleared in small committed steps instead of one long-held lock on a
     * table that live checkout inserts into.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteOneBatch(LocalDateTime cutoff) {
        return idempotencyRecordRepository.deleteExpiredBatch(cutoff, batchSize);
    }

    /** Exposed for tests asserting the retention boundary. */
    public int getRetentionDays() {
        return retentionDays;
    }
}
