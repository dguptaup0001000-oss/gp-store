package com.gpstore.upload;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deletes leftover R2 objects after the database already points at the new
 * image. Cloudinary URLs are left untouched until an operator confirms the
 * copy-to-R2 job.
 */
@Service
public class CatalogImageCleanup {

    private final R2ObjectStorageService r2;

    public CatalogImageCleanup(R2ObjectStorageService r2) {
        this.r2 = r2;
    }

    public void deleteReplaced(String previousUrl, String nextUrl) {
        if (previousUrl == null || previousUrl.isBlank()) {
            return;
        }
        if (previousUrl.equals(nextUrl)) {
            return;
        }
        r2.deletePublicUrl(previousUrl);
    }

    public void deleteRemoved(Collection<String> previousUrls, Collection<String> nextUrls) {
        if (previousUrls == null || previousUrls.isEmpty()) {
            return;
        }
        Set<String> keep = nextUrls == null ? Set.of() : new HashSet<>(nextUrls);
        for (String url : previousUrls) {
            if (url != null && !keep.contains(url)) {
                r2.deletePublicUrl(url);
            }
        }
    }

    /** Drop unused objects only after the gallery write has committed. */
    public void deleteRemovedAfterCommit(Collection<String> previousUrls, Collection<String> nextUrls) {
        List<String> previous = previousUrls == null ? List.of() : List.copyOf(previousUrls);
        List<String> next = nextUrls == null ? List.of() : List.copyOf(nextUrls);
        runAfterCommit(() -> deleteRemoved(previous, next));
    }

    public void deleteReplacedAfterCommit(String previousUrl, String nextUrl) {
        runAfterCommit(() -> deleteReplaced(previousUrl, nextUrl));
    }

    private static void runAfterCommit(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }
}
