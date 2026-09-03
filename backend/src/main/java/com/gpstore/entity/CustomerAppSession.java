package com.gpstore.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * One stretch of time a customer had the app open.
 *
 * WHAT THIS DELIBERATELY DOES NOT HOLD. There is no screen name, no search
 * term, no product id, no route trail. A shopkeeper asking "is this customer
 * actually using the app" is answered by a duration; answering it with a
 * journey would build a record of somebody's browsing that nobody asked for
 * and that could not be un-built later. The narrow version cannot be quietly
 * repurposed into the wide one.
 *
 * NOT EVIDENCE OF ANYTHING. The duration is measured by the phone and sent by
 * the app, so a wrong clock or a modified build can claim whatever it likes.
 * The server caps it before it lands here. Good enough to tell a regular from
 * somebody who installed the app once; not good enough to bill, pay or
 * discipline anyone on.
 */
@Entity
@Table(name = "customer_app_sessions")
public class CustomerAppSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at", nullable = false)
    private LocalDateTime endedAt;

    /** After the server's cap, never the raw number the app sent. */
    @Column(nullable = false)
    private Integer seconds;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Customer getCustomer() { return customer; }
    public void setCustomer(Customer customer) { this.customer = customer; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(LocalDateTime endedAt) { this.endedAt = endedAt; }

    public Integer getSeconds() { return seconds; }
    public void setSeconds(Integer seconds) { this.seconds = seconds; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
