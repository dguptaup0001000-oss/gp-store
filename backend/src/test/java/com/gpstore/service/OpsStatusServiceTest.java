package com.gpstore.service;

import com.gpstore.entity.OpsBackupRun;
import com.gpstore.repository.OpsBackupRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpsStatusServiceTest {

    @TempDir
    Path tempDir;

    private OpsBackupRun run(String status, Instant takenAt) {
        return new OpsBackupRun(takenAt, "gpstore-test.dump", 100L, "abc", status, "ok");
    }

    @Test
    void redisPingFailureIsUnhealthyWithoutLeakingTheException() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(redis.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenThrow(new RuntimeException("Connection refused to redis:6379 AUTH secret"));

        OpsBackupRunRepository repo = mock(OpsBackupRunRepository.class);
        when(repo.findFirstByStatusOrderByTakenAtDesc(any())).thenReturn(Optional.empty());

        OpsStatusService service = new OpsStatusService(repo, 26, "", redis);
        Map<String, Object> status = service.redisStatus();
        assertEquals(false, status.get("healthy"));
        assertEquals("PING failed.", status.get("reason"));
        assertFalse(status.toString().toLowerCase().contains("secret"));
        assertFalse(status.toString().toLowerCase().contains("auth"));
    }

    @Test
    void diskOnARealDirectoryIsHealthy() {
        OpsBackupRunRepository repo = mock(OpsBackupRunRepository.class);
        OpsStatusService service = new OpsStatusService(repo, 26, tempDir.toString(), null);
        Map<String, Object> disk = service.diskStatus();
        assertEquals(true, disk.get("configured"));
        assertEquals(true, disk.get("healthy"));
        assertFalse(disk.containsKey("path"), "filesystem paths must not leak to the admin API");
    }

    @Test
    void unmountedVolumeIsReportedNotConfigured() {
        OpsBackupRunRepository repo = mock(OpsBackupRunRepository.class);
        OpsStatusService service = new OpsStatusService(repo, 26, "", null);
        Map<String, Object> disk = service.diskStatus();
        assertEquals(false, disk.get("configured"));
        assertEquals(true, disk.get("healthy"));
    }

    @Test
    void successfulFreshBackupIsHealthy() {
        OpsBackupRun success = run("SUCCESS", Instant.now().minus(2, ChronoUnit.HOURS));
        OpsBackupRunRepository repo = mock(OpsBackupRunRepository.class);
        when(repo.findFirstByStatusOrderByTakenAtDesc("SUCCESS")).thenReturn(Optional.of(success));
        when(repo.findTop20ByOrderByTakenAtDesc()).thenReturn(List.of(success));

        OpsStatusService service = new OpsStatusService(repo, 26, "", null);
        assertEquals(OpsStatusService.BackupAlert.HEALTHY, service.backupAlert());
        Map<String, Object> body = service.backupStatus();
        assertEquals(true, body.get("healthy"));
        assertEquals("HEALTHY", body.get("alert"));
        assertFalse(body.toString().toLowerCase().contains("password"));
    }

    @Test
    void failedAttemptIsUnhealthyEvenWhenAnOlderSuccessExists() {
        OpsBackupRun success = run("SUCCESS", Instant.now().minus(1, ChronoUnit.HOURS));
        OpsBackupRun failure = run("FAILURE", Instant.now().minus(5, ChronoUnit.MINUTES));
        OpsBackupRunRepository repo = mock(OpsBackupRunRepository.class);
        when(repo.findFirstByStatusOrderByTakenAtDesc("SUCCESS")).thenReturn(Optional.of(success));
        when(repo.findTop20ByOrderByTakenAtDesc()).thenReturn(List.of(failure, success));

        OpsStatusService service = new OpsStatusService(repo, 26, "", null);
        assertEquals(OpsStatusService.BackupAlert.FAILED, service.backupAlert());
        Map<String, Object> body = service.backupStatus();
        assertEquals(false, body.get("healthy"));
        assertEquals("FAILED", body.get("alert"));
        assertTrue(body.get("reason").toString().contains("failed"));
    }

    @Test
    void staleSuccessIsUnhealthy() {
        OpsBackupRun success = run("SUCCESS", Instant.now().minus(40, ChronoUnit.HOURS));
        OpsBackupRunRepository repo = mock(OpsBackupRunRepository.class);
        when(repo.findFirstByStatusOrderByTakenAtDesc("SUCCESS")).thenReturn(Optional.of(success));
        when(repo.findTop20ByOrderByTakenAtDesc()).thenReturn(List.of(success));

        OpsStatusService service = new OpsStatusService(repo, 26, "", null);
        assertEquals(OpsStatusService.BackupAlert.STALE, service.backupAlert());
        assertEquals(false, service.backupStatus().get("healthy"));
    }

    @Test
    void missingBackupIsUnhealthy() {
        OpsBackupRunRepository repo = mock(OpsBackupRunRepository.class);
        when(repo.findFirstByStatusOrderByTakenAtDesc(any())).thenReturn(Optional.empty());
        when(repo.findTop20ByOrderByTakenAtDesc()).thenReturn(List.of());

        OpsStatusService service = new OpsStatusService(repo, 26, "", null);
        assertEquals(OpsStatusService.BackupAlert.MISSING, service.backupAlert());
        assertEquals(false, service.backupStatus().get("healthy"));
    }

    @Test
    void successAfterFailureRecovers() {
        OpsBackupRun recovered = run("SUCCESS", Instant.now().minus(10, ChronoUnit.MINUTES));
        OpsBackupRun failure = run("FAILURE", Instant.now().minus(2, ChronoUnit.HOURS));
        OpsBackupRunRepository repo = mock(OpsBackupRunRepository.class);
        when(repo.findFirstByStatusOrderByTakenAtDesc("SUCCESS")).thenReturn(Optional.of(recovered));
        when(repo.findTop20ByOrderByTakenAtDesc()).thenReturn(List.of(recovered, failure));

        OpsStatusService service = new OpsStatusService(repo, 26, "", null);
        assertEquals(OpsStatusService.BackupAlert.HEALTHY, service.backupAlert());
        assertEquals(true, service.backupStatus().get("healthy"));
    }
}
