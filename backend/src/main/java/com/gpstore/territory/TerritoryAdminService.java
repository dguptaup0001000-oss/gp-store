package com.gpstore.territory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpstore.entity.DeliveryPartner;
import com.gpstore.entity.DeliverySubzone;
import com.gpstore.entity.DeliveryZone;
import com.gpstore.entity.SubzoneBackupPartner;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.AddressRepository;
import com.gpstore.repository.DeliveryPartnerRepository;
import com.gpstore.repository.DeliverySubzoneRepository;
import com.gpstore.repository.DeliveryZoneRepository;
import com.gpstore.repository.SubzoneBackupPartnerRepository;
import com.gpstore.entity.Address;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The only thing in this application allowed to move a boundary.
 *
 * Everything else - dispatch, batching, routing, capacity - reads the map and
 * never writes it. That asymmetry is deliberate and is what makes the
 * territories permanent in any meaningful sense: there is exactly one door
 * into the geography, it is behind admin authorisation, and a human has to
 * walk through it.
 *
 * Every write invalidates the resolver's cached map, so the next address
 * saved lands in the territory the administrator just drew rather than the
 * one they replaced.
 */
@Service
public class TerritoryAdminService {

    /**
     * The count the design calls for. Not enforced as a constraint - a map is
     * built up over days and would be unusable if it refused every state
     * except the finished one - but reported by {@link #healthCheck()} so
     * "we have 26" is something anyone can verify rather than assume.
     */
    public static final int EXPECTED_ZONES = 8;
    public static final int EXPECTED_SUBZONES = 26;

    private final DeliveryZoneRepository zoneRepository;
    private final DeliverySubzoneRepository subzoneRepository;
    private final SubzoneBackupPartnerRepository backupRepository;
    private final DeliveryPartnerRepository partnerRepository;
    private final AddressRepository addressRepository;
    private final TerritoryResolver resolver;
    private final ObjectMapper objectMapper;

    public TerritoryAdminService(DeliveryZoneRepository zoneRepository,
                                 DeliverySubzoneRepository subzoneRepository,
                                 SubzoneBackupPartnerRepository backupRepository,
                                 DeliveryPartnerRepository partnerRepository,
                                 AddressRepository addressRepository,
                                 TerritoryResolver resolver,
                                 ObjectMapper objectMapper) {
        this.zoneRepository = zoneRepository;
        this.subzoneRepository = subzoneRepository;
        this.backupRepository = backupRepository;
        this.partnerRepository = partnerRepository;
        this.addressRepository = addressRepository;
        this.resolver = resolver;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- zones

    @Transactional
    public DeliveryZone saveZone(DeliveryZone zone) {
        if (zone.getCode() == null || zone.getCode().isBlank()) {
            throw new BadRequestException("A zone needs a code - Z1 through Z8.");
        }
        DeliveryZone saved = zoneRepository.save(zone);
        resolver.invalidate();
        return saved;
    }

    @Transactional(readOnly = true)
    public List<DeliveryZone> listZones() {
        return zoneRepository.findByActiveTrueOrderByDisplayOrderAscIdAsc();
    }

    // ------------------------------------------------------------- subzones

    /**
     * Creates or updates one territory.
     *
     * The boundary is validated by PARSING it, not by trusting it. A polygon
     * that cannot be read is silently invisible to the resolver - it matches
     * no address, every order in it falls back, and nothing says why. Far
     * better that the administrator who pasted it finds out in the same second
     * they pasted it.
     */
    @Transactional
    public DeliverySubzone saveSubzone(Long zoneId, DeliverySubzone incoming) {
        DeliveryZone zone = zoneRepository.findById(zoneId)
                .orElseThrow(() -> new ResourceNotFoundException("Zone not found: " + zoneId));

        if (incoming.getCode() == null || incoming.getCode().isBlank()) {
            throw new BadRequestException("A subzone needs a code - Z7B and so on.");
        }

        if (incoming.getBoundary() != null && !incoming.getBoundary().isBlank()) {
            TerritoryPolygon polygon = TerritoryPolygon.parse(incoming.getBoundary(), objectMapper);
            if (polygon == null) {
                throw new BadRequestException(
                        "That boundary could not be read. It must be a JSON array of at least three "
                                + "[latitude, longitude] pairs, e.g. [[28.61,77.20],[28.62,77.20],[28.62,77.21]].");
            }
            for (DeliverySubzone existing : subzoneRepository.findAll()) {
                if (incoming.getId() != null && incoming.getId().equals(existing.getId())) {
                    continue;
                }
                if (existing.getBoundary() == null || existing.getBoundary().isBlank()) {
                    continue;
                }
                TerritoryPolygon other = TerritoryPolygon.parse(existing.getBoundary(), objectMapper);
                if (other != null && polygon.overlapsInterior(other)) {
                    throw new BadRequestException(
                            "That outline overlaps territory " + existing.getCode()
                                    + ". Redraw so each house is in exactly one territory.");
                }
            }
        }

        incoming.setZone(zone);
        DeliverySubzone saved = subzoneRepository.save(incoming);
        resolver.invalidate();
        return saved;
    }

    /**
     * Whether this JSON is a drawable outline. Does not persist anything.
     */
    public Map<String, Object> validateBoundary(String boundary) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (boundary == null || boundary.isBlank()) {
            body.put("valid", false);
            body.put("vertexCount", 0);
            body.put("message",
                    "Paste a JSON array of at least three [latitude, longitude] pairs.");
            return body;
        }
        TerritoryPolygon polygon = TerritoryPolygon.parse(boundary, objectMapper);
        if (polygon == null) {
            body.put("valid", false);
            body.put("vertexCount", 0);
            body.put("message",
                    "That boundary could not be read. It must be a JSON array of at least three "
                            + "[latitude, longitude] pairs, e.g. [[28.61,77.20],[28.62,77.20],[28.62,77.21]].");
            return body;
        }
        body.put("valid", true);
        body.put("vertexCount", polygon.vertexCount());
        body.put("message", "Outline is valid (" + polygon.vertexCount() + " points).");
        return body;
    }

    @Transactional(readOnly = true)
    public List<DeliverySubzone> listSubzones() {
        return subzoneRepository.findAll();
    }

    /** Assigns the rider who normally works this territory. */
    @Transactional
    public DeliverySubzone setPrimaryPartner(Long subzoneId, Long partnerId) {
        DeliverySubzone subzone = requireSubzone(subzoneId);
        DeliveryPartner partner = partnerId == null ? null : requirePartner(partnerId);
        subzone.setPrimaryPartner(partner);
        DeliverySubzone saved = subzoneRepository.save(subzone);
        resolver.invalidate();
        return saved;
    }

    /**
     * Replaces this territory's standing backup list, in priority order.
     *
     * Replace rather than append, because a backup list is a statement about
     * who currently knows this territory, and appending would let people who
     * left the roster months ago drift to the bottom of it rather than off it.
     */
    @Transactional
    public List<SubzoneBackupPartner> setBackupPartners(Long subzoneId, List<Long> partnerIdsInPriorityOrder) {
        DeliverySubzone subzone = requireSubzone(subzoneId);
        backupRepository.deleteBySubzoneId(subzoneId);

        List<SubzoneBackupPartner> saved = new ArrayList<>();
        int priority = 1;
        for (Long partnerId : partnerIdsInPriorityOrder) {
            if (subzone.getPrimaryPartner() != null
                    && subzone.getPrimaryPartner().getId().equals(partnerId)) {
                // Naming the primary as their own backup reads as a safety net
                // and is the opposite: the ladder reaches rung 2 precisely
                // because rung 1 could not take it, so this entry can only
                // ever be skipped. Rejecting it stops someone believing the
                // territory is covered when it is not.
                throw new BadRequestException(
                        "A territory's primary rider cannot also be its backup - the backup list is "
                                + "only consulted when the primary cannot take the order.");
            }
            SubzoneBackupPartner backup = new SubzoneBackupPartner();
            backup.setSubzone(subzone);
            backup.setPartner(requirePartner(partnerId));
            backup.setPriority(priority++);
            saved.add(backupRepository.save(backup));
        }
        return saved;
    }

    /**
     * Declares which territories a rider may be borrowed between, in both
     * directions.
     *
     * WRITTEN BOTH WAYS on purpose. "Z7A borders Z7B" and "Z7B borders Z7A"
     * are the same fact, and storing it once would mean every lookup is an OR
     * across two columns and every edit risks the two halves disagreeing.
     *
     * NOT DERIVED FROM THE POLYGONS, which is the whole point of the table.
     * Two outlines can share a long edge and still be a half-hour apart if
     * that edge is a railway line with no crossing. Only someone who knows the
     * roads can say whether a border is a border a scooter can cross.
     */
    @Transactional
    public DeliverySubzone setNeighbours(Long subzoneId, List<Long> neighbourIds) {
        DeliverySubzone subzone = requireSubzone(subzoneId);

        // Clear the old edges from both sides before writing the new ones,
        // otherwise a removed neighbour keeps pointing back at this one and
        // the graph quietly becomes asymmetric.
        for (DeliverySubzone previous : new ArrayList<>(subzone.getNeighbours())) {
            previous.getNeighbours().removeIf(s -> s.getId().equals(subzoneId));
            subzoneRepository.save(previous);
        }
        subzone.getNeighbours().clear();

        for (Long neighbourId : neighbourIds) {
            if (neighbourId.equals(subzoneId)) {
                throw new BadRequestException("A territory cannot be its own neighbour.");
            }
            DeliverySubzone neighbour = requireSubzone(neighbourId);

            subzone.getNeighbours().add(neighbour);
            if (neighbour.getNeighbours().stream().noneMatch(s -> s.getId().equals(subzoneId))) {
                neighbour.getNeighbours().add(subzone);
                subzoneRepository.save(neighbour);
            }
        }

        DeliverySubzone saved = subzoneRepository.save(subzone);
        resolver.invalidate();
        return saved;
    }

    // ------------------------------------------------------------ addresses

    /**
     * Re-resolves every address that is not pinned by hand.
     *
     * Run after drawing or redrawing territories. This is the ONE operation
     * that deliberately moves existing customers between territories, and it
     * is a button an administrator presses rather than something that happens
     * on its own - which is exactly the difference between a map being edited
     * and a map drifting.
     *
     * Paged rather than loaded whole: this table is one row per customer
     * address and the instance has 512 MB.
     */
    @Transactional
    public int reresolveAllAddresses(int pageSize) {
        resolver.invalidate();

        int moved = 0;
        int page = 0;
        int safePageSize = Math.min(Math.max(pageSize, 1), 500);

        while (true) {
            var slice = addressRepository.findAll(
                    org.springframework.data.domain.PageRequest.of(page, safePageSize,
                            org.springframework.data.domain.Sort.by("id")));
            if (slice.isEmpty()) {
                break;
            }

            for (Address address : slice.getContent()) {
                if (Boolean.TRUE.equals(address.getSubzoneLocked())) {
                    continue;
                }
                Long before = address.getSubzone() == null ? null : address.getSubzone().getId();
                Long after = resolver.resolveSubzoneId(address.getLatitude(), address.getLongitude())
                        .orElse(null);

                if (!java.util.Objects.equals(before, after)) {
                    address.setSubzone(after == null ? null : subzoneRepository.getReferenceById(after));
                    addressRepository.save(address);
                    moved++;
                }
            }

            if (!slice.hasNext()) {
                break;
            }
            page++;
        }
        return moved;
    }

    /**
     * Pins one address into a territory by hand and marks it so nothing
     * automatic moves it again.
     *
     * The map cannot know that a plot sits on the far side of a boundary road,
     * or that a colony's only usable gate opens into the next territory. This
     * is how a person who does know says so permanently.
     */
    @Transactional
    public Address pinAddress(Long addressId, Long subzoneId) {
        Address address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResourceNotFoundException("Address not found: " + addressId));
        address.setSubzone(subzoneId == null ? null : requireSubzone(subzoneId));
        address.setSubzoneLocked(true);
        return addressRepository.save(address);
    }

    // ----------------------------------------------------------- inspection

    /**
     * What is actually configured, versus what the design calls for.
     *
     * Every field here answers a question that is otherwise only answerable by
     * an order going wrong: is the map finished, does every territory have an
     * outline, does every territory have a rider, and is anybody's border
     * declared only from one side.
     */
    public record TerritoryHealth(int zones,
                                  int expectedZones,
                                  int subzones,
                                  int expectedSubzones,
                                  int subzonesWithBoundary,
                                  int subzonesWithPrimaryPartner,
                                  List<String> subzonesMissingBoundary,
                                  List<String> subzonesMissingPartner,
                                  List<String> subzonesWithNoNeighbours,
                                  List<String> problems) {
    }

    @Transactional(readOnly = true)
    public TerritoryHealth healthCheck() {
        List<DeliveryZone> zones = zoneRepository.findByActiveTrueOrderByDisplayOrderAscIdAsc();
        List<DeliverySubzone> subzones = subzoneRepository.findAll();

        List<String> missingBoundary = new ArrayList<>();
        List<String> missingPartner = new ArrayList<>();
        List<String> isolated = new ArrayList<>();
        int withBoundary = 0;
        int withPartner = 0;

        for (DeliverySubzone subzone : subzones) {
            if (!Boolean.TRUE.equals(subzone.getActive())) {
                continue;
            }
            if (TerritoryPolygon.parse(subzone.getBoundary(), objectMapper) == null) {
                missingBoundary.add(subzone.getCode());
            } else {
                withBoundary++;
            }
            if (subzone.getPrimaryPartner() == null) {
                missingPartner.add(subzone.getCode());
            } else {
                withPartner++;
            }
            if (subzoneRepository.findNeighbours(subzone.getId()).isEmpty()) {
                // Not automatically wrong - a territory can genuinely be
                // cut off by a river with one bridge - but it means the
                // overflow ladder skips straight past its neighbours rung,
                // and that is worth knowing on purpose rather than by
                // surprise on a busy evening.
                isolated.add(subzone.getCode());
            }
        }

        List<String> problems = new ArrayList<>();
        if (zones.size() != EXPECTED_ZONES) {
            problems.add("Expected " + EXPECTED_ZONES + " main zones, found " + zones.size() + ".");
        }
        if (subzones.size() != EXPECTED_SUBZONES) {
            problems.add("Expected " + EXPECTED_SUBZONES + " subzones, found " + subzones.size() + ".");
        }
        if (!missingBoundary.isEmpty()) {
            problems.add("No boundary drawn for: " + String.join(", ", missingBoundary)
                    + ". Addresses there resolve to no territory and their orders fall back to "
                    + "load-based assignment.");
        }
        if (!missingPartner.isEmpty()) {
            problems.add("No primary rider for: " + String.join(", ", missingPartner) + ".");
        }
        if (!isolated.isEmpty()) {
            problems.add("No declared neighbours for: " + String.join(", ", isolated)
                    + ". Overflow from these skips straight to zone-wide support.");
        }

        return new TerritoryHealth(zones.size(), EXPECTED_ZONES, subzones.size(), EXPECTED_SUBZONES,
                withBoundary, withPartner, missingBoundary, missingPartner, isolated, problems);
    }

    private DeliverySubzone requireSubzone(Long id) {
        return subzoneRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Subzone not found: " + id));
    }

    private DeliveryPartner requirePartner(Long id) {
        return partnerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery partner not found: " + id));
    }
}
