package com.gpstore.repository;

import com.gpstore.entity.DeliverySubzone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DeliverySubzoneRepository extends JpaRepository<DeliverySubzone, Long> {

    Optional<DeliverySubzone> findByCodeIgnoreCase(String code);

    Optional<DeliverySubzone> findFirstByPrimaryPartner_Id(Long partnerId);

    List<DeliverySubzone> findByZoneIdOrderByDisplayOrderAscIdAsc(Long zoneId);

    /**
     * Everything the resolver needs to answer "which territory is this point
     * in", in ONE query.
     *
     * The resolver holds the whole map in memory - 26 polygons is nothing -
     * and this is how it fills that cache. The join fetches matter: without
     * them, walking 26 subzones to read each one's zone code is 26 extra
     * selects on a 512 MB instance, which is exactly the kind of quiet N+1
     * that only shows up under load.
     */
    /*
     * THE RIDER IS DELIBERATELY NOT FETCHED HERE ANY MORE.
     *
     * TerritoryResolver.load(), the only caller, builds a polygon record from
     * the subzone's id, code, zone and boundary and never looks at the rider.
     * So the fetch bought nothing - and under a marketplace it would have cost
     * something real. delivery_partners is shop-owned; delivery_subzones is
     * not (territories are platform geography today, §88). Fetching a
     * shop-owned entity from an unfiltered root means TenantEntityListener's
     * @PostLoad meets a rider belonging to some other shop and refuses the
     * whole load - so the first subzone handed to a second merchant's rider
     * would have taken the territory map down for everybody, on a code path
     * nobody would have thought to look at.
     *
     * The zone fetch stays: DeliveryZone has no shop, the resolver reads its
     * code on every subzone, and without it 26 subzones are 26 extra selects.
     */
    @Query("select distinct s from DeliverySubzone s "
            + "left join fetch s.zone "
            + "where s.active = true")
    List<DeliverySubzone> findAllActiveForResolution();

    /**
     * The neighbour graph for one subzone, loaded on demand rather than kept
     * in the resolver's cache: it is read only when the dispatch ladder
     * actually needs to step outside a territory, which is the uncommon path.
     */
    @Query("select n from DeliverySubzone s join s.neighbours n "
            + "where s.id = :subzoneId and n.active = true")
    List<DeliverySubzone> findNeighbours(Long subzoneId);
}
