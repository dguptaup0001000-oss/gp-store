package com.gpstore.controller;

import com.gpstore.entity.AuditLog;
import com.gpstore.service.AuditLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    // Admin only (enforced in SecurityConfig) - full audit trail, newest first.
    @GetMapping
    public Page<AuditLog> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        return auditLogService.getAll(pageable);
    }

    // Admin only - full history for a specific entity, e.g. every event on one order.
    @GetMapping("/entity/{entityType}/{entityId}")
    public Page<AuditLog> getForEntity(
            @PathVariable String entityType,
            @PathVariable Long entityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        return auditLogService.getForEntity(entityType, entityId, pageable);
    }
}
