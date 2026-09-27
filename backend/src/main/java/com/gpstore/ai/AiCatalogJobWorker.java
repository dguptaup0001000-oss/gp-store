package com.gpstore.ai;

import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantScope;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded background drain for catalogue media/document extraction jobs.
 * SKIP LOCKED in the service makes this safe across multiple backend replicas.
 */
@Component
public class AiCatalogJobWorker {
    private static final int MAX_PER_RUN = 10;
    private final AiCatalogService catalog;

    public AiCatalogJobWorker(AiCatalogService catalog) {
        this.catalog = catalog;
    }

    @Scheduled(fixedDelayString = "${marketplace.ai.catalog-worker-delay-ms:5000}")
    @SchedulerLock(name = "aiCatalogExtractionJobs",
            lockAtMostFor = "PT2M", lockAtLeastFor = "PT1S")
    public void process() {
        TenantContext.runWithin(TenantScope.platform(), () -> {
            for (int i = 0; i < MAX_PER_RUN; i++) {
                if (!catalog.processNextQueuedJob()) break;
            }
        });
    }
}
