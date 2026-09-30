package com.gpstore.store.hours;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * This shop's week.
 *
 * JPQL, so the shop filter narrows it to the shop in scope - which is why no
 * method here takes a shop id, and why none can be asked for another shop's
 * hours by passing one.
 */
public interface ShopBusinessHoursRepository extends JpaRepository<ShopBusinessHours, Long> {

    @Query("select h from ShopBusinessHours h order by h.dayOfWeek asc, h.opensAt asc")
    List<ShopBusinessHours> wholeWeek();

    /**
     * Trading weeks for a bounded marketplace discovery result.
     *
     * <p>NATIVE ON PURPOSE. Hibernate's shop filter would narrow a JPQL query
     * to the one shop in the current request scope, while this query is the
     * reviewed cross-shop read that prevents one hours query per storefront.
     * The ids are produced by {@code ShopDiscovery}; they are not accepted
     * from an arbitrary client request body.
     */
    @Query(value = "SELECT * FROM shop_business_hours "
            + "WHERE shop_id IN (:shopIds) "
            + "ORDER BY shop_id, day_of_week, opens_at", nativeQuery = true)
    List<ShopBusinessHours> findForShops(@Param("shopIds") Collection<Long> shopIds);
}
