package com.gpstore.territory;

import com.gpstore.entity.AssignmentReason;
import com.gpstore.entity.DeliveryPartner;
import com.gpstore.entity.DeliverySubzone;
import com.gpstore.entity.SubzoneBackupPartner;
import com.gpstore.repository.DeliveryRepository;
import com.gpstore.repository.DeliverySubzoneRepository;
import com.gpstore.repository.SubzoneBackupPartnerRepository;
import com.gpstore.service.DeliveryEstimateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decides WHICH RIDER gets an order, without ever touching where the
 * territories are.
 *
 * This is the dynamic half of the system, and the separation from the
 * permanent half is the entire design. Nothing in this class writes a
 * boundary, splits a territory, or reacts to a busy evening by redrawing a
 * map. When a territory is overloaded the answer is a second rider for the
 * night, and tomorrow the territory is exactly the shape it was.
 *
 * THE LADDER, in the order it is tried:
 *
 *   1. The territory's own primary rider, if present and under capacity.
 *   2. A named standing backup for that territory.
 *   3. The primary rider of a DECLARED neighbouring territory.
 *   4. Any rider in the same main zone.
 *   5. Any rider in a neighbouring main zone.
 *   6. Nothing territory-aware left - fall back and say so.
 *
 * Each rung is strictly worse than the one above it in terms of local
 * knowledge, which is the thing being conserved. A rider two territories away
 * with an empty plate is still a rider who does not know which gate opens.
 *
 * THE SAFETY RULE (and the reason the order of operations below is not an
 * accident): a candidate is NEVER chosen because they have fewer orders. Load
 * is a tie-breaker among riders who have already passed a geographic gate,
 * never a reason to reach past that gate. An idle rider on the far side of the
 * river loses to a busy one on this side, every time. {@link #isSuitable}
 * runs before {@link #score}, and no code path scores an unsuitable candidate.
 */
@Service
public class TerritoryDispatchService {

    private static final Logger log = LoggerFactory.getLogger(TerritoryDispatchService.class);

    /**
     * The outcome, with the reasoning attached.
     *
     * The explanation is not decoration. Six weeks from now the question will
     * be "why did a Z2 rider deliver in Z7 last Tuesday", and the difference
     * between a system that can answer that and one that cannot is whether
     * anybody ever trusts the dispatcher again.
     */
    public record DispatchDecision(DeliveryPartner partner,
                                   AssignmentReason reason,
                                   DeliverySubzone subzone,
                                   String explanation) {

        public boolean hasPartner() {
            return partner != null;
        }
    }

    /** A candidate that has already passed the geographic gate, with its score. */
    private record ScoredCandidate(DeliveryPartner partner, double score, String detail) {
    }

    private final DeliverySubzoneRepository subzoneRepository;
    private final SubzoneBackupPartnerRepository backupRepository;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryEstimateService estimateService;

    private final boolean enabled;
    private final double maxBackupDetourKm;
    private final double loadWeightKmPerOrder;

    public TerritoryDispatchService(
            DeliverySubzoneRepository subzoneRepository,
            SubzoneBackupPartnerRepository backupRepository,
            DeliveryRepository deliveryRepository,
            DeliveryEstimateService estimateService,
            @Value("${territory.enabled:true}") boolean enabled,
            @Value("${territory.max-backup-detour-km:4.0}") double maxBackupDetourKm,
            @Value("${territory.load-weight-km-per-order:0.8}") double loadWeightKmPerOrder) {
        this.subzoneRepository = subzoneRepository;
        this.backupRepository = backupRepository;
        this.deliveryRepository = deliveryRepository;
        this.estimateService = estimateService;
        this.enabled = enabled;
        this.maxBackupDetourKm = maxBackupDetourKm;
        this.loadWeightKmPerOrder = loadWeightKmPerOrder;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Walks the ladder for one delivery point in one territory.
     *
     * Returns a decision with a null partner when no rung produced a
     * geographically suitable rider. That is a real answer, not a failure: the
     * caller falls back to load-based assignment and records it as
     * {@link AssignmentReason#FALLBACK}, because an order that is already
     * placed and paid must go out even when the territory map cannot help.
     */
    @Transactional(readOnly = true)
    public DispatchDecision chooseFor(DeliverySubzone subzone, Double destLat, Double destLng) {
        if (!enabled) {
            return new DispatchDecision(null, AssignmentReason.FALLBACK, subzone,
                    "Territory dispatch is switched off (territory.enabled=false).");
        }
        if (subzone == null) {
            return new DispatchDecision(null, AssignmentReason.FALLBACK, null,
                    "This address belongs to no drawn territory, so there is no primary rider to "
                            + "prefer. Draw a subzone covering it to stop these falling back.");
        }

        // ---- Rung 1: the territory's own rider -----------------------------
        DeliveryPartner primary = subzone.getPrimaryPartner();
        long territoryLoad = deliveryRepository.countActiveBySubzoneId(subzone.getId());
        int capacity = subzone.getMaxConcurrentOrders() == null ? 12 : subzone.getMaxConcurrentOrders();

        boolean primaryPresent = primary != null && isOnDuty(primary);
        boolean territoryOverloaded = territoryLoad >= capacity;

        if (primaryPresent && !territoryOverloaded) {
            return new DispatchDecision(primary, AssignmentReason.PRIMARY, subzone,
                    subzone.label() + " has " + territoryLoad + " of " + capacity
                            + " live orders; its own rider takes it.");
        }

        // Which of the two things went wrong decides how the assignment is
        // LABELLED, not which candidates are considered - an absent rider and
        // a full one both need someone else, and the ladder below is the same
        // either way. The label is what makes the two distinguishable later,
        // and they call for completely different responses: absence is a
        // rostering problem, sustained overflow is a hiring one.
        AssignmentReason reason = primaryPresent
                ? AssignmentReason.OVERFLOW
                : AssignmentReason.ABSENCE;

        String situation = primaryPresent
                ? subzone.label() + " is at capacity (" + territoryLoad + "/" + capacity + ")"
                : subzone.label() + " has no rider on duty";

        // ---- Rung 2: named standing backups for THIS territory --------------
        Optional<ScoredCandidate> namedBackup = bestOf(
                namedBackupsFor(subzone), subzone, destLat, destLng);
        if (namedBackup.isPresent()) {
            return new DispatchDecision(namedBackup.get().partner(), reason, subzone,
                    situation + "; its named backup takes it. " + namedBackup.get().detail());
        }

        // ---- Rung 3: riders who own a DECLARED neighbouring territory -------
        List<DeliverySubzone> neighbours = subzoneRepository.findNeighbours(subzone.getId());
        Optional<ScoredCandidate> neighbourRider = bestOf(
                primariesOf(neighbours), subzone, destLat, destLng);
        if (neighbourRider.isPresent()) {
            return new DispatchDecision(neighbourRider.get().partner(), reason, subzone,
                    situation + "; a neighbouring territory's rider takes it. "
                            + neighbourRider.get().detail());
        }

        // ---- Rung 4: anyone else in the same main zone ----------------------
        if (subzone.getZone() != null) {
            List<DeliverySubzone> sameZone = new ArrayList<>(
                    subzoneRepository.findByZoneIdOrderByDisplayOrderAscIdAsc(subzone.getZone().getId()));
            sameZone.removeIf(s -> s.getId().equals(subzone.getId()));

            Optional<ScoredCandidate> zoneRider = bestOf(
                    primariesOf(sameZone), subzone, destLat, destLng);
            if (zoneRider.isPresent()) {
                return new DispatchDecision(zoneRider.get().partner(), AssignmentReason.ZONE_SUPPORT, subzone,
                        situation + "; another rider in " + subzone.getZone().getCode()
                                + " takes it. " + zoneRider.get().detail());
            }
        }

        // ---- Rung 5: a neighbouring MAIN ZONE -------------------------------
        // Derived from the subzone adjacency rather than declared separately:
        // two main zones are neighbours exactly when some territory in one
        // borders some territory in the other. Declaring it twice would let
        // the two statements disagree, and the subzone-level one is the one
        // that was written by someone looking at roads.
        List<DeliverySubzone> acrossTheZoneBorder = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (DeliverySubzone neighbour : neighbours) {
            if (neighbour.getZone() == null || subzone.getZone() == null) {
                continue;
            }
            if (neighbour.getZone().getId().equals(subzone.getZone().getId())) {
                continue;
            }
            for (DeliverySubzone sibling
                    : subzoneRepository.findByZoneIdOrderByDisplayOrderAscIdAsc(neighbour.getZone().getId())) {
                if (seen.add(sibling.getId())) {
                    acrossTheZoneBorder.add(sibling);
                }
            }
        }

        Optional<ScoredCandidate> farRider = bestOf(
                primariesOf(acrossTheZoneBorder), subzone, destLat, destLng);
        if (farRider.isPresent()) {
            return new DispatchDecision(farRider.get().partner(),
                    AssignmentReason.NEIGHBOUR_ZONE_SUPPORT, subzone,
                    situation + "; a rider from a neighbouring main zone takes it. "
                            + farRider.get().detail());
        }

        // ---- Rung 6: the ladder is exhausted --------------------------------
        return new DispatchDecision(null, AssignmentReason.FALLBACK, subzone,
                situation + ", and no rider within reach of it was both free and close enough. "
                        + "Falling back to whoever is least loaded.");
    }

    /**
     * A rider is on duty when the roster says they are available AND their
     * record is active.
     *
     * Both, because they mean different things: {@code active} is "still works
     * here", {@code available} is "on shift right now". A rider who left the
     * business three months ago is not a valid absence backup no matter what
     * their availability flag was left at.
     */
    private boolean isOnDuty(DeliveryPartner partner) {
        return partner != null
                && Boolean.TRUE.equals(partner.getActive())
                && Boolean.TRUE.equals(partner.getAvailable());
    }

    private List<DeliveryPartner> namedBackupsFor(DeliverySubzone subzone) {
        List<DeliveryPartner> partners = new ArrayList<>();
        for (SubzoneBackupPartner backup : backupRepository.findBySubzoneIdOrderByPriorityAscIdAsc(subzone.getId())) {
            if (backup.getPartner() != null) {
                partners.add(backup.getPartner());
            }
        }
        return partners;
    }

    private List<DeliveryPartner> primariesOf(List<DeliverySubzone> subzones) {
        // LinkedHashMap keyed by id: one rider can be primary for more than
        // one territory, and scoring them twice would just be wasted work
        // with no effect on the outcome. Insertion-ordered so the declared
        // neighbour order stays the tie-break of last resort.
        Map<Long, DeliveryPartner> unique = new LinkedHashMap<>();
        for (DeliverySubzone subzone : subzones) {
            DeliveryPartner partner = subzone.getPrimaryPartner();
            if (partner != null && partner.getId() != null) {
                unique.putIfAbsent(partner.getId(), partner);
            }
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * Gate first, then score. Never the other way round.
     *
     * Every candidate must pass {@link #isSuitable} before it is scored at
     * all, so there is no code path in which a low order count can carry an
     * unsuitable rider into the result. That ordering IS the safety rule; a
     * scoring function that merely weighted distance heavily would still let
     * a sufficiently idle rider on the wrong side of a river win.
     */
    private Optional<ScoredCandidate> bestOf(List<DeliveryPartner> candidates,
                                             DeliverySubzone target,
                                             Double destLat, Double destLng) {
        List<ScoredCandidate> scored = new ArrayList<>();

        for (DeliveryPartner candidate : candidates) {
            if (!isOnDuty(candidate)) {
                continue;
            }
            if (!isSuitable(candidate, destLat, destLng)) {
                continue;
            }
            scored.add(score(candidate, destLat, destLng));
        }

        return scored.stream().min(Comparator.comparingDouble(ScoredCandidate::score));
    }

    /**
     * The hard geographic gate. Passing it is a precondition for being
     * considered at all; failing it cannot be outweighed by anything.
     *
     * Two tests, and both are about the rider being genuinely able to serve
     * this drop rather than merely being idle:
     *
     * DISTANCE. If the rider's own position is known, the drop must be within
     * the configured detour of it. A rider whose live GPS puts them six
     * kilometres and a level crossing away is not a backup for this order
     * however empty their plate is.
     *
     * THEIR OWN LOAD. A rider already carrying a batch at capacity is not
     * available to absorb someone else's overflow. Taking the order would
     * simply move the overload rather than relieve it, and it would do so by
     * degrading a territory that was coping.
     *
     * WHEN POSITION IS UNKNOWN the distance test is skipped rather than
     * failed. A rider who has not sent a location - most likely one who has
     * not started their shift's first run - is still a legitimate candidate
     * from a rung that already established they belong nearby; the ladder,
     * not the GPS, is what put them in this list. Failing them here would
     * make the whole territory system depend on live tracking that may not be
     * running.
     */
    private boolean isSuitable(DeliveryPartner candidate, Double destLat, Double destLng) {
        long ownLoad = deliveryRepository.countActiveByPartnerId(candidate.getId());
        if (ownLoad >= com.gpstore.entity.DeliveryBatch.MAX_ORDERS_PER_BATCH) {
            return false;
        }

        Double riderLat = candidate.getCurrentLatitude();
        Double riderLng = candidate.getCurrentLongitude();
        if (riderLat == null || riderLng == null || destLat == null || destLng == null) {
            return true;
        }

        double detourKm = haversineKm(riderLat, riderLng, destLat, destLng);
        return detourKm <= maxBackupDetourKm;
    }

    /**
     * Lower is better. Everything is expressed in kilometres, including load,
     * so the trade-off being made is legible rather than hidden in
     * dimensionless weights.
     *
     * {@code load-weight-km-per-order} is literally "how many kilometres of
     * extra riding is one existing order worth avoiding". At the default of
     * 0.8, a rider two orders busier has to be more than 1.6 km closer to
     * win - a statement anyone can argue with, which is the point of putting
     * it in a config file instead of a magic number.
     */
    private ScoredCandidate score(DeliveryPartner candidate, Double destLat, Double destLng) {
        long load = deliveryRepository.countActiveByPartnerId(candidate.getId());

        double distanceKm;
        String from;
        if (candidate.getCurrentLatitude() != null && candidate.getCurrentLongitude() != null
                && destLat != null && destLng != null) {
            distanceKm = haversineKm(candidate.getCurrentLatitude(), candidate.getCurrentLongitude(),
                    destLat, destLng);
            from = "live position";
        } else {
            // No live position: fall back to distance from the store, which
            // is where an idle rider usually is, and is the same number for
            // every candidate in this situation - so among them the ranking
            // reduces to load, which is the correct behaviour once geography
            // genuinely cannot distinguish them.
            distanceKm = estimateService.distanceFromStoreKm(destLat, destLng);
            if (Double.isNaN(distanceKm)) {
                distanceKm = 0;
            }
            from = "store (rider position unknown)";
        }

        double score = distanceKm + load * loadWeightKmPerOrder;

        String detail = candidate.getName() + " is " + round1(distanceKm) + " km from the drop ("
                + from + ") carrying " + load + " live order" + (load == 1 ? "" : "s") + ".";

        return new ScoredCandidate(candidate, score, detail);
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    /** Same great-circle formula as DeliveryEstimateService, between two arbitrary points. */
    static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double earthRadiusKm = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return earthRadiusKm * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
