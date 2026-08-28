package com.gpstore.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin-only controls for the test catalogue.
 *
 * ADMIN-ONLY IS ENFORCED IN SecurityConfig, not here, matching how every
 * other admin surface in this application is protected. These endpoints can
 * insert a thousand products, make a thousand outbound requests, and delete
 * every test product in the shop - which is exactly the set of things that
 * must never be reachable without a token.
 *
 * WHY ENDPOINTS RATHER THAN A STARTUP RUNNER ALONE: the shop is operated from
 * a phone. A runner that only fires on boot means every catalogue change needs
 * a redeploy and a cold start on a free-tier instance. These can be called
 * with curl from anywhere, including Termux.
 */
@RestController
@RequestMapping("/api/admin/catalog")
public class CatalogAdminController {

    private static final Logger log = LoggerFactory.getLogger(CatalogAdminController.class);

    private final CatalogSeedService seedService;
    private final CatalogImageBackfillService imageService;
    private final CatalogCleanupService cleanupService;
    private final CatalogAuditService auditService;
    private final CatalogImageR2MigrationService r2MigrationService;

    public CatalogAdminController(CatalogSeedService seedService,
                                  CatalogImageBackfillService imageService,
                                  CatalogCleanupService cleanupService,
                                  CatalogAuditService auditService,
                                  CatalogImageR2MigrationService r2MigrationService) {
        this.seedService = seedService;
        this.imageService = imageService;
        this.cleanupService = cleanupService;
        this.auditService = auditService;
        this.r2MigrationService = r2MigrationService;
    }

    /** Idempotent. Running it twice updates rather than duplicating. */
    @PostMapping("/seed")
    public CatalogSeedService.SeedResult seed() {
        log.info("Catalog seed requested by admin");
        return seedService.seed();
    }

    /**
     * Fetches real product images. Bounded per call because each product
     * costs a courtesy pause plus a live request - see the service.
     */
    @PostMapping("/images/backfill")
    public CatalogImageBackfillService.BackfillResult backfillImages(
            @RequestParam(defaultValue = "100") int limit) {
        log.info("Catalog image backfill requested by admin, limit {}", limit);
        return imageService.backfill(Math.max(1, Math.min(limit, 1000)));
    }

    /**
     * Copies Cloudinary catalogue bytes into R2 and rewrites image_url.
     * Does not delete Cloudinary. Requires R2_* on the VPS. confirm=true
     * is required so a stray tap cannot start a copy job.
     */
    @PostMapping("/images/migrate-to-r2")
    public ResponseEntity<?> migrateImagesToR2(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean confirm) {
        if (!confirm) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Refused. Re-send with ?confirm=true to copy Cloudinary images into R2.",
                    "hint", "This does not delete Cloudinary objects. Existing HTTPS URLs keep working."));
        }
        log.info("Catalog Cloudinary→R2 migration requested by admin, limit {}", limit);
        return ResponseEntity.ok(r2MigrationService.migrate(limit));
    }

    /** The numbers the pre-launch review needs, counted from the database. */
    @GetMapping("/audit")
    public CatalogAuditService.CatalogAudit audit() {
        return auditService.audit();
    }

    /**
     * Deletes every product flagged is_test_data.
     *
     * REFUSES rather than cascades when a test product has been ordered.
     * Deleting it would leave an order line pointing at nothing, and an order
     * history that cannot be rendered is a worse outcome than a leftover test
     * product - especially since the products in question are the ones
     * somebody was testing checkout with. Those are reported by id so they can
     * be dealt with deliberately.
     *
     * The confirm parameter is not decoration: this is the one endpoint here
     * that destroys data, and a misplaced curl should not be enough.
     */
    @DeleteMapping("/test-data")
    public ResponseEntity<?> removeTestData(@RequestParam(defaultValue = "false") boolean confirm) {
        if (!confirm) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Refused. Re-send with ?confirm=true to delete all test products.",
                    "hint", "GET /api/admin/catalog/audit first to see what would be removed."));
        }
        log.warn("Catalog test-data deletion requested by admin");
        return ResponseEntity.ok(cleanupService.deleteTestProducts());
    }
}
