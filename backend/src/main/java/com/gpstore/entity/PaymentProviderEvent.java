package com.gpstore.entity;

import com.gpstore.enums.PaymentProvider;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * One row per webhook the gateway delivers, written before the event is
 * acted on.
 *
 * THIS IS THE DUPLICATE PROTECTION, and it is worth being precise about how.
 * Cashfree retries delivery until it receives a 2xx, so a retry is
 * indistinguishable from a first attempt except by its event id. Checking
 * "have I seen this?" and then applying the change is a check-then-act:
 * two deliveries arriving together can both pass the check.
 *
 * Instead the id is INSERTED first, under a unique constraint on
 * (provider, event_id), inside the same transaction that applies the payment
 * change. The second delivery cannot pass the insert, so its transaction
 * rolls back having changed nothing. The database decides, not the ordering
 * of two threads.
 *
 * It is also the reconciliation log. When a customer says they paid and the
 * order disagrees, this is the table that settles it - which is why an event
 * for an unknown order is still recorded, with no payment attached and an
 * outcome saying why nothing happened.
 */
@Entity
@Table(
        name = "payment_provider_events",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_payment_events_provider_event",
                columnNames = {"provider", "event_id"}))
public class PaymentProviderEvent {

    /** What we did with the event, and why. */
    public enum Outcome {
        /** Applied: the payment moved as a result of this event. */
        APPLIED,
        /** Valid, but the payment was already in this state. Nothing to do. */
        ALREADY_SETTLED,
        /** No payment matched the provider order id in this event. */
        UNKNOWN_ORDER,
        /** The amount or currency did not match what we asked the gateway to collect. */
        MISMATCH,
        /** Understood, but not a state we act on (a pending/attempt notification). */
        IGNORED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentProvider provider;

    @Column(name = "event_id", nullable = false, length = 160)
    private String eventId;

    @Column(name = "event_type", length = 64)
    private String eventType;

    /**
     * LAZY and nullable. An event can arrive for an order this system does
     * not recognise - a stale sandbox order, a misconfigured dashboard
     * pointing at the wrong environment - and that is worth recording
     * precisely BECAUSE nothing could be done with it.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @Column(name = "provider_order_id", length = 120)
    private String providerOrderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Outcome outcome;

    /**
     * Short human explanation. Deliberately NOT the raw payload: a webhook
     * body carries payment instrument details that have no business sitting
     * in this database for years, and storing it would turn a reconciliation
     * table into a liability.
     */
    @Column(length = 500)
    private String detail;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    public PaymentProviderEvent() {
    }

    public static PaymentProviderEvent of(PaymentProvider provider, String eventId, String eventType,
                                          String providerOrderId, Outcome outcome, String detail) {
        PaymentProviderEvent event = new PaymentProviderEvent();
        event.provider = provider;
        event.eventId = eventId;
        event.eventType = eventType;
        event.providerOrderId = providerOrderId;
        event.outcome = outcome;
        event.detail = detail;
        event.receivedAt = LocalDateTime.now();
        return event;
    }

    public Long getId() { return id; }

    public PaymentProvider getProvider() { return provider; }
    public void setProvider(PaymentProvider provider) { this.provider = provider; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public Payment getPayment() { return payment; }
    public void setPayment(Payment payment) { this.payment = payment; }

    public String getProviderOrderId() { return providerOrderId; }
    public void setProviderOrderId(String providerOrderId) { this.providerOrderId = providerOrderId; }

    public Outcome getOutcome() { return outcome; }
    public void setOutcome(Outcome outcome) { this.outcome = outcome; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }
}
