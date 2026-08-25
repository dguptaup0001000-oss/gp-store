package com.gpstore.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * A durable record of business-critical work that must happen AFTER an order
 * commits, but must not be lost if this process dies.
 *
 * Why this exists: post-order side effects were handed to an in-memory
 * ThreadPoolExecutor. That executor is bounded and has proper backpressure,
 * so it is not a memory risk - but it is not durable. Anything still queued
 * or mid-flight when the JVM stops is gone with no trace. On this
 * deployment that is not a rare event: the service restarts on every
 * deploy, and a process crash or OOM is always possible.
 * An order placed seconds before either could keep its money and its stock
 * decrement while permanently losing its invoice - a GST/accounting record
 * the business is legally required to have.
 *
 * The outbox pattern fixes exactly that: the event row is INSERTed inside
 * the same transaction as the order itself, so it either commits with the
 * order or does not exist at all. There is no window where an order exists
 * without its follow-up work recorded. A worker then picks the row up
 * afterwards, retrying until it succeeds.
 *
 * Delivery is AT-LEAST-ONCE, not exactly-once: a handler can succeed and the
 * process die before the row is marked processed, so it will run again.
 * Every handler must therefore be idempotent - see
 * OutboxWorker.handleOrderPlaced.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    public enum Status {
        /** Waiting to be processed, or waiting for its next retry. */
        PENDING,
        /** Successfully handled - kept for a while for auditability, then purged. */
        PROCESSED,
        /**
         * Gave up after maxAttempts. Deliberately NOT deleted: a failed
         * invoice is a real business problem someone has to resolve, and
         * silently dropping it is how it goes unnoticed. These stay
         * queryable so they can be found and replayed.
         */
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** What kind of thing this event is about, e.g. "Order". */
    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    /** Which one - the order id for an ORDER_PLACED event. */
    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    /** What happened, e.g. "ORDER_PLACED". Dispatched on by the worker. */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "attempts", nullable = false)
    private Integer attempts = 0;

    /**
     * When this row becomes eligible for processing. Set to now on insert,
     * and pushed forward with exponential backoff after each failure so a
     * consistently failing event does not spin the worker.
     */
    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    /** Truncated - this is for a human debugging a stuck event, not a full trace. */
    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    public OutboxEvent() {
    }

    public static OutboxEvent of(String aggregateType, Long aggregateId, String eventType) {
        OutboxEvent event = new OutboxEvent();
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.eventType = eventType;
        event.status = Status.PENDING;
        event.attempts = 0;
        event.createdAt = LocalDateTime.now();
        event.nextAttemptAt = event.createdAt;
        return event;
    }

    public Long getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public void setAggregateType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    public Long getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(Long aggregateId) {
        this.aggregateId = aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }

    public LocalDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(LocalDateTime nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(LocalDateTime processedAt) {
        this.processedAt = processedAt;
    }
}
