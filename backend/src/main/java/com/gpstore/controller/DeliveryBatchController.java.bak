package com.gpstore.controller;

import com.gpstore.entity.DeliveryBatch;
import com.gpstore.service.DeliveryBatchService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/delivery-batches")
public class DeliveryBatchController {

    private final DeliveryBatchService service;

    public DeliveryBatchController(DeliveryBatchService service) {
        this.service = service;
    }

    @PostMapping
    public DeliveryBatch save(@RequestBody DeliveryBatch batch) {
        return service.save(batch);
    }

    @GetMapping
    public List<DeliveryBatch> getAll() {
        return service.getAll();
    }

    @GetMapping("/status/{status}")
    public List<DeliveryBatch> getByStatus(@PathVariable String status) {
        return service.getByStatus(status);
    }

    @PutMapping
    public DeliveryBatch update(@RequestBody DeliveryBatch batch) {
        return service.update(batch);
    }
}