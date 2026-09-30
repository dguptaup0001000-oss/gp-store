package com.gpstore.store.hours;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface ShopHoursOverrideRepository extends JpaRepository<ShopHoursOverride, Long> {

    /**
     * Overrides in a date range, earliest first.
     *
     * RANGE, NOT ALL, for the same reason StoreClosureRepository.findBetween
     * is: the schedule needs to know about the next thirty days, and loading
     * every special day a shop has ever declared to answer that grows without
     * bound and is re-read on every status request.
     */
    @Query("select o from ShopHoursOverride o where o.onDate >= :from and o.onDate <= :to "
            + "order by o.onDate asc, o.opensAt asc")
    List<ShopHoursOverride> findBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select o from ShopHoursOverride o where o.onDate >= :from "
            + "order by o.onDate asc, o.opensAt asc")
    List<ShopHoursOverride> findUpcoming(@Param("from") LocalDate from);

    List<ShopHoursOverride> findByOnDate(LocalDate onDate);

    /** See {@link ShopBusinessHoursRepository#findForShops(Collection)}. */
    @Query(value = "SELECT * FROM shop_hours_override "
            + "WHERE shop_id IN (:shopIds) AND on_date >= :from AND on_date <= :to "
            + "ORDER BY shop_id, on_date, opens_at", nativeQuery = true)
    List<ShopHoursOverride> findBetweenForShops(
            @Param("shopIds") Collection<Long> shopIds,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
