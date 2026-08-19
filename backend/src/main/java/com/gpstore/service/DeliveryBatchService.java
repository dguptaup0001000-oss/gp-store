package com.gpstore.service;

import com.gpstore.entity.DeliveryBatch;
import com.gpstore.entity.DeliveryPartner;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.DeliveryBatchRepository;
import com.gpstore.repository.DeliveryPartnerRepository;
import com.gpstore.repository.DeliveryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class DeliveryBatchService {

    private final DeliveryBatchRepository repository;
    private final DeliveryPartnerRepository deliveryPartnerRepository;
    private final DeliveryRepository deliveryRepository;

    public DeliveryBatchService(
            DeliveryBatchRepository repository,
            DeliveryPartnerRepository deliveryPartnerRepository,
            DeliveryRepository deliveryRepository) {
        this.repository = repository;
        this.deliveryPartnerRepository = deliveryPartnerRepository;
        this.deliveryRepository = deliveryRepository;
    }

    // Unused by the current frontend (confirmed) and admin-only, but both
    // getAll() and getByStatus() below were unbounded - every delivery
    // batch ever created, growing forever with order volume. Capped
    // defensively rather than left as live unbounded API surface.
    private static final int ADMIN_LIST_CAP = 500;

    @Transactional
    public com.gpstore.dto.response.DeliveryBatchResponse save(com.gpstore.dto.request.DeliveryBatchRequest request) {


        DeliveryPartner partner = deliveryPartnerRepository.findById(request.getDeliveryPartnerId())


                .orElseThrow(() -> new ResourceNotFoundException("Delivery partner not found"));



        DeliveryBatch batch = new DeliveryBatch();


        batch.setBatchNumber("BATCH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());


        batch.setArea(request.getArea());


        batch.setDeliveryPartner(partner);


        batch.setStatus("OPEN");


        batch.setCreatedAt(LocalDateTime.now());


        batch.setActive(true);



        return com.gpstore.dto.response.DeliveryBatchResponse.from(repository.save(batch));
    }

    @Transactional(readOnly = true)


    public List<com.gpstore.dto.response.DeliveryBatchResponse> getAll() {
        return repository.findAll(org.springframework.data.domain.PageRequest.of(0, ADMIN_LIST_CAP)).stream()
                .map(com.gpstore.dto.response.DeliveryBatchResponse::from)
                .collect(java.util.stream.Collectors.toList());
    }

    @Transactional(readOnly = true)


    public List<com.gpstore.dto.response.DeliveryBatchResponse> getByStatus(String status) {
        return repository.findByStatus(status, org.springframework.data.domain.PageRequest.of(0, ADMIN_LIST_CAP)).stream()
                .map(com.gpstore.dto.response.DeliveryBatchResponse::from)
                .collect(java.util.stream.Collectors.toList());
    }

    @Transactional


    public com.gpstore.dto.response.DeliveryBatchResponse update(Long id, com.gpstore.dto.request.DeliveryBatchRequest request) {


        DeliveryBatch batch = repository.findById(id)


                .orElseThrow(() -> new ResourceNotFoundException("Delivery batch not found"));



        batch.setStatus(request.getStatus());



        return com.gpstore.dto.response.DeliveryBatchResponse.from(repository.save(batch));
    }

    /**
     * Business rule: a delivery partner carries at most MAX_ORDERS_PER_BATCH (20)
     * orders per run. This locks any existing OPEN batch for the partner+area,
     * re-checks its live order count under that lock, and transparently opens a
     * new batch if the current one is full - callers never see a "batch full"
     * error, a new run just starts automatically.
     */
    @Transactional
    public DeliveryBatch getOrCreateOpenBatch(Long deliveryPartnerId, String area) {

        List<DeliveryBatch> openBatches =
                repository.findOpenBatchForUpdate(deliveryPartnerId, area);

        for (DeliveryBatch batch : openBatches) {
            long currentCount = deliveryRepository.countByBatchId(batch.getId());
            if (currentCount < DeliveryBatch.MAX_ORDERS_PER_BATCH) {
                return batch;
            }
            // This batch is full - mark it so it stops being picked up again.
            if (!"FULL".equals(batch.getStatus())) {
                batch.setStatus("FULL");
                repository.save(batch);
            }
        }

        DeliveryPartner partner = deliveryPartnerRepository.findById(deliveryPartnerId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery partner not found"));

        DeliveryBatch newBatch = new DeliveryBatch();
        newBatch.setBatchNumber("BATCH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        newBatch.setArea(area);
        newBatch.setDeliveryPartner(partner);
        newBatch.setStatus("OPEN");
        newBatch.setCreatedAt(LocalDateTime.now());
        newBatch.setActive(true);

        return repository.save(newBatch);
    }
}
