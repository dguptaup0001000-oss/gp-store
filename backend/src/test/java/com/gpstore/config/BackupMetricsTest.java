package com.gpstore.config;

import com.gpstore.service.OpsStatusService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackupMetricsTest {

    @Test
    void healthyBackupExportsOneAndZero() {
        OpsStatusService ops = mock(OpsStatusService.class);
        when(ops.backupAlert()).thenReturn(OpsStatusService.BackupAlert.HEALTHY);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new BackupMetrics(registry, ops);
        assertEquals(1.0, registry.get("gpstore.backup.healthy").gauge().value());
        assertEquals(0.0, registry.get("gpstore.backup.alert_code").gauge().value());
    }

    @Test
    void failedBackupExportsZeroAndTwo() {
        OpsStatusService ops = mock(OpsStatusService.class);
        when(ops.backupAlert()).thenReturn(OpsStatusService.BackupAlert.FAILED);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new BackupMetrics(registry, ops);
        assertEquals(0.0, registry.get("gpstore.backup.healthy").gauge().value());
        assertEquals(2.0, registry.get("gpstore.backup.alert_code").gauge().value());
    }
}
