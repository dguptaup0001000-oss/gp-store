package com.gpstore.store.hours;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
