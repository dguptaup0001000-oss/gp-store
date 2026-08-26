package com.gpstore.service;

import com.gpstore.entity.OpsBackupRun;
import com.gpstore.repository.OpsBackupRunRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only view of backup heartbeats written by the sidecar, plus Redis
 * and backup-volume disk.
 *
 * Never exposes file paths outside the backup volume, credentials, or dump
 * contents. Admin-only at the HTTP layer.
 */
@Service
public class OpsStatusService {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILURE = "FAILURE";

    static final long DISK_MIN_FREE_BYTES = 512L * 1024L * 1024L;

    private final OpsBackupRunRepository backupRunRepository;
    private final Duration maxAge;
    private final String backupVolumePath;
    private final StringRedisTemplate redisTemplate;

    public OpsStatusService(
            OpsBackupRunRepository backupRunRepository,
            @Value("${ops.backup.max-age-hours:26}") int maxAgeHours,
            @Value("${ops.backup.volume-path:}") String backupVolumePath,
            @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.backupRunRepository = backupRunRepository;
        this.maxAge = Duration.ofHours(Math.max(1, maxAgeHours));
        this.backupVolumePath = backupVolumePath == null ? "" : backupVolumePath.trim();
        this.redisTemplate = redisTemplate;
    }

    public Map<String, Object> backupStatus() {
        OpsBackupRun lastOk = backupRunRepository
                .findFirstByStatusOrderByTakenAtDesc(STATUS_SUCCESS)
                .orElse(null);
        OpsBackupRun lastAny = backupRunRepository.findTop20ByOrderByTakenAtDesc()
                .stream()
                .findFirst()
                .orElse(null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configured", true);
        body.put("maxAgeHours", maxAge.toHours());
        if (lastOk == null) {
            body.put("healthy", false);
            body.put("reason", lastAny == null
                    ? "No backup has been recorded yet."
                    : "No successful backup has been recorded.");
            body.put("lastSuccessAt", null);
            body.put("lastAttempt", summarise(lastAny));
            return body;
        }

        Duration age = Duration.between(lastOk.getTakenAt(), Instant.now());
        boolean healthy = !age.isNegative() && age.compareTo(maxAge) <= 0;
        body.put("healthy", healthy);
        body.put("reason", healthy
                ? "Last successful backup is within the retention window."
                : "Last successful backup is older than " + maxAge.toHours() + " hours.");
        body.put("lastSuccessAt", lastOk.getTakenAt().toString());
        body.put("lastSuccess", summarise(lastOk));
        body.put("lastAttempt", summarise(lastAny));
        body.put("ageHours", Math.round(age.toMinutes() / 6.0) / 10.0);
        return body;
    }

    public Map<String, Object> redisStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (redisTemplate == null) {
            body.put("healthy", false);
            body.put("reason", "Redis client is not configured in this process.");
            return body;
        }
        RedisConnectionFactory factory = redisTemplate.getConnectionFactory();
        if (factory == null) {
            body.put("healthy", false);
            body.put("reason", "Redis connection factory is missing.");
            return body;
        }
        RedisConnection connection = null;
        try {
            connection = factory.getConnection();
            String pong = connection.ping();
            boolean ok = pong != null && !pong.isBlank();
            body.put("healthy", ok);
            body.put("reason", ok ? "PING succeeded." : "PING returned empty.");
            return body;
        } catch (RuntimeException unavailable) {
            body.put("healthy", false);
            body.put("reason", "PING failed.");
            return body;
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (RuntimeException ignored) {
                    // Closing a dead connection must not hide the PING result.
                }
            }
        }
    }

    /**
     * Disk remaining on the backup volume. Paths are not returned — operators
     * already know the volume is {@code gpstore_pg_backups}.
     */
    public Map<String, Object> diskStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (backupVolumePath.isEmpty()) {
            body.put("configured", false);
            body.put("healthy", true);
            body.put("reason", "Backup volume is not mounted on this process.");
            return body;
        }
        body.put("configured", true);
        Path path = Path.of(backupVolumePath);
        if (!Files.isDirectory(path)) {
            body.put("healthy", false);
            body.put("reason", "Backup volume path is not a directory.");
            return body;
        }
        try {
            FileStore store = Files.getFileStore(path);
            long usable = store.getUsableSpace();
            long total = store.getTotalSpace();
            body.put("usableBytes", usable);
            body.put("totalBytes", total);
            boolean healthy = usable >= DISK_MIN_FREE_BYTES;
            if (total > 0 && usable * 10 < total) {
                healthy = false;
            }
            body.put("healthy", healthy);
            body.put("reason", healthy
                    ? "Backup volume has enough free space."
                    : "Backup volume is low on free space (under 512 MiB or 10%).");
            return body;
        } catch (IOException e) {
            body.put("healthy", false);
            body.put("reason", "Could not read backup volume free space.");
            return body;
        }
    }

    public List<Map<String, Object>> recentRuns() {
        return backupRunRepository.findTop20ByOrderByTakenAtDesc().stream()
                .map(this::summarise)
                .toList();
    }

    private Map<String, Object> summarise(OpsBackupRun run) {
        if (run == null) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("takenAt", run.getTakenAt() != null ? run.getTakenAt().toString() : null);
        row.put("filename", run.getFilename());
        row.put("bytes", run.getBytes());
        row.put("sha256", run.getSha256());
        row.put("status", run.getStatus());
        row.put("detail", run.getDetail());
        return row;
    }
}
