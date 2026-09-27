package com.gpstore.demand;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class DemandController {
    private final DemandNetwork demand;

    public DemandController(DemandNetwork demand) {
        this.demand = demand;
    }

    @PostMapping("/api/demand-requests")
    public DemandNetwork.DemandView create(@RequestBody DemandNetwork.CreateRequest request) {
        return demand.create(request);
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
}
