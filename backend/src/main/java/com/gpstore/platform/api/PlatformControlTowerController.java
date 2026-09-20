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

    /**
     * The Merchants section of the control tower.
     *
     * <p>Separate from the global {@code /search} on purpose: that one
     * answers "find this string anywhere" across five entity types, which is
     * the right tool when an operator has a reference and does not know what
     * it refers to. This one answers "show me merchants", which is what the
     * Merchants tab is, and can therefore return merchant-shaped rows - owner,
     * shop count, status - instead of a lowest-common-denominator hit.
     *
     * <p>Authorization is the SecurityConfig line for {@code /api/platform/**}
     * (PLATFORM_ADMIN), not anything in this method and not anything Flutter
     * does. A customer, worker or merchant calling this directly is refused
     * before the handler runs.
     */
    @GetMapping("/merchants/search")
    public PlatformControlTowerService.PageEnvelope<PlatformControlTowerService.MerchantHit>
            searchMerchants(@RequestParam("q") String query,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "20") int size) {
        return service.searchMerchants(query, page, size);
    }

    /** The Customers section. Same contract as the merchants section above. */
    @GetMapping("/customers/search")
    public PlatformControlTowerService.PageEnvelope<PlatformControlTowerService.CustomerHit>
            searchCustomers(@RequestParam("q") String query,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "20") int size) {
        return service.searchCustomers(query, page, size);
    }

    @GetMapping("/dashboard")
    public PlatformControlTowerService.DashboardSummary dashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long merchantId,
            @RequestParam(required = false) Long shopId,
            @RequestParam(required = false) String orderStatus,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) String paymentMethod) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end : from;
        return service.dashboard(start.atStartOfDay(), end.plusDays(1).atStartOfDay(),
                new PlatformControlTowerService.DashboardFilters(
                        merchantId, shopId, orderStatus, paymentStatus, paymentMethod));
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

    /**
     * The full Customer 360.
     *
     * <p>A SEPARATE PATH FROM {@code /customers/{id}} rather than a change to
     * it, because deployed app builds parse that response and widening it
     * would make every one of them download sections they do not draw. The
     * existing endpoint keeps its shape; this one is what the new screen asks
     * for.
     */
    @GetMapping("/customers/{id}/profile")
    public PlatformControlTowerService.CustomerProfile customerProfile(@PathVariable Long id) {
        return service.customerProfile(id);
    }

    /**
     * The full Merchant 360, over a window.
     *
     * <p>The window applies to the things that are only meaningful over one -
     * the order-status breakdown and the active-day count. Identity, shops,
     * workforce and lifetime reputation are not windowed, because "how many
     * shops does this merchant have" has no date range.
     *
     * <p>Defaults to the last 30 days, which is the range an operator asking
     * "is this merchant trading" means, and a shorter default would send them
     * straight to the date picker every time.
     */
    @GetMapping("/merchants/{id}/profile")
    public PlatformControlTowerService.MerchantProfile merchantProfile(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        return service.merchantProfile(id, start.atStartOfDay(), end.plusDays(1).atStartOfDay());
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
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String stockStatus,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDateTimeRange range = range(from, to);
        return service.resource(resource, q, page, size,
                new PlatformControlTowerService.ResourceFilters(
                        status, merchantId, shopId, customerId, workerId,
                        paymentStatus, paymentMethod, category, stockStatus,
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
