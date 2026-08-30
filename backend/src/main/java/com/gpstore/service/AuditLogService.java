package com.gpstore.service;

import com.gpstore.config.ClientIpResolver;
import com.gpstore.entity.AuditLog;
import com.gpstore.repository.AuditLogRepository;
import com.gpstore.security.AuthenticatedUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

@Service
public class AuditLogService {

    private final AuditLogRepository repository;
    private final ClientIpResolver clientIpResolver;

    public AuditLogService(AuditLogRepository repository, ClientIpResolver clientIpResolver) {
        this.repository = repository;
        this.clientIpResolver = clientIpResolver;
    }

    /**
     * Records who did what, to what, and when - for every financially or
     * security-sensitive action (order status changes, cancellations, refunds,
     * coupon creation). Never throws: a failure to log must never break the
     * actual business operation it's recording. Captures the actor
     * automatically from the security context when one exists, and stays null
     * (not a fabricated value) for system-triggered actions with no human actor.
     */
    public void log(String action, String entityType, Long entityId, String details) {
        try {
            AuditLog entry = new AuditLog();

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
                entry.setActorCustomerId(user.getCustomerId());
                entry.setActorEmail(user.getEmail());
                entry.setActorRole(user.getRole());
            }

            entry.setAction(action);
            entry.setEntityType(entityType);
            entry.setEntityId(entityId);
            // Client IP appended when a real HTTP request is in progress - a
            // scheduled job (like the delivery-guarantee or payment-expiry
            // checks) has no request at all, so this stays absent for those,
            // which is correct rather than fabricated.
            entry.setDetails(details + clientIpSuffix());
            entry.setOccurredAt(LocalDateTime.now());

            repository.save(entry);
        } catch (Exception ex) {
            // Deliberately swallowed - audit logging is observability, not a
            // business rule. A logging failure must never roll back or block
            // the real operation (e.g. a refund) it's trying to record.
        }
    }

    private String clientIpSuffix() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return "";
            }
            String ip = clientIpResolver.resolve(attrs.getRequest());
            return ip == null ? "" : ", clientIp=" + ip;
        } catch (Exception ex) {
            return "";
        }
    }

    public Page<AuditLog> getForEntity(String entityType, Long entityId, Pageable pageable) {
        return repository.findByEntityTypeAndEntityIdOrderByOccurredAtDesc(entityType, entityId, pageable);
    }

    public Page<AuditLog> getForActor(Long actorCustomerId, Pageable pageable) {
        return repository.findByActorCustomerIdOrderByOccurredAtDesc(actorCustomerId, pageable);
    }

    public Page<AuditLog> getAll(Pageable pageable) {
        return repository.findAllByOrderByOccurredAtDesc(pageable);
    }
}
