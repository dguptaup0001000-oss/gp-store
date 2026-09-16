package com.gpstore.platform.api;

import com.gpstore.platform.PlatformControlTowerService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/** Platform-only, bounded read endpoints for the Super Admin cockpit. */
@RestController
@RequestMapping("/api/platform/control")
public class PlatformControlTowerController {

    private final PlatformControlTowerService service;

    public PlatformControlTowerController(PlatformControlTowerService service) {
        this.service = service;
    }

    @GetMapping("/search")
    public PlatformControlTowerService.PageEnvelope<PlatformControlTowerService.SearchResult> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.search(q, page, size);
    }

    @GetMapping("/dashboard")
    public PlatformControlTowerService.DashboardSummary dashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end : from;
        return service.dashboard(start.atStartOfDay(), end.plusDays(1).atStartOfDay());
    }

    @GetMapping("/customers/{id}")
    public PlatformControlTowerService.Customer360 customer(@PathVariable Long id) {
        return service.customer(id);
    }

    public record RevealRequest(String field, String reason) {}

    @PostMapping("/customers/{id}/reveal")
    public PlatformControlTowerService.RevealedPii reveal(
            @PathVariable Long id, @RequestBody RevealRequest request) {
        return service.revealCustomerPii(id, request.field(), request.reason());
    }

    @GetMapping("/merchants/{id}")
    public PlatformControlTowerService.Merchant360 merchant(@PathVariable Long id) {
        return service.merchant(id);
    }

    @GetMapping("/shops/{id}")
    public PlatformControlTowerService.Shop360 shop(@PathVariable Long id) {
        return service.shop(id);
    }

    @GetMapping("/{resource:customers|merchants|shops|orders|workers|products|payments|refunds|returns|reviews|shop-reviews|security|audit}")
    public PlatformControlTowerService.PageEnvelope<java.util.Map<String, Object>> resource(
            @PathVariable String resource,
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long merchantId,
            @RequestParam(required = false) Long shopId,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long workerId,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDateTimeRange range = range(from, to);
        return service.resource(resource, q, page, size,
                new PlatformControlTowerService.ResourceFilters(
                        status, merchantId, shopId, customerId, workerId,
                        paymentStatus, paymentMethod,
                        range == null ? null : range.from(),
                        range == null ? null : range.to()));
    }

    @GetMapping("/orders/{id}")
    public java.util.Map<String, Object> order(@PathVariable Long id) {
        return service.orderDetail(id);
    }

    private record LocalDateTimeRange(java.time.LocalDateTime from,
                                      java.time.LocalDateTime to) {}

    private static LocalDateTimeRange range(LocalDate from, LocalDate to) {
        if (from == null && to == null) return null;
        LocalDate end = to == null ? from : to;
        LocalDate start = from == null ? to : from;
        return new LocalDateTimeRange(start.atStartOfDay(), end.plusDays(1).atStartOfDay());
    }
}
