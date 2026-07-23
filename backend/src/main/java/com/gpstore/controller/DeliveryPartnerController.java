package com.gpstore.controller;

import com.gpstore.entity.DeliveryPartner;
import com.gpstore.service.DeliveryPartnerService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/delivery-partners")
public class DeliveryPartnerController {

    private final DeliveryPartnerService service;

    public DeliveryPartnerController(DeliveryPartnerService service) {
        this.service = service;
    }

    @PostMapping
    public DeliveryPartner save(@RequestBody DeliveryPartner partner) {
        return service.save(partner);
    }

    @GetMapping
    public List<DeliveryPartner> getAll() {
        return service.getAll();
    }

    @GetMapping("/available")
    public List<DeliveryPartner> getAvailablePartners() {
        return service.getAvailablePartners();
    }

    @PutMapping
    public DeliveryPartner update(@RequestBody DeliveryPartner partner) {
        return service.update(partner);
    }

}