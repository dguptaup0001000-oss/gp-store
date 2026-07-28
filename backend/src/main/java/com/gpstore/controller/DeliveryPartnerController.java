package com.gpstore.controller;

import com.gpstore.entity.DeliveryPartner;
import com.gpstore.security.CurrentUser;
import com.gpstore.service.DeliveryPartnerService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/delivery-partners")
public class DeliveryPartnerController {

    private final DeliveryPartnerService service;
    private final CurrentUser currentUser;

    public DeliveryPartnerController(DeliveryPartnerService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    // Admin only (enforced in SecurityConfig).
    @PostMapping
    public DeliveryPartner save(@RequestBody DeliveryPartner partner) {
        return service.save(partner);
    }

    // Admin only (enforced in SecurityConfig).
    @GetMapping
    public List<DeliveryPartner> getAll() {
        return service.getAll();
    }

    @GetMapping("/available")
    public List<DeliveryPartner> getAvailablePartners() {
        return service.getAvailablePartners();
    }

    // Admin only (enforced in SecurityConfig) - full bulk update with no
    // ownership scoping, unlike the self-service endpoint below.
    @PutMapping
    public DeliveryPartner update(@RequestBody DeliveryPartner partner) {
        return service.update(partner);
    }

    // A delivery partner setting their OWN availability (e.g. going off
    // duty) - resolved from their own account, never a client-supplied id,
    // so this can never touch anyone else's record.
    @PutMapping("/me/availability")
    public DeliveryPartner setMyAvailability(@RequestParam boolean available) {
        return service.setMyAvailability(currentUser.customerId(), available);
    }

    // A delivery partner viewing their OWN roster record - needed so the
    // app can show real current availability on load, not a guess.
    @GetMapping("/me")
    public DeliveryPartner getMyProfile() {
        return service.getByAccountIdOrThrow(currentUser.customerId());
    }

}