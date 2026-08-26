package com.gpstore.controller;

import com.gpstore.service.OpsStatusService;
import com.gpstore.service.TlsCertificateProbe;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operator status. Admin-only (SecurityConfig {@code /api/admin/**}).
 *
 * Does not dump environment variables, connection strings, or file contents.
 */
@RestController
@RequestMapping("/api/admin/ops")
public class OpsStatusController {

    private final OpsStatusService opsStatusService;
    private final TlsCertificateProbe tlsCertificateProbe;

    public OpsStatusController(OpsStatusService opsStatusService, TlsCertificateProbe tlsCertificateProbe) {
        this.opsStatusService = opsStatusService;
        this.tlsCertificateProbe = tlsCertificateProbe;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("backups", opsStatusService.backupStatus());
        body.put("redis", opsStatusService.redisStatus());
        body.put("disk", opsStatusService.diskStatus());
        body.put("tls", tlsCertificateProbe.probe());
        return body;
    }

    @GetMapping("/backups")
    public Map<String, Object> backups() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", opsStatusService.backupStatus());
        body.put("recent", opsStatusService.recentRuns());
        return body;
    }
}
