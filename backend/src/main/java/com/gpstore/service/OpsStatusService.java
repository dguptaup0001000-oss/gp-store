package com.gpstore.service;

import com.gpstore.entity.OpsBackupRun;
import com.gpstore.repository.OpsBackupRunRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only view of backup heartbeats written by the sidecar.
 *
 * Never exposes file paths outside the backup volume, credentials, or dump
 * contents. Admin-only at the HTTP layer.
 */
@Service
public class OpsStatusService {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILURE = "FAILURE";

    private final OpsBackupRunRepository backupRunRepository;
    private final Duration maxAge;

    public OpsStatusService(
            OpsBackupRunRepository backupRunRepository,
            @Value("${ops.backup.max-age-hours:26}") int maxAgeHours) {
        this.backupRunRepository = backupRunRepository;
        this.maxAge = Duration.ofHours(Math.max(1, maxAgeHours));
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
