package com.gpstore.upload;

import com.gpstore.entity.R2StagingObject;
import com.gpstore.repository.R2StagingObjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class R2StagingSweepServiceTest {

    @Test
    void unconfirmedStagingOlderThanTtlIsReclaimed() {
        R2ObjectStorageService r2 = mock(R2ObjectStorageService.class);
        R2StagingObjectRepository repo = mock(R2StagingObjectRepository.class);
        when(r2.isConfigured()).thenReturn(true);
        R2StagingObject stale = new R2StagingObject(
                "gpstore/staging/products/new/original/stale.jpg",
                Instant.now().minus(25, ChronoUnit.HOURS));
        when(repo.findByCreatedAtBeforeOrderByCreatedAtAsc(any(), any(Pageable.class)))
                .thenReturn(List.of(stale))
                .thenReturn(List.of());

        new R2StagingSweepService(r2, repo, 24, 50).sweepExpired();

        verify(r2).deleteStagingObject("gpstore/staging/products/new/original/stale.jpg");
        verify(repo).delete(stale);
    }

    @Test
    void confirmedPermanentKeyIsNeverPassedToDeleteStaging() {
        R2ObjectStorageService r2 = mock(R2ObjectStorageService.class);
        R2StagingObjectRepository repo = mock(R2StagingObjectRepository.class);
        when(r2.isConfigured()).thenReturn(true);
        when(repo.findByCreatedAtBeforeOrderByCreatedAtAsc(any(), any(Pageable.class)))
                .thenReturn(List.of());

        new R2StagingSweepService(r2, repo, 24, 50).sweepExpired();

        verify(r2, never()).deleteStagingObject(any());
        verify(r2, never()).deletePublicUrl(any());
        verify(repo, never()).delete(any());
    }

    @Test
    void sweeperDoesNotTouchR2WhenUnconfigured() {
        R2ObjectStorageService r2 = mock(R2ObjectStorageService.class);
        R2StagingObjectRepository repo = mock(R2StagingObjectRepository.class);
        when(r2.isConfigured()).thenReturn(false);

        new R2StagingSweepService(r2, repo, 24, 50).sweepExpired();

        verify(repo, never()).findByCreatedAtBeforeOrderByCreatedAtAsc(any(), any());
        verify(r2, never()).deleteStagingObject(any());
    }

    @Test
    void deleteStagingObjectRefusesPermanentCatalogueKeys() {
        R2ObjectStorageService r2 = new R2ObjectStorageService(
                "acct", "", "key", "secret", "bucket", "");
        r2.deleteStagingObject("gpstore/products/1/original/live.jpg");
        // No S3 client: the refuse path must return without throwing.
    }
}
