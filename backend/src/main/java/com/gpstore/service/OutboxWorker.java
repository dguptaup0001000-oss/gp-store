package com.gpstore.service;

import com.gpstore.entity.OutboxEvent;
import com.gpstore.repository.OutboxEventRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Drains the outbox: picks up durable post-order work and runs it until it
 * succeeds.
 *
 * ORDERING OF CONCERNS, which is the whole design:
 *
 *   1. Claim a bounded batch and mark it, in a SHORT transaction. Row locks
 *      are taken with FOR UPDATE SKIP LOCKED and released the moment that
 *      transaction commits.
 *   2. Run the handlers with NO database lock held. This matters because
 *      handlers do slow things - invoice generation, and previously network
 *      calls - and holding a lock across those is how a background job ends
 *      up blocking live checkout on the same rows.
 *   3. Record the outcome in a separate short transaction per event.
 *
 * Delivery is AT-LEAST-ONCE. If the process dies between a handler
 * succeeding and step 3 recording it, the event runs again on the next
 * sweep. Handlers are therefore written to be idempotent rather than
 * assuming they run once - see handleOrderPlaced.
 */
@Service
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

    public static final String AGGREGATE_ORDER = "Order";
    public static final String EVENT_ORDER_PLACED = "ORDER_PLACED";
    public static final String EVENT_ORDER_CANCELLED = "ORDER_CANCELLED";

    /**
     * A cancelled prepaid order owes its customer money back.
     *
     * Durable rather than best-effort, and for the plainest reason there
     * is: it is somebody's money. If Cashfree is down when an order is
     * cancelled, the refund has to survive that and go out later, not
     * evaporate into a log line nobody reads.
     */
    public static final String EVENT_REFUND_REQUESTED = "REFUND_REQUESTED";

    private final OutboxEventRepository outboxEventRepository;
    private final InvoiceService invoiceService;
    private final DeliveryService deliveryService;
    private final PaymentService paymentService;
    private final AuditLogService auditLogService;
    private final OutboxWorker self;
    private final int batchSize;
    private final int maxBatchesPerRun;
    private final int maxAttempts;
    private final int processedRetentionDays;

    public OutboxWorker(
            OutboxEventRepository outboxEventRepository,
            InvoiceService invoiceService,
            @Lazy DeliveryService deliveryService,
            // @Lazy for the same reason as DeliveryService: PaymentService
            // reaches back into order handling, and eager wiring closes the
            // loop into a circular dependency at startup.
            @Lazy PaymentService paymentService,
            AuditLogService auditLogService,
            // Own proxy, so the per-event transactions below actually apply -
            // a plain this.call() bypasses Spring's transactional proxy and
            // would silently collapse the careful transaction boundaries this
            // class depends on.
            @Lazy OutboxWorker self,
            @Value("${outbox.batch-size:50}") int batchSize,
            @Value("${outbox.max-batches-per-run:20}") int maxBatchesPerRun,
            @Value("${outbox.max-attempts:10}") int maxAttempts,
            @Value("${outbox.processed-retention-days:7}") int processedRetentionDays) {

        this.outboxEventRepository = outboxEventRepository;
        this.invoiceService = invoiceService;
        this.deliveryService = deliveryService;
        this.paymentService = paymentService;
        this.auditLogService = auditLogService;
        this.self = self;
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
        this.maxAttempts = maxAttempts;
        this.processedRetentionDays = processedRetentionDays;
    }

    /**
     * Every 30 seconds. Frequent enough that an invoice appears promptly
     * after checkout, infrequent enough to be negligible load when the
     * outbox is empty (one indexed query returning nothing).
     */
    @Scheduled(fixedDelayString = "${outbox.drain-interval-ms:30000}", initialDelayString = "${outbox.initial-delay-ms:5000}")
    @SchedulerLock(name = "outboxWorker", lockAtMostFor = "10m", lockAtLeastFor = "5s")
    public void drain() {
        int processed = 0;

        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            List<Long> claimed = self.claimBatch();
            if (claimed.isEmpty()) {
                break;
            }

            for (Long eventId : claimed) {
                // No DB lock is held here - the claim transaction has already
                // committed. Each event's outcome is recorded separately.
                self.processClaimedEvent(eventId);
                processed++;
            }

            if (claimed.size() < batchSize) {
                break;
            }
        }

        if (processed > 0) {
            log.info("Outbox: processed {} event(s)", processed);
        }
    }

    /**
     * Short transaction: take the row locks, hand back only the ids, commit.
     *
     * Returns ids rather than entities so nothing detached is carried into
     * the processing step - the handler re-reads what it needs in its own
     * transaction, which is also what makes a retry see current state rather
     * than a stale snapshot from when the batch was claimed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Long> claimBatch() {
        List<OutboxEvent> due = outboxEventRepository.claimDueBatch(LocalDateTime.now(), batchSize);
        List<Long> ids = new ArrayList<>(due.size());
        for (OutboxEvent event : due) {
            ids.add(event.getId());
        }
        return ids;
    }

    /**
     * Runs one event's handler and records the outcome.
     *
     * NOT transactional itself, on purpose, and this is load-bearing. The
     * handler's own services are transactional and can throw - and a
     * RuntimeException raised inside a @Transactional method marks the
     * SURROUNDING transaction rollback-only. If the handler ran inside this
     * method's transaction, catching that exception would not be enough: the
     * status update would then fail at commit with
     * UnexpectedRollbackException, and the event would be left PENDING with
     * its attempt count rolled back - a permanently failing event retried
     * forever, at full speed, never dead-lettering.
     *
     * So the work is split into three parts with distinct transactions:
     * count the attempt, run the handler with no transaction of ours around
     * it, then record the outcome. That also keeps the promise made in this
     * class's header - no database lock of ours is held while a handler
     * does slow work.
     */
    public void processClaimedEvent(Long eventId) {
        OutboxEvent event = outboxEventRepository.findById(eventId).orElse(null);
        if (event == null || event.getStatus() != OutboxEvent.Status.PENDING) {
            return;
        }

        int attempt = self.countAttempt(eventId);

        try {
            dispatch(event.getEventType(), event.getAggregateId());
            self.markProcessed(eventId);

        } catch (Exception ex) {
            // Never rethrow: one poisonous event must not abort the rest of
            // the sweep.
            String message = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            String trimmed = message.length() > 1000 ? message.substring(0, 1000) : message;

            if (attempt >= maxAttempts) {
                self.markFailed(eventId, trimmed);
                // Audited rather than only logged: a permanently failed
                // ORDER_PLACED event means an order with no invoice, which is
                // an accounting problem somebody has to act on, not just a
                // line in a log that rotates away.
                auditLogService.log("OUTBOX_EVENT_FAILED", event.getAggregateType(), event.getAggregateId(),
                        event.getEventType() + " gave up after " + attempt + " attempts: " + trimmed);
                log.error("Outbox event {} ({} for {} {}) FAILED permanently after {} attempts: {}",
                        eventId, event.getEventType(), event.getAggregateType(),
                        event.getAggregateId(), attempt, trimmed);
            } else {
                // Exponential backoff, capped. A transient failure (the DB
                // briefly unavailable, a dependency restarting) should retry
                // soon; a persistent one should back off rather than burn the
                // remaining attempts in seconds.
                long delaySeconds = Math.min(300L, (long) Math.pow(2, attempt));
                self.scheduleRetry(eventId, trimmed, delaySeconds);
                log.warn("Outbox event {} attempt {} failed, retrying in {}s: {}",
                        eventId, attempt, delaySeconds, trimmed);
            }
        }
    }

    /**
     * Counts the attempt in its own committed transaction BEFORE the handler
     * runs, so the increment survives even if the handler then fails in a
     * way that would have rolled it back. Without this, a consistently
     * failing event never accumulates attempts and never dead-letters.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int countAttempt(Long eventId) {
        OutboxEvent event = outboxEventRepository.findById(eventId).orElseThrow();
        event.setAttempts(event.getAttempts() + 1);
        outboxEventRepository.save(event);
        return event.getAttempts();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessed(Long eventId) {
        OutboxEvent event = outboxEventRepository.findById(eventId).orElseThrow();
        event.setStatus(OutboxEvent.Status.PROCESSED);
        event.setProcessedAt(LocalDateTime.now());
        event.setLastError(null);
        outboxEventRepository.save(event);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long eventId, String error) {
        OutboxEvent event = outboxEventRepository.findById(eventId).orElseThrow();
        event.setStatus(OutboxEvent.Status.FAILED);
        event.setLastError(error);
        outboxEventRepository.save(event);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void scheduleRetry(Long eventId, String error, long delaySeconds) {
        OutboxEvent event = outboxEventRepository.findById(eventId).orElseThrow();
        event.setLastError(error);
        event.setNextAttemptAt(LocalDateTime.now().plusSeconds(delaySeconds));
        outboxEventRepository.save(event);
    }

    private void dispatch(String eventType, Long aggregateId) {
        if (EVENT_ORDER_PLACED.equals(eventType)) {
            handleOrderPlaced(aggregateId);
            return;
        }
        if (EVENT_ORDER_CANCELLED.equals(eventType)) {
            handleOrderCancelled(aggregateId);
            return;
        }
        if (EVENT_REFUND_REQUESTED.equals(eventType)) {
            handleRefundRequested(aggregateId);
            return;
        }
        // An unknown type is a code/data mismatch, not a transient fault -
        // let it fail and be retried/dead-lettered rather than silently
        // marking work done that nothing actually performed.
        throw new IllegalStateException("No handler for outbox event type: " + eventType);
    }

    /**
     * IDEMPOTENT by construction, because delivery is at-least-once.
     *
     * generateForOrderIfAbsent rather than generateForOrder: the latter
     * signals "already exists" by throwing, which is correct for an admin
     * clicking twice but wrong here. A retry that finds its own previous
     * success would read that exception as failure and keep retrying until
     * it dead-lettered - reporting an order that HAS an invoice as
     * permanently broken. Catching the exception is not sufficient either,
     * since it would already have marked the surrounding transaction
     * rollback-only.
     *
     * Delivery assignment stays best-effort: autoAssignBestEffort swallows
     * its own failures (no partner free right now is normal, not an error),
     * so it can never fail the invoice half of this event.
     */
    private void handleOrderPlaced(Long orderId) {
        invoiceService.generateForOrderIfAbsent(orderId);
        deliveryService.autoAssignBestEffort(orderId);
    }

    /**
     * Cancels the order's invoice, if it has one.
     *
     * Durable rather than best-effort because this is accounting: a
     * cancelled order whose invoice is still active reads as a valid sale
     * for GST purposes, and nothing would report the discrepancy.
     *
     * IDEMPOTENT, as at-least-once delivery requires. cancelInvoice only
     * sets a status field, so re-running it is harmless - but the status is
     * checked first anyway to avoid a pointless UPDATE on every redelivery.
     * An order with no invoice is a no-op: it may never have had one, or its
     * ORDER_PLACED event may not have been processed yet, and neither is an
     * error.
     */
    private void handleOrderCancelled(Long orderId) {
        invoiceService.getInvoiceByOrderId(orderId).ifPresent(invoice -> {
            if (!"CANCELLED".equals(invoice.getStatus())) {
                invoiceService.cancelInvoice(invoice.getInvoiceId());
            }
        });
    }

    /**
     * Sends the refund a cancelled prepaid order promised its customer.
     *
     * NOT BEST-EFFORT, and not swallowed. If the provider is unreachable this
     * must throw, so the event is retried and eventually dead-lettered where
     * somebody can see it. A refund that quietly gave up would be the exact
     * failure this whole path exists to prevent: the shop believing the money
     * went back when it did not.
     *
     * IDEMPOTENT, as at-least-once delivery requires - sendRefundToProvider
     * returns without doing anything for a refund that is already settled,
     * already sent, or was never owed. Its refund id is derived from the
     * payment, so even a send that does go out twice reaches the same refund
     * at the provider rather than paying the customer twice.
     */
    private void handleRefundRequested(Long orderId) {
        paymentService.sendRefundToProvider(orderId);
    }

    /**
     * Housekeeping: PROCESSED rows have served their purpose after a while.
     * FAILED rows are never touched here - see deleteProcessedBatch.
     */
    @Scheduled(fixedDelayString = "${outbox.purge-interval-ms:21600000}", initialDelayString = "${outbox.initial-delay-ms:5000}")
    @SchedulerLock(name = "outboxCleanup", lockAtMostFor = "30m", lockAtLeastFor = "1m")
    public void purgeOldProcessedEvents() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(processedRetentionDays);
        int total = 0;
        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            int deleted = self.purgeOneBatch(cutoff);
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("Outbox: purged {} processed event(s) older than {} days", total, processedRetentionDays);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeOneBatch(LocalDateTime cutoff) {
        return outboxEventRepository.deleteProcessedBatch(cutoff, batchSize);
    }
}
