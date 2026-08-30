package com.gpstore.upload;

import com.gpstore.entity.R2StagingObject;
import com.gpstore.repository.R2StagingObjectRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Deletes unconfirmed R2 staging objects after 24 hours. Keys are recorded
 * at sign time so this job never needs ListBucket.
 */
@Service
public class R2StagingSweepService {

    private static final Logger log = LoggerFactory.getLogger(R2StagingSweepService.class);

    private final R2ObjectStorageService r2;
    private final R2StagingObjectRepository stagingObjects;
    private final int ttlHours;
    private final int batchSize;

    public R2StagingSweepService(
            R2ObjectStorageService r2,
            R2StagingObjectRepository stagingObjects,
            @Value("${r2.staging-ttl-hours:24}") int ttlHours,
            @Value("${r2.staging-sweep-batch-size:50}") int batchSize) {
        this.r2 = r2;
        this.stagingObjects = stagingObjects;
        this.ttlHours = ttlHours;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${r2.staging-sweep-interval-ms:3600000}",
            initialDelayString = "${r2.staging-sweep-initial-delay-ms:120000}")
    @SchedulerLock(name = "r2StagingSweep", lockAtMostFor = "15m", lockAtLeastFor = "1m")
    public void sweepExpired() {
        if (!r2.isConfigured()) {
            return;
        }
        Instant cutoff = Instant.now().minus(ttlHours, ChronoUnit.HOURS);
        int deleted = 0;
        while (true) {
            List<R2StagingObject> batch = stagingObjects.findByCreatedAtBeforeOrderByCreatedAtAsc(
                    cutoff, PageRequest.of(0, batchSize));
            if (batch.isEmpty()) {
                break;
            }
            for (R2StagingObject row : batch) {
                r2.deleteStagingObject(row.getObjectKey());
                stagingObjects.delete(row);
                deleted++;
            }
            if (batch.size() < batchSize) {
                break;
            }
        }
        if (deleted > 0) {
            log.info("Reclaimed {} unconfirmed R2 staging object(s) older than {}h", deleted, ttlHours);
        }
    }
}
