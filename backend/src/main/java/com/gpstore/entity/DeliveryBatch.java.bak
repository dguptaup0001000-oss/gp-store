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

    private String area;

    // Deliberately left EAGER: DeliveryBatchController.getAll()/getByStatus()/
    // save()/update() all return raw DeliveryBatch entities with no @Transactional
    // on the service methods, so Jackson serializes after the session closes -
    // LAZY here throws LazyInitializationException on those endpoints. Revisit
    // alongside a DTO refactor of DeliveryBatchController, not in isolation.
    @ManyToOne
    private DeliveryPartner deliveryPartner;

    // OPEN (still accepting orders, < 20), FULL (20 orders, ready to dispatch), DISPATCHED, COMPLETED
    private String status;

    private LocalDateTime createdAt;

    private Boolean active;
}
