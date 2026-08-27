package com.gpstore.config;

import com.gpstore.service.OpsStatusService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

/**
 * Backup health as Prometheus gauges. No APM. Scraped from the existing
 * admin-only {@code /v1/actuator/prometheus} endpoint.
 *
 * Backup status is intentionally NOT on public {@code /actuator/health}:
 * a failed dump must page operators without taking the shop off Traefik.
 */
@Configuration
public class BackupMetrics {

    public BackupMetrics(MeterRegistry registry, OpsStatusService opsStatusService) {
        Gauge.builder("gpstore.backup.healthy", opsStatusService,
                        ops -> ops.backupAlert() == OpsStatusService.BackupAlert.HEALTHY ? 1.0 : 0.0)
                .description("1 when the last backup attempt is SUCCESS and within the freshness window")
                .register(registry);

        Gauge.builder("gpstore.backup.alert_code", opsStatusService, ops -> switch (ops.backupAlert()) {
                    case HEALTHY -> 0.0;
                    case MISSING -> 1.0;
                    case FAILED -> 2.0;
                    case STALE -> 3.0;
                })
                .description("0=HEALTHY 1=MISSING 2=FAILED 3=STALE")
                .register(registry);
    }
}
