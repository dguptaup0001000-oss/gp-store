package com.gpstore.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One day the vans do not run: a festival, a wedding, a flood.
 *
 * <p>A DATE, NOT A TIMESTAMP. "Closed on Holi" is a statement about a day in
 * the shop's own calendar, and storing it as an instant would make it start
 * and end at 05:30 local - the classic bug where a holiday begins at half past
 * five in the morning. The date is always interpreted in the shop's zone (see
 * StoreScheduleProperties).
 *
 * <p>CLOSED MEANS NO DELIVERIES, NOT NO SHOP. Browsing continues, orders are
 * still taken, and they are scheduled for the next open day. See StoreStatus
 * for why those are three separate questions.
 */
@Entity
@Table(name = "store_closures")
@Getter
@Setter
@NoArgsConstructor
public class StoreClosure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique: the same day cannot be closed twice. */
    @Column(name = "closed_on", nullable = false, unique = true)
    private LocalDate closedOn;

    /** Shown to customers, so it is written for them, not for the log. */
    @Column(name = "reason", length = 300)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 120)
    private String createdBy;
}
