package com.gpstore.demand;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class DemandController {
    private final DemandNetwork demand;
    private final DemandPhotoService photos;

    public DemandController(DemandNetwork demand, DemandPhotoService photos) {
        this.demand = demand;
        this.photos = photos;
    }

    @PostMapping("/api/demand-requests")
    public DemandNetwork.DemandView create(@RequestBody DemandNetwork.CreateRequest request) {
        return demand.create(request);
    }

    @PostMapping("/api/demand-requests/photo/confirm")
    public DemandPhotoService.PhotoView confirmPhoto(@RequestBody PhotoRequest request) {
        return photos.confirm(request.objectKey());
    }

    @GetMapping("/api/demand-requests/mine")
    public List<DemandNetwork.DemandView> mine(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return demand.mine(page, size);
    }

    @PostMapping("/api/demand-requests/{id}/close")
    public DemandNetwork.DemandView close(@PathVariable long id) {
        return demand.close(id, false);
    }

    @PostMapping("/api/demand-requests/{id}/cancel")
    public DemandNetwork.DemandView cancel(@PathVariable long id) {
        return demand.close(id, true);
    }

    @PostMapping("/api/demand-responses/{id}/report")
    public DemandNetwork.ReportView report(
            @PathVariable long id, @RequestBody DemandNetwork.ReportRequest request) {
        return demand.report(id, request);
    }

    @GetMapping("/api/shop/demand-requests")
    public List<DemandNetwork.MerchantDemand> merchantRequests(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return demand.forCurrentShop(page, size);
    }

    @PutMapping("/api/shop/demand-requests/{id}/response")
    public DemandNetwork.ResponseView respond(
            @PathVariable long id, @RequestBody DemandNetwork.ResponseRequest request) {
        return demand.respond(id, request);
    }

    public record PhotoRequest(String objectKey) {}
}
