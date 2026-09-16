package com.gpstore.platform;

import com.gpstore.config.ClientIpResolver;
import com.gpstore.entity.AuditLog;
import com.gpstore.repository.AuditLogRepository;
import com.gpstore.service.AuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogTenantIsolationTest {

    @Mock private AuditLogRepository repository;
    @Mock private ClientIpResolver clientIpResolver;

    @AfterEach
    void clearScope() {
        TenantContext.clear();
    }

    @Test
    void merchantAuditReadsArePredicatedByTheAuthenticatedShopScope() {
        var service = new AuditLogService(repository, clientIpResolver);
        var page = PageRequest.of(0, 20);
        when(repository.findByShopIdOrderByOccurredAtDesc(41L, page)).thenReturn(Page.empty());

        TenantContext.runWithin(TenantScope.ofShop(41L), () -> service.getAll(page));

        verify(repository).findByShopIdOrderByOccurredAtDesc(41L, page);
        verify(repository, never()).findAllByOrderByOccurredAtDesc(any());
    }

    @Test
    void platformAuditReadsMayUseTheCompleteAppendStream() {
        var service = new AuditLogService(repository, clientIpResolver);
        var page = PageRequest.of(0, 20);
        when(repository.findAllByOrderByOccurredAtDesc(page)).thenReturn(Page.empty());

        TenantContext.runWithin(TenantScope.platform(), () -> service.getAll(page));

        verify(repository).findAllByOrderByOccurredAtDesc(page);
        verify(repository, never()).findByShopIdOrderByOccurredAtDesc(any(), any());
    }

    @Test
    void shopAuditWritesAreStampedFromScopeNotCallerInput() {
        var service = new AuditLogService(repository, clientIpResolver);
        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        when(repository.save(any(AuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TenantContext.runWithin(TenantScope.ofShop(73L),
                () -> service.log("TEST", "Order", 9L, 2L, 999L,
                        null, null, null, "safe context"));

        verify(repository).save(saved.capture());
        assertEquals(73L, saved.getValue().getShopId());
    }

    @Test
    void entityHistoryCannotCrossTheShopBoundary() {
        var service = new AuditLogService(repository, clientIpResolver);
        var page = PageRequest.of(0, 20);
        when(repository.findByShopIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
                5L, "Order", 99L, page)).thenReturn(Page.empty());

        TenantContext.runWithin(TenantScope.ofShop(5L),
                () -> service.getForEntity("Order", 99L, page));

        verify(repository).findByShopIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
                5L, "Order", 99L, page);
        verify(repository, never()).findByEntityTypeAndEntityIdOrderByOccurredAtDesc(
                eq("Order"), eq(99L), any());
    }
}
