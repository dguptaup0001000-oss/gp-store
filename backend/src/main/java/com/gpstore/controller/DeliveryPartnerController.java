package com.gpstore.controller;

import com.gpstore.config.PageRequests;
import com.gpstore.dto.request.LocationUpdateRequest;
import com.gpstore.entity.DeliveryPartner;
import com.gpstore.security.AdminPermission;
import com.gpstore.security.CurrentUser;
import com.gpstore.security.RolePermissions;
import com.gpstore.service.DeliveryPartnerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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

    // Admin only (enforced in SecurityConfig). List JSON for the existing
    // admin UI; page/size cap the query so findAll() cannot dump the roster.
    @GetMapping
    public List<DeliveryPartner> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return service.getAll(PageRequests.of(page, size));
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

    // WHICH ACCOUNT THIS RIDER SIGNS IN WITH.
    //
    // Admin only, by the same /api/delivery-partners/** rule as the rest of
    // roster management - and it needs to be, because linking an account
    // GRANTS it the DELIVERY_BOY role.
    //
    // Placed above the /me/** routes purely for readability; SecurityConfig
    // orders the rules, not this file.
    // The worker's login used to be attached here, by pointing a roster row
    // at a Customer account. It now lives on the roster row itself and is
    // managed at /api/admin/workers - see WorkerAdminController. Two ways to
    // set the same credential is what made this so hard to get right.



    // A delivery partner setting their OWN availability (e.g. going off
    // duty) - resolved from their own account, never a client-supplied id,
    // so this can never touch anyone else's record.
    @PutMapping("/me/availability")
    public DeliveryPartner setMyAvailability(@RequestParam boolean available) {
        return service.setMyAvailability(currentUser.get().getWorkerId(), available);
    }

    // A delivery partner viewing their OWN roster record - needed so the
    // app can show real current availability on load, not a guess.
    @GetMapping("/me")
    public DeliveryPartner getMyProfile() {
        return service.getLiveWorkerOrThrow(currentUser.get().getWorkerId());
    }

    // A delivery partner's own app pushing its live GPS position (called
    // every few seconds while on a run) - resolved from their own account,
    // never a client-supplied id, so this can never spoof someone else's
    // location.
    @PutMapping("/me/location")
    public DeliveryPartner updateMyLocation(@Valid @RequestBody LocationUpdateRequest request) {
        return service.updateMyLocation(currentUser.get().getWorkerId(), request.getLatitude(),
                request.getLongitude(), request.getAccuracyMeters());
    }

}