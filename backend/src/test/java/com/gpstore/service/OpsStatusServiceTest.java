package com.gpstore.service;

import com.gpstore.repository.OpsBackupRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpsStatusServiceTest {

    @TempDir
    Path tempDir;

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
}
