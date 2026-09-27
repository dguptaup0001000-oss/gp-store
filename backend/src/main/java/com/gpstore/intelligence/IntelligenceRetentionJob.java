package com.gpstore.intelligence;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Raw discovery data is temporary. Aggregated operational history remains
 * useful without retaining an individual's searches indefinitely.
 */
@Component
public class IntelligenceRetentionJob {
    private final JdbcTemplate jdbc;
    private final int retentionDays;

    public IntelligenceRetentionJob(
            JdbcTemplate jdbc,
            @Value("${marketplace.analytics.raw-retention-days:90}") int retentionDays) {
        this.jdbc = jdbc;
        this.retentionDays = Math.min(Math.max(retentionDays, 7), 365);
    }

    @Scheduled(cron = "${marketplace.analytics.cleanup-cron:0 25 3 * * *}")
    @SchedulerLock(name = "marketplaceIntelligenceRetention",
            lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void clean() {
        jdbc.update("""
                UPDATE demand_requests SET status='EXPIRED', updated_at=now()
                 WHERE status='OPEN' AND expires_at<=now()
                """);
        jdbc.update("""
                DELETE FROM marketplace_search_events
                 WHERE created_at < now() - (? * interval '1 day')
                """, retentionDays);
    }
}
