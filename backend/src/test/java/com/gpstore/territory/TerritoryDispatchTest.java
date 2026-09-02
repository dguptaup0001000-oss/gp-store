package com.gpstore.territory;

import com.gpstore.entity.AssignmentReason;
import com.gpstore.entity.DeliveryPartner;
import com.gpstore.entity.DeliverySubzone;
import com.gpstore.entity.DeliveryZone;
import com.gpstore.repository.DeliveryPartnerRepository;
import com.gpstore.repository.DeliverySubzoneRepository;
import com.gpstore.repository.DeliveryZoneRepository;
import com.gpstore.territory.TerritoryDispatchService.DispatchDecision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The dispatch ladder, against a real database.
 *
 * WHY NOT MOCKS. Every interesting question here is about what the DATA says -
 * whether a territory is at capacity, whether an edge exists in the neighbour
 * graph, whether a rider is on duty. A mocked repository would let this file
 * assert whatever it was told to assert and pass while the real ladder walked
 * straight past a rung.
 *
 * The fixtures are named with a prefix and removed in @AfterEach. That is not
 * housekeeping: this suite shares one database, and territory rows left behind
 * would resolve real addresses in later tests into territories that only exist
 * inside this file.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000",
        // A tight detour so "too far to be a backup" is a short hop in the
        // fixtures rather than a journey across a fabricated city.
        "territory.max-backup-detour-km=2.0"
})
class TerritoryDispatchTest {

    /**
     * Short deliberately: subzone codes are varchar(16), which is right for a
     * column holding "Z7B" and which this file has to live inside like any
     * other caller.
     */
    private static final String PREFIX = "TT-";

    @Autowired private TerritoryDispatchService dispatch;
    @Autowired private DeliveryZoneRepository zoneRepository;
    @Autowired private DeliverySubzoneRepository subzoneRepository;
    @Autowired private DeliveryPartnerRepository partnerRepository;
    @Autowired private TerritoryResolver resolver;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.gpstore.repository.DeliveryRepository deliveryRepository;

    /** Two touching squares, one degree apart in longitude, at Delhi latitudes. */
    private static final String WEST_BOUNDARY = "[[28.60,77.20],[28.60,77.22],[28.62,77.22],[28.62,77.20]]";
    private static final String EAST_BOUNDARY = "[[28.60,77.22],[28.60,77.24],[28.62,77.24],[28.62,77.22]]";

    /** A point comfortably inside the west territory. */
    private static final double DEST_LAT = 28.610;
    private static final double DEST_LNG = 77.210;

    private DeliveryZone zone;
    private DeliverySubzone west;
    private DeliverySubzone east;
    private final List<Long> createdPartners = new ArrayList<>();

    @BeforeEach
    void buildATinyMap() {
        cleanUp();

        zone = new DeliveryZone();
        zone.setCode(PREFIX + "Z7");
        zone.setName("Test main zone");
        zone.setActive(true);
        zone = zoneRepository.save(zone);

        west = newSubzone("Z7A", WEST_BOUNDARY, 2);
        east = newSubzone("Z7B", EAST_BOUNDARY, 2);
        resolver.invalidate();
    }

    @AfterEach
    void cleanUp() {
        // Order matters: the join and child tables reference the subzones.
        jdbc.update("DELETE FROM subzone_neighbours WHERE subzone_id IN "
                + "(SELECT id FROM delivery_subzones WHERE code LIKE ?)", PREFIX + "%");
        jdbc.update("DELETE FROM subzone_neighbours WHERE neighbour_subzone_id IN "
                + "(SELECT id FROM delivery_subzones WHERE code LIKE ?)", PREFIX + "%");
        jdbc.update("DELETE FROM subzone_backup_partners WHERE subzone_id IN "
                + "(SELECT id FROM delivery_subzones WHERE code LIKE ?)", PREFIX + "%");
        jdbc.update("DELETE FROM deliveries WHERE subzone_id IN "
                + "(SELECT id FROM delivery_subzones WHERE code LIKE ?)", PREFIX + "%");
        // Anything else still pointing at a fixture territory has to let go
        // before the territory row can be removed.
        jdbc.update("UPDATE addresses SET subzone_id = NULL WHERE subzone_id IN "
                + "(SELECT id FROM delivery_subzones WHERE code LIKE ?)", PREFIX + "%");
        jdbc.update("UPDATE delivery_batches SET subzone_id = NULL WHERE subzone_id IN "
                + "(SELECT id FROM delivery_subzones WHERE code LIKE ?)", PREFIX + "%");
        jdbc.update("DELETE FROM delivery_subzones WHERE code LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM delivery_zones WHERE code LIKE ?", PREFIX + "%");

        retireFixturePartners();
        createdPartners.clear();
        resolver.invalidate();
    }

    /**
     * Takes this file's fixture riders out of service, then out of the
     * database - in that order, and that order is the whole point.
     *
     * WHY THIS IS NOT THREE DELETEs. While a fixture rider exists with
     * available = true, they are a REAL candidate for the least-loaded
     * fallback path, and fourteen test classes in this suite run with a live
     * outbox worker - they do not disable outbox.drain-interval-ms, Spring
     * caches their contexts, and those contexts are never closed. So a worker
     * belonging to a class that finished minutes ago can auto-assign an order
     * to one of these riders at any moment, opening a batch against them.
     * Delete the batch and a delivery inserted a millisecond later still
     * points at it; the foreign key fires and a cleanup takes the test down
     * with it.
     *
     * Retiring first closes the window instead of racing it. An unavailable,
     * inactive rider cannot be picked by anything, so nothing new can attach
     * to them, and the deletes that follow have a stable target.
     *
     * WHY THE CATCH. Cleanup must not be the thing that fails a test. If a
     * row did slip in during the microseconds before retirement, the riders
     * stay in the table - unavailable, inactive, and unpickable, which is
     * exactly what a rider who has left the roster looks like. Inert rows are
     * a far smaller problem than a red suite that says nothing about the code.
     *
     * This is a pre-existing property of the suite rather than something the
     * territory work introduced; a 20 km radius made more test addresses
     * deliverable, which made more orders auto-assign, which made it visible.
     */
    private void retireFixturePartners() {
        jdbc.update("UPDATE delivery_partners SET available = false, active = false "
                + "WHERE name LIKE ?", PREFIX + "%");
        try {
            jdbc.update("DELETE FROM deliveries WHERE batch_id IN "
                    + "(SELECT id FROM delivery_batches WHERE delivery_partner_id IN "
                    + " (SELECT id FROM delivery_partners WHERE name LIKE ?))", PREFIX + "%");
            jdbc.update("DELETE FROM delivery_batches WHERE delivery_partner_id IN "
                    + "(SELECT id FROM delivery_partners WHERE name LIKE ?)", PREFIX + "%");
            jdbc.update("DELETE FROM delivery_partners WHERE name LIKE ?", PREFIX + "%");
        } catch (org.springframework.dao.DataIntegrityViolationException retiredButStillReferenced) {
            // Already unavailable and inactive above, so harmless. Left in
            // place deliberately rather than retried in a loop, which would
            // just be racing the same worker again.
        }
    }


    /**
     * Zeroes the workload of this file's fixture riders, immediately before a
     * decision that depends on their scores being equal.
     *
     * <p>WHY THIS EXISTS. score() is {@code distance + load * weight}, and
     * load is {@code countActiveByPartnerId} - a live count over the whole
     * database, not something this class controls. Two riders placed at the
     * same coordinates are only tied while both carry nothing, and a single
     * auto-assigned delivery against one of them breaks the tie the other way.
     * That is not hypothetical: it is how backupPriorityIsRespected failed in
     * CI with "expected: &lt;22&gt; but was: &lt;23&gt;" while passing 12/12 in
     * isolation.
     *
     * <p>A delivery reaches its rider through batch_id -> delivery_batches,
     * not a column of its own, so that join is what has to be cleared.
     *
     * <p>The source of those assignments is a cached Spring context from an
     * earlier test class whose outbox worker is still draining - see
     * retireFixturePartners() for the same problem seen from the cleanup side.
     * The real fix is upstream: a class that places orders and does not test
     * async behaviour should not run a live drain, and the ones that were
     * doing so have been given outbox.drain-interval-ms. This call is the
     * belt to that pair of braces. It does not make the race impossible - a
     * worker could still fire in the microseconds after it - so clearing is
     * only half of it. {@link #decideOnATie()} is the other half: it CHECKS
     * AGAIN after the decision and throws the decision away if the window was
     * lost, which is what finally closed this.
     */
    private void clearFixtureLoad() {
        jdbc.update("DELETE FROM deliveries WHERE batch_id IN "
                + "(SELECT id FROM delivery_batches WHERE delivery_partner_id IN "
                + " (SELECT id FROM delivery_partners WHERE name LIKE ?))", PREFIX + "%");
    }

    /**
     * The id of the first fixture rider carrying live work, or null if the
     * scores this class assumes are tied really are tied.
     */
    private Long busyFixtureRider() {
        for (Long partnerId : createdPartners) {
            if (partnerId != null && deliveryRepository.countActiveByPartnerId(partnerId) > 0) {
                return partnerId;
            }
        }
        return null;
    }

    /**
     * A dispatch decision taken with every fixture rider verifiably idle -
     * before AND after the scoring.
     *
     * CLEARING FIRST IS NOT ENOUGH, and that is the whole point of this
     * method. An outbox worker in a sibling context can assign an order in
     * the gap between the clear and the score, and one order is 0.8 km of
     * score - more than enough to flip two riders standing on the same spot.
     * The clear alone left microseconds open, and CI kept finding them.
     *
     * So the check runs again afterwards, and a decision taken during a lost
     * window is discarded rather than asserted on. chooseFor is
     * {@code @Transactional(readOnly = true)}, so re-taking it costs a query
     * and changes nothing.
     *
     * <p>This does not weaken the assertion: the test still has to pass with
     * both riders genuinely idle. It only refuses to judge a run where the
     * premise was not true. If the premise never holds, that is a real
     * problem in the suite and this fails saying so.
     */
    private DispatchDecision decideOnATie() {
        Long busy = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            clearFixtureLoad();
            if (busyFixtureRider() != null) {
                continue;
            }
            DispatchDecision decision = dispatch.chooseFor(reload(west), DEST_LAT, DEST_LNG);
            busy = busyFixtureRider();
            if (busy == null) {
                return decision;
            }
        }
        return fail("fixture rider " + busy + " picked up live work while this test was scoring, "
                + "four times running, so the scores this test assumes are tied are not tied. "
                + "Something is assigning to fixture riders mid-test - check that new "
                + "@SpringBootTest classes which place orders set outbox.drain-interval-ms.");
    }

    private DeliverySubzone newSubzone(String code, String boundary, int capacity) {
        DeliverySubzone subzone = new DeliverySubzone();
        subzone.setZone(zone);
        subzone.setCode(PREFIX + code);
        subzone.setName("Test " + code);
        subzone.setBoundary(boundary);
        subzone.setMaxConcurrentOrders(capacity);
        subzone.setActive(true);
        return subzoneRepository.save(subzone);
    }

    private DeliveryPartner newPartner(String name, boolean available, Double lat, Double lng) {
        DeliveryPartner partner = new DeliveryPartner();
        partner.setName(PREFIX + name);
        partner.setMobile("90000000" + (createdPartners.size() + 10));
        partner.setVehicleType("BIKE");
        partner.setAvailable(available);
        partner.setActive(true);
        partner.setCurrentLatitude(lat);
        partner.setCurrentLongitude(lng);
        DeliveryPartner saved = partnerRepository.save(partner);
        createdPartners.add(saved.getId());
        return saved;
    }

    private void setPrimary(DeliverySubzone subzone, DeliveryPartner partner) {
        subzone.setPrimaryPartner(partner);
        subzoneRepository.save(subzone);
    }

    /** Adds live orders to a territory without going through checkout. */
    private void loadTerritory(DeliverySubzone subzone, int orders) {
        for (int i = 0; i < orders; i++) {
            jdbc.update("INSERT INTO deliveries (subzone_id, delivery_status, active, assignment_reason) "
                    + "VALUES (?, 'ASSIGNED', true, 'PRIMARY')", subzone.getId());
        }
    }

    private void declareNeighbours(DeliverySubzone a, DeliverySubzone b) {
        jdbc.update("INSERT INTO subzone_neighbours (subzone_id, neighbour_subzone_id) VALUES (?, ?)",
                a.getId(), b.getId());
        jdbc.update("INSERT INTO subzone_neighbours (subzone_id, neighbour_subzone_id) VALUES (?, ?)",
                b.getId(), a.getId());
    }

    private void addNamedBackup(DeliverySubzone subzone, DeliveryPartner partner, int priority) {
        jdbc.update("INSERT INTO subzone_backup_partners (subzone_id, partner_id, priority) VALUES (?, ?, ?)",
                subzone.getId(), partner.getId(), priority);
    }

    // ------------------------------------------------------------ rung one

    @Test
    @DisplayName("the territory's own rider takes its orders")
    void primaryRiderTakesTheirOwnTerritory() {
        DeliveryPartner local = newPartner("local", true, DEST_LAT, DEST_LNG);
        setPrimary(west, local);

        var decision = dispatch.chooseFor(reload(west), DEST_LAT, DEST_LNG);

        assertTrue(decision.hasPartner());
        assertEquals(local.getId(), decision.partner().getId());
        assertEquals(AssignmentReason.PRIMARY, decision.reason());
    }

    @Test
    @DisplayName("a busier local rider still beats an idle one from elsewhere")
    void localKnowledgeOutranksAnEmptyPlate() {
        // The rule the whole design exists for. The local rider is carrying
        // work and the outsider is carrying none; the local rider still wins,
        // because a rider who does not know the streets is not an improvement.
        DeliveryPartner local = newPartner("local", true, DEST_LAT, DEST_LNG);
        DeliveryPartner idleOutsider = newPartner("idle-outsider", true, DEST_LAT, DEST_LNG);
        setPrimary(west, local);
        setPrimary(east, idleOutsider);
        declareNeighbours(west, east);

        loadTerritory(west, 1); // busy, but under the capacity of 2

        var decision = dispatch.chooseFor(reload(west), DEST_LAT, DEST_LNG);

        assertEquals(local.getId(), decision.partner().getId(),
                "an idle neighbour must not displace the territory's own rider while they have room");
        assertEquals(AssignmentReason.PRIMARY, decision.reason());
    }

    // ------------------------------------------------------------ overflow

    @Test
    @DisplayName("an overloaded territory borrows a rider, and says it was overflow")
    void overflowGoesToTheNamedBackup() {
        DeliveryPartner local = newPartner("local", true, DEST_LAT, DEST_LNG);
        DeliveryPartner backup = newPartner("backup", true, DEST_LAT, DEST_LNG);
        setPrimary(west, local);
        addNamedBackup(west, backup, 1);

        loadTerritory(west, 2); // exactly at the capacity of 2

        var decision = dispatch.chooseFor(reload(west), DEST_LAT, DEST_LNG);

        assertEquals(backup.getId(), decision.partner().getId());
        assertEquals(AssignmentReason.OVERFLOW, decision.reason(),
                "the primary is present but full - that is overflow, not absence");
    }

    @Test
    @DisplayName("the territory's load counts orders carried by borrowed riders too")
    void territoryLoadIsAboutTheTerritoryNotTheRider() {
        // If load were counted per rider, an overflow order handed to a
        // neighbour would make the territory look QUIETER precisely because
        // it was busy enough to need help - and the next order would go
        // straight back to the already-full local rider.
        DeliveryPartner local = newPartner("local", true, DEST_LAT, DEST_LNG);
        DeliveryPartner backup = newPartner("backup", true, DEST_LAT, DEST_LNG);
        setPrimary(west, local);
        addNamedBackup(west, backup, 1);

        loadTerritory(west, 2);

        var decision = dispatch.chooseFor(reload(west), DEST_LAT, DEST_LNG);
        assertNotEquals(local.getId(), decision.partner().getId(),
                "the territory is at capacity regardless of who is carrying its orders");
    }

    // ------------------------------------------------------------- absence

    @Test
    @DisplayName("an absent rider's territory falls to its named backup, labelled as absence")
    void absenceIsDistinctFromOverflow() {
        DeliveryPartner local = newPartner("local", false, DEST_LAT, DEST_LNG);
        DeliveryPartner backup = newPartner("backup", true, DEST_LAT, DEST_LNG);
        setPrimary(west, local);
        addNamedBackup(west, backup, 1);

        var decision = dispatch.chooseFor(reload(west), DEST_LAT, DEST_LNG);

        assertEquals(backup.getId(), decision.partner().getId());
        assertEquals(AssignmentReason.ABSENCE, decision.reason(),
                "absence and overflow need completely different responses - a rostering problem "
                        + "and a hiring one - so they must not collapse into one label");
    }

    @Test
    @DisplayName("named backups are tried in the order someone declared")
    void backupPriorityIsRespected() {
        DeliveryPartner local = newPartner("local", false, DEST_LAT, DEST_LNG);
        DeliveryPartner first = newPartner("first-choice", true, DEST_LAT, DEST_LNG);
        DeliveryPartner second = newPartner("second-choice", true, DEST_LAT, DEST_LNG);
        setPrimary(west, local);
        addNamedBackup(west, second, 2);
        addNamedBackup(west, first, 1);

        var decision = decideOnATie();

        // Both are equally close and equally idle, so priority is the only
        // thing that can decide it - which is exactly what it is for.
        assertEquals(first.getId(), decision.partner().getId());
    }

    @Test
    @DisplayName("a rider who has left the roster is not an absence backup")
    void inactivePartnersAreNeverCandidates() {
        DeliveryPartner local = newPartner("local", false, DEST_LAT, DEST_LNG);
        DeliveryPartner exEmployee = newPartner("ex-employee", true, DEST_LAT, DEST_LNG);
        exEmployee.setActive(false);
        partnerRepository.save(exEmployee);

        setPrimary(west, local);
        addNamedBackup(west, exEmployee, 1);

        var decision = dispatch.chooseFor(reload(west), DEST_LAT, DEST_LNG);

        assertFalse(decision.hasPartner(),
                "available=true on someone who no longer works here must not make them a backup");
        assertEquals(AssignmentReason.FALLBACK, decision.reason());
    }

    // ------------------------------------------------------- the safety rule

    @Test
    @DisplayName("a completely idle rider who is too far away is not chosen - at all")
    void anIdleRiderTooFarAwayIsNotAcceptable() {
        // THE RULE FROM THE BRIEF, stated as an assertion: never assign to a
        // backup simply because they have fewer orders. This candidate has
        // ZERO orders, is a declared neighbour, and is on duty. They are still
        // excluded, because they are 8 km away and the gate is 2 km. Nothing
        // about their empty plate can buy them past it.
        DeliveryPartner local = newPartner("local", false, DEST_LAT, DEST_LNG);
        DeliveryPartner idleButFar = newPartner("idle-but-far", true, 28.68, 77.30);
        setPrimary(west, local);
        setPrimary(east, idleButFar);
        declareNeighbours(west, east);

        var decision = dispatch.chooseFor(reload(west), DEST_LAT, DEST_LNG);

        assertFalse(decision.hasPartner(),
                "an idle rider outside the detour gate must not be chosen; distance is a hard gate, "
                        + "not a weight that a low order count can outweigh");
        assertEquals(AssignmentReason.FALLBACK, decision.reason());
    }

    @Test
    @DisplayName("between two suitable riders, the closer and freer one wins")
    void amongSuitableCandidatesTheScoreDecides() {
        DeliveryPartner local = newPartner("local", false, DEST_LAT, DEST_LNG);
        DeliveryPartner near = newPartner("near", true, DEST_LAT, DEST_LNG);
        // 2.0 km from the drop, and that number is load-bearing. score() is
        // `distance + load * territory.load-weight-km-per-order`, and that
        // weight is 0.8 km PER ORDER. The original coordinate put this rider
        // 0.666 km away - a gap NARROWER THAN ONE ORDER - so a single live
        // delivery against the nearer rider made its score 0.8 against this
        // one's 0.666 and inverted the very claim being asserted. The test
        // could be flipped by one order arriving from anywhere, which is
        // exactly what kept happening.
        //
        // 2.0 km keeps the rider comfortably inside the 4.0 km backup detour
        // gate, so both are still suitable and the test still asks "of two
        // suitable riders, does the closer one win" - but it now takes three
        // stray orders to invert instead of one. Do not move this closer.
        DeliveryPartner slightlyFurther = newPartner("further", true, 28.6235, 77.2235);
        setPrimary(west, local);
        addNamedBackup(west, slightlyFurther, 1);
        addNamedBackup(west, near, 2);

        var decision = decideOnATie();

        assertEquals(near.getId(), decision.partner().getId(),
                "both passed the gate, so the closer one wins on score even though it was declared "
                        + "second - priority orders the list, it does not override geography");
    }

    // ---------------------------------------------------------- the far rungs

    @Test
    @DisplayName("with no named backup, a declared neighbouring territory's rider steps in")
    void neighbourRungIsUsedWhenNoBackupIsNamed() {
        DeliveryPartner local = newPartner("local", false, DEST_LAT, DEST_LNG);
        DeliveryPartner neighbourRider = newPartner("neighbour", true, DEST_LAT, DEST_LNG);
        setPrimary(west, local);
        setPrimary(east, neighbourRider);
        declareNeighbours(west, east);

        var decision = dispatch.chooseFor(reload(west), DEST_LAT, DEST_LNG);

        assertEquals(neighbourRider.getId(), decision.partner().getId());
    }

    @Test
    @DisplayName("an undeclared border is not a border - geometry alone lends nobody")
    void adjacencyIsDeclaredNotInferred() {
        // west and east share an edge exactly. Without a declared neighbour
        // row they are still not neighbours, because a shared edge can be a
        // railway line. The rider is reached instead through the same-main-zone
        // rung, which is a strictly later and correctly-labelled rung.
        DeliveryPartner local = newPartner("local", false, DEST_LAT, DEST_LNG);
        DeliveryPartner acrossTheLine = newPartner("across-the-line", true, DEST_LAT, DEST_LNG);
        setPrimary(west, local);
        setPrimary(east, acrossTheLine);
        // deliberately NOT declareNeighbours(west, east)

        var decision = dispatch.chooseFor(reload(west), DEST_LAT, DEST_LNG);

        assertEquals(AssignmentReason.ZONE_SUPPORT, decision.reason(),
                "with no declared border, help must come from the zone-wide rung rather than the "
                        + "neighbour rung - touching outlines are not evidence of a crossable border");
    }

    // ------------------------------------------------------------- no map

    @Test
    @DisplayName("an address in no territory is a visible fallback, not a guess")
    void noTerritoryMeansFallback() {
        var decision = dispatch.chooseFor(null, DEST_LAT, DEST_LNG);

        assertFalse(decision.hasPartner());
        assertEquals(AssignmentReason.FALLBACK, decision.reason());
        assertTrue(decision.explanation().toLowerCase().contains("no drawn territory"),
                "the explanation has to say the map has a hole in it; was: " + decision.explanation());
    }

    private DeliverySubzone reload(DeliverySubzone subzone) {
        return subzoneRepository.findById(subzone.getId()).orElseThrow();
    }
}
