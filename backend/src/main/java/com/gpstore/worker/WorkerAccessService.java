package com.gpstore.worker;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.gpstore.entity.DeliveryPartner;
import com.gpstore.repository.DeliveryPartnerRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * The live "may this worker still be here?" check for the JWT filter.
 *
 * WHY EVERY REQUEST AND NOT JUST LOGIN. A worker token has no refresh token
 * and a shift-length life, so if access were only decided at sign-in, a rider
 * deleted or suspended at nine in the morning would keep working until the
 * evening. Re-checking here means the shop's decision lands on their next tap.
 *
 * TWO SECONDS OF CACHE, matching CustomerAccountStatusService, for the same
 * reason: long enough to collapse the burst of calls one screen makes into a
 * single query, short enough that "paused for an hour" is not a suggestion.
 * A cache that outlived the token would make this check pointless.
 */
@Service
public class WorkerAccessService {

    static final long TTL_MS = 2_000L;
    private static final long MAX_ENTRIES = 20_000L;

    private final DeliveryPartnerRepository repository;
    private final Cache<Long, WorkerAccess.Decision> cache;

    public WorkerAccessService(DeliveryPartnerRepository repository) {
        this.repository = repository;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMillis(TTL_MS))
                .maximumSize(MAX_ENTRIES)
                .build();
    }

    /**
     * Note the cached value is the DECISION, not the row: a suspension that
     * expires during the two-second window is re-evaluated on the next miss,
     * which is the same bound as every other status change here.
     */
    public WorkerAccess.Decision resolve(Long workerId) {
        if (workerId == null) {
            return WorkerAccess.check(null, LocalDateTime.now());
        }
        return cache.get(workerId, id ->
                WorkerAccess.check(repository.findById(id).orElse(null), LocalDateTime.now()));
    }

    /**
     * Called after the shop changes something about a worker, so the change
     * applies on the next request instead of up to {@link #TTL_MS} later.
     * Belt and braces: without it the worst case is still that bound.
     */
    public void invalidate(Long workerId) {
        if (workerId != null) {
            cache.invalidate(workerId);
        }
    }

    /** Convenience for the login path, which already holds the row. */
    public WorkerAccess.Decision check(DeliveryPartner worker) {
        return WorkerAccess.check(worker, LocalDateTime.now());
    }
}
