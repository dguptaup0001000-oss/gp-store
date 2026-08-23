package com.gpstore.controller;

import com.gpstore.entity.Address;
import com.gpstore.entity.DeliverySubzone;
import com.gpstore.entity.DeliveryZone;
import com.gpstore.entity.SubzoneBackupPartner;
import com.gpstore.territory.TerritoryAdminService;
import com.gpstore.territory.TerritoryResolver;
import com.gpstore.territory.TerritoryRouteService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Configuring the permanent delivery map, and inspecting what it currently
 * says.
 *
 * Admin-only - see SecurityConfig, which grants /api/admin/territory/**
 * beside catalog administration and for the same reason: one request here
 * silently reroutes every future order in an area.
 */
@RestController
@RequestMapping("/api/admin/territory")
public class TerritoryAdminController {

    private final TerritoryAdminService adminService;
    private final TerritoryResolver resolver;
    private final TerritoryRouteService routeService;

    public TerritoryAdminController(TerritoryAdminService adminService,
                                    TerritoryResolver resolver,
                                    TerritoryRouteService routeService) {
        this.adminService = adminService;
        this.resolver = resolver;
        this.routeService = routeService;
    }

    // ---------------------------------------------------------------- zones

    @GetMapping("/zones")
    public List<DeliveryZone> listZones() {
        return adminService.listZones();
    }

    @PostMapping("/zones")
    public DeliveryZone saveZone(@RequestBody DeliveryZone zone) {
        return adminService.saveZone(zone);
    }

    // ------------------------------------------------------------- subzones

    @GetMapping("/subzones")
    public List<DeliverySubzone> listSubzones() {
        return adminService.listSubzones();
    }

    @PostMapping("/zones/{zoneId}/subzones")
    public DeliverySubzone saveSubzone(@PathVariable Long zoneId, @RequestBody DeliverySubzone subzone) {
        return adminService.saveSubzone(zoneId, subzone);
    }

    @PutMapping("/subzones/{subzoneId}/primary-partner")
    public DeliverySubzone setPrimaryPartner(@PathVariable Long subzoneId,
                                             @RequestBody Map<String, Long> body) {
        return adminService.setPrimaryPartner(subzoneId, body.get("partnerId"));
    }

    /** Body: {"partnerIds": [12, 15, 9]} - first is tried first. */
    @PutMapping("/subzones/{subzoneId}/backup-partners")
    public List<SubzoneBackupPartner> setBackups(@PathVariable Long subzoneId,
                                                 @RequestBody Map<String, List<Long>> body) {
        return adminService.setBackupPartners(subzoneId, body.getOrDefault("partnerIds", List.of()));
    }

    /** Body: {"neighbourIds": [4, 5]} - written in both directions. */
    @PutMapping("/subzones/{subzoneId}/neighbours")
    public DeliverySubzone setNeighbours(@PathVariable Long subzoneId,
                                         @RequestBody Map<String, List<Long>> body) {
        return adminService.setNeighbours(subzoneId, body.getOrDefault("neighbourIds", List.of()));
    }

    // ------------------------------------------------------------ addresses

    /**
     * Re-runs resolution over every address that is not pinned by hand.
     *
     * The one operation that deliberately moves existing customers between
     * territories, which is why it is a button someone presses rather than
     * something that happens on a schedule.
     */
    @PostMapping("/addresses/reresolve")
    public Map<String, Object> reresolve(@RequestParam(defaultValue = "200") int pageSize) {
        int moved = adminService.reresolveAllAddresses(pageSize);
        return Map.of("addressesMoved", moved);
    }

    @PutMapping("/addresses/{addressId}/pin")
    public Address pin(@PathVariable Long addressId, @RequestBody Map<String, Long> body) {
        return adminService.pinAddress(addressId, body.get("subzoneId"));
    }

    // ----------------------------------------------------------- inspection

    /**
     * "Which territory would this point land in, and does more than one claim
     * it?" - the check to run right after drawing a boundary, before any
     * customer finds the answer out for you.
     */
    @GetMapping("/resolve")
    public Map<String, Object> resolve(@RequestParam double latitude, @RequestParam double longitude) {
        List<String> matches = resolver.findOverlaps(latitude, longitude);
        return Map.of(
                "subzoneCode", matches.isEmpty() ? "" : matches.get(0),
                "matches", matches,
                "overlapping", matches.size() > 1,
                "mappedTerritories", resolver.mappedTerritoryCount());
    }

    @GetMapping("/health")
    public TerritoryAdminService.TerritoryHealth health() {
        return adminService.healthCheck();
    }

    /** The route a rider would be given for everything currently waiting in one territory. */
    @GetMapping("/subzones/{subzoneId}/route")
    public TerritoryRouteService.PlannedRoute route(@PathVariable Long subzoneId) {
        return routeService.planRoute(subzoneId);
    }
}
