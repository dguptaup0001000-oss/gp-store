package com.gpstore.repository;

import com.gpstore.entity.StoreClosure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StoreClosureRepository extends JpaRepository<StoreClosure, Long> {

    /**
     * Every closure in a date range, oldest first.
     *
     * <p>RANGE, NOT ALL. The schedule needs to know which of the next thirty
     * days are shut; loading every closure the shop has ever declared to
     * answer that would grow without bound and be re-read on every status
     * request. The range is bounded by the lookahead.
     */
    @Query("select c from StoreClosure c where c.closedOn >= :from and c.closedOn <= :to order by c.closedOn asc")
    List<StoreClosure> findBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    Optional<StoreClosure> findByClosedOn(LocalDate closedOn);

    /**
     * Upcoming closures for the admin screen, oldest first.
     *
     * <p>Both queries here are JPQL, so the shop filter narrows them to the
     * shop in scope - which is the whole reason StoreClosure is ShopOwned
     * rather than carrying a shopId this file would have to remember to name.
     */
    @Query("select c from StoreClosure c where c.closedOn >= :from order by c.closedOn asc")
    List<StoreClosure> findUpcoming(@Param("from") LocalDate from);
}
