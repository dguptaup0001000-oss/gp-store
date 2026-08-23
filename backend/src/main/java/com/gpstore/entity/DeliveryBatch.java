package com.gpstore.entity;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "delivery_batches")
@Getter
@Setter
@NoArgsConstructor
public class DeliveryBatch {

    // Business rule: a delivery partner carries at most this many orders per run.
    public static final int MAX_ORDERS_PER_BATCH = 20;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String batchNumber;

    /**
     * Free text copied from whatever the customer typed into their address,
     * and matched with = when looking for an open batch.
     *
     * "Sector 12", "sector 12" and "Sector-12" therefore open three separate
     * batches for one neighbourhood, and every typo opens a fourth. Kept so
     * existing rows still read; new grouping goes through {@link #subzone},
     * which is a row in a table rather than a string a customer typed.
     */
    @Deprecated
    private String area;

    /**
     * The permanent territory this batch is being built for.
     *
     * Batching by territory rather than by typed text is what makes a batch
     * mean something to the rider carrying it: every stop in it is inside one
     * area they already know, which is the precondition for ordering the
     * stops into a route worth riding.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subzone_id")
    private DeliverySubzone subzone;

    // DTO refactor complete: DeliveryBatchService/Controller now return
    // DeliveryBatchResponse from @Transactional(readOnly = true) service methods.
    @ManyToOne(fetch = FetchType.LAZY)
    private DeliveryPartner deliveryPartner;

    // OPEN (still accepting orders, < 20), FULL (20 orders, ready to dispatch), DISPATCHED, COMPLETED
    private String status;

    private LocalDateTime createdAt;

    private Boolean active;
}
