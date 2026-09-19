package com.gpstore.repository;

import com.gpstore.entity.StoreClosure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
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

    /**
     * The same range, for many shops at once.
     *
     * <p>Native for the reason spelled out on
     * {@link StoreOperationsSettingsRepository#findForShops}: JPQL here carries
     * the shop filter, which would narrow a batch to a single shop and make
     * the marketplace screen quietly wrong. The narrowing is therefore
     * written into the query - {@code shop_id IN (:shopIds)} - where it can be
     * read.
     *
     * <p>Ordered by shop and then date so a caller grouping the result does
     * not depend on the database's natural order, which is not a promise.
     */
    @Query(value = "SELECT * FROM store_closures WHERE shop_id IN (:shopIds) "
            + "AND closed_on >= :from AND closed_on <= :to "
            + "ORDER BY shop_id, closed_on", nativeQuery = true)
    List<StoreClosure> findBetweenForShops(@Param("shopIds") Collection<Long> shopIds,
                                           @Param("from") LocalDate from,
                                           @Param("to") LocalDate to);
}
