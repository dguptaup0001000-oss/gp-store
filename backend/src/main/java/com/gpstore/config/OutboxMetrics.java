package com.gpstore.config;

import com.gpstore.entity.OutboxEvent;
import com.gpstore.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the four numbers needed to decide whether the outbox worker is
 * keeping up - BEFORE changing anything about how it runs.
 *
 * The worker processes claimed events one at a time. That is safe, and for a
 * single-store operation it is very likely sufficient, but "likely
 * sufficient" is not something to act on either way without evidence.
 * Parallelising it would add real complexity (ordering, contention, more
 * concurrent transactions holding order/invoice rows) to solve a problem
 * that may not exist. So these are published first, and the decision waits
 * on data.
 *
 * What to watch:
 *
 *  - outbox.pending      backlog size. A spike alone is not a problem; a
 *                        backlog that does not drain is.
 *  - outbox.oldest_pending_age_seconds
 *                        how far behind the worker is. This is the one to
 *                        alert on - a count can look bad during a harmless
 *                        burst, or look fine while one event is stuck for a
 *                        day, and only age distinguishes those.
 *  - outbox.failed       dead-lettered events. Should be zero. Anything
 *                        here means an order whose invoice never generated.
 *  - outbox.processed    completed events still inside the retention window,
 *                        which gives the throughput denominator.
 *
 * Gauges, not counters: each is a current-state question, and a gauge is
 * read on scrape rather than requiring the app to track deltas itself.
 * Registered against the existing Prometheus registry, so this needs no new
 * infrastructure - it appears at /v1/actuator/prometheus alongside
 * everything else.
 */
@Configuration
public class OutboxMetrics {

    public OutboxMetrics(MeterRegistry registry, OutboxEventRepository outboxEventRepository) {
        registry.gauge("outbox.pending", outboxEventRepository,
                repo -> repo.countByStatus(OutboxEvent.Status.PENDING));

        registry.gauge("outbox.failed", outboxEventRepository,
                repo -> repo.countByStatus(OutboxEvent.Status.FAILED));

        registry.gauge("outbox.processed", outboxEventRepository,
                repo -> repo.countByStatus(OutboxEvent.Status.PROCESSED));

        registry.gauge("outbox.oldest_pending_age_seconds", outboxEventRepository, repo -> {
            Double age = repo.findOldestPendingAgeSeconds();
            // 0, not NaN, when the queue is empty: an empty outbox is "zero
            // seconds behind", and a NaN would show as a gap in the graph
            // that reads like a scrape failure rather than a healthy queue.
            return age == null ? 0d : age;
        });
    }
}
