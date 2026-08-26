package com.gpstore.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * One of the 26 permanent delivery territories. Z7B, and it stays Z7B.
 *
 * PERMANENCE IS THE POINT. A rider who works Z7B every day learns which gate
 * of the housing society actually opens, which lane floods, which shop counts
 * as the landmark everyone gives directions by, and which building has the
 * lift out. That knowledge is worth more than any routing algorithm, and it
 * only accrues if the territory stops moving. So nothing in this application
 * writes to {@link #boundary} except an administrator's explicit edit - not a
 * nightly job, not a load balancer, not a rebalancing heuristic. When a
 * territory gets busy the ANSWER IS A SECOND RIDER, never a new line on the
 * map.
 *
 * SIZE AND VOLUME ARE NOT EQUAL AND ARE NOT MEANT TO BE. Z1 may take ten
 * orders a day across four square kilometres of low houses while Z2A takes
 * thirty from six towers on one street. Both are correct if the roads make
 * them efficient to ride. There is deliberately no code that compares two
 * subzones' areas or order counts and calls the difference a problem.
 */
@Entity
@Table(name = "delivery_subzones")
@Getter
@Setter
@NoArgsConstructor
public class DeliverySubzone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "zone_id", nullable = false)
    private DeliveryZone zone;

    /** Z7B. Unique across the whole territory map, not just within its zone. */
    @Column(nullable = false, length = 16)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    /**
     * The territory outline as a closed ring of {@code [latitude, longitude]}
     * pairs, JSON, e.g. {@code [[28.61,77.20],[28.62,77.20],[28.62,77.21]]}.
     *
     * Stored as JSON text rather than a PostGIS geometry on purpose. CI and
     * production run the plain postgres image, which has no PostGIS, and turning the
     * extension on in Supabase is a change to a live production database made
     * for the sake of 26 polygons that a ray-casting test in TerritoryResolver
     * handles in microseconds. Keeping it as text means the move to PostGIS
     * later is a data migration rather than a redesign.
     *
     * TEXT rather than jsonb, and that was decided by the database rather than
     * by preference: Hibernate binds a String attribute as varchar, Postgres
     * will not implicitly cast varchar to jsonb, and every insert failed with
     * "column boundary is of type jsonb but expression is of type character
     * varying". The alternative is a JSON type mapping whose only benefit here
     * would be Postgres checking that the text parses - and TerritoryAdminService
     * already checks something strictly stronger before any write, namely that
     * it parses AS A RING OF AT LEAST THREE COORDINATE PAIRS. Well-formed JSON
     * that is not a polygon would satisfy jsonb and still be useless.
     *
     * Null means "drawn later". A subzone with no boundary matches no address
     * - see TerritoryResolver, which fails closed rather than guessing.
     */
    @Column(columnDefinition = "text")
    private String boundary;

    /**
     * The rider who normally works this territory, and the reason permanence
     * is worth anything. Nullable so a territory can be drawn before anyone
     * is hired for it.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "primary_partner_id")
    private DeliveryPartner primaryPartner;

    /**
     * How many live orders this territory's primary rider is expected to carry
     * before the overflow ladder starts looking for help.
     *
     * Per-subzone, not global, and that is the whole argument: twelve orders
     * in six adjacent towers is a comfortable evening, and twelve orders
     * spread over farm plots is not. A single global number would quietly
     * reintroduce the equal-workload assumption this design rejects.
     */
    @Column(nullable = false)
    private Integer maxConcurrentOrders = 12;

    /** Why this line, in the words of whoever drew it. See DeliveryZone.notes. */
    @Column(columnDefinition = "TEXT")
    private String notes;

    private Integer displayOrder;

    @Column(nullable = false)
    private Boolean active = Boolean.TRUE;

    /**
     * The subzones a rider may be borrowed from or lent to, DECLARED rather
     * than derived from the geometry.
     *
     * Two polygons can share a long edge and still be an hour apart if that
     * edge is a railway line with no crossing. Inferring adjacency from
     * touching boundaries would produce exactly the assignment this system
     * exists to prevent, so the fact that two territories are practically
     * reachable from one another is a statement someone who knows the roads
     * makes explicitly.
     *
     * Written in both directions by TerritoryAdminService so a lookup stays a
     * single indexed read.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "subzone_neighbours",
            joinColumns = @JoinColumn(name = "subzone_id"),
            inverseJoinColumns = @JoinColumn(name = "neighbour_subzone_id"))
    private List<DeliverySubzone> neighbours = new ArrayList<>();

    @PrePersist
    @PreUpdate
    void normalise() {
        if (active == null) {
            active = Boolean.TRUE;
        }
        if (maxConcurrentOrders == null || maxConcurrentOrders < 1) {
            maxConcurrentOrders = 12;
        }
        if (code != null) {
            code = code.trim().toUpperCase();
        }
    }

    /** Convenience for logs and dispatch decisions: "Z7B (Canal Colony)". */
    public String label() {
        return code + (name == null || name.isBlank() ? "" : " (" + name + ")");
    }
}
