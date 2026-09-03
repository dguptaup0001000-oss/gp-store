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
    private final com.gpstore.otp.OtpConfigurationStatus otpStatus;

    public OpsStatusController(OpsStatusService opsStatusService,
                               TlsCertificateProbe tlsCertificateProbe,
                               com.gpstore.otp.OtpConfigurationStatus otpStatus) {
        this.otpStatus = otpStatus;
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
        // WHY OTP IS AN OPS READING. When it is misconfigured, every login
        // and every password reset fails with a message that deliberately
        // says nothing, and the only explanation is one WARN line on the
        // server. This is the screen the shopkeeper can actually reach.
        body.put("otp", otpStatus.status());
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
