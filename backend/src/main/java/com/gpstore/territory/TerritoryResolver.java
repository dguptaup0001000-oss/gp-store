package com.gpstore.territory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpstore.entity.DeliverySubzone;
import com.gpstore.repository.DeliverySubzoneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Answers one question: which permanent territory is this point in?
 *
 * THE WHOLE MAP LIVES IN MEMORY. Twenty-six polygons of a few dozen vertices
 * is a few kilobytes. Holding it means resolving an address costs no database
 * round trip at all, which matters because the alternative - a query per
 * address save, per admin re-resolve, per bulk backfill - is exactly the kind
 * of per-request database work this application has spent a lot of effort
 * removing.
 *
 * THE CACHE IS REFRESHED, NEVER STALE-FOREVER. An administrator who redraws a
 * boundary expects the next address they save to land in the new one. So the
 * cache reloads on a short interval, AND {@link #invalidate()} is called
 * directly by the admin service the moment it writes - the interval is the
 * safety net for an edit made on another instance, not the primary mechanism.
 *
 * IT FAILS CLOSED, and this is the important property. An address that
 * matches no territory returns empty. It does NOT get put in the nearest
 * territory, the biggest one, or a default. "We do not know which territory
 * this is" is a true and useful answer that the dispatcher can act on;
 * "probably Z3" is a rider sent across a river.
 *
 * WHAT IT DELIBERATELY DOES NOT DO. It never writes. Resolution is a pure
 * function of a point and the current map, and the decision to STAMP a result
 * onto an address - which is what makes a customer's territory permanent -
 * belongs to the caller, so that permanence is visible at the call site rather
 * than buried in a lookup.
 */
@Service
public class TerritoryResolver {

    private static final Logger log = LoggerFactory.getLogger(TerritoryResolver.class);

    /**
     * How long a cached map may be used before it is reloaded.
     *
     * Two minutes is chosen against the cost of being wrong in each
     * direction. Too long and an admin redrawing a boundary watches addresses
     * land in the old territory with no explanation. Too short and every
     * instance re-reads the table constantly for a map that changes a few
     * times a year. Direct invalidation covers the same-instance case, so
     * this only ever matters across instances.
     */
    static final long REFRESH_MS = 2 * 60 * 1000L;

    /**
     * One territory, flattened to what resolution actually needs.
     *
     * A record rather than the entity on purpose: the cache outlives the
     * transaction that filled it, and holding detached entities with lazy
     * associations across that boundary is how LazyInitializationException
     * arrives in production and not in tests.
     */
    record Territory(Long subzoneId, String subzoneCode, Long zoneId, String zoneCode,
                     TerritoryPolygon polygon) {
    }

    private record Snapshot(List<Territory> territories, long loadedAtMs) {
    }

    private final DeliverySubzoneRepository subzoneRepository;
    private final ObjectMapper objectMapper;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(null);

    public TerritoryResolver(DeliverySubzoneRepository subzoneRepository, ObjectMapper objectMapper) {
        this.subzoneRepository = subzoneRepository;
        this.objectMapper = objectMapper;
    }

    /** Drops the cached map so the next resolution reloads it. */
    public void invalidate() {
        snapshot.set(null);
    }

    /**
     * The territory containing this point, or empty when there is none.
     *
     * Empty covers every "we do not know" case and they are all the same
     * answer to the caller: no coordinates on the address, no territories
     * drawn yet, a point in a gap between polygons, a point outside the
     * served area entirely.
     */
    @Transactional(readOnly = true)
    public Optional<Long> resolveSubzoneId(Double latitude, Double longitude) {
        return resolve(latitude, longitude).map(Territory::subzoneId);
    }

    /** As {@link #resolveSubzoneId} but returns the whole cached record. */
    @Transactional(readOnly = true)
    public Optional<Territory> resolve(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            return Optional.empty();
        }

        for (Territory territory : currentMap()) {
            if (territory.polygon().contains(latitude, longitude)) {
                List<String> overlaps = findOverlaps(latitude, longitude);
                if (overlaps.size() > 1) {
                    log.warn("Point ({}, {}) is inside overlapping territories {}; failing closed",
                            latitude, longitude, overlaps);
                    return Optional.empty();
                }
                return Optional.of(territory);
            }
        }
        return Optional.empty();
    }

    /**
     * Every territory whose outline contains this point.
     *
     * Should always be zero or one. It exists so an administrator can be TOLD
     * when it is two: overlapping polygons are a configuration mistake that
     * otherwise shows up months later as a customer whose rider changes for
     * no visible reason, because {@link #resolve} silently takes whichever
     * happens to be scanned first.
     */
    @Transactional(readOnly = true)
    public List<String> findOverlaps(Double latitude, Double longitude) {
        List<String> codes = new ArrayList<>();
        if (latitude == null || longitude == null) {
            return codes;
        }
        for (Territory territory : currentMap()) {
            if (territory.polygon().contains(latitude, longitude)) {
                codes.add(territory.subzoneCode());
            }
        }
        return codes;
    }

    /** How many territories currently have a usable outline. */
    @Transactional(readOnly = true)
    public int mappedTerritoryCount() {
        return currentMap().size();
    }

    private List<Territory> currentMap() {
        Snapshot current = snapshot.get();
        if (current != null && System.currentTimeMillis() - current.loadedAtMs() < REFRESH_MS) {
            return current.territories();
        }

        List<Territory> loaded = load();
        snapshot.set(new Snapshot(loaded, System.currentTimeMillis()));
        return loaded;
    }

    private List<Territory> load() {
        List<Territory> territories = new ArrayList<>();
        int unmapped = 0;

        for (DeliverySubzone subzone : subzoneRepository.findAllActiveForResolution()) {
            TerritoryPolygon polygon = TerritoryPolygon.parse(subzone.getBoundary(), objectMapper);
            if (polygon == null) {
                // A territory with no drawable outline matches nothing. That
                // is the fail-closed rule, and it is worth saying out loud
                // once per reload rather than silently: a subzone nobody can
                // be resolved into looks exactly like a subzone nobody lives
                // in, and the two need very different responses.
                unmapped++;
                continue;
            }
            territories.add(new Territory(
                    subzone.getId(),
                    subzone.getCode(),
                    subzone.getZone() == null ? null : subzone.getZone().getId(),
                    subzone.getZone() == null ? null : subzone.getZone().getCode(),
                    polygon));
        }

        if (unmapped > 0) {
            log.warn("Territory map loaded with {} active subzone(s) carrying no usable boundary. "
                    + "Addresses in those areas will resolve to no territory and their orders will "
                    + "fall back to load-based assignment.", unmapped);
        }

        return territories;
    }
}
