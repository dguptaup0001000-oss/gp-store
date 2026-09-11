package com.gpstore.discovery;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Every read is keyed on the customer, because the list is the customer's.
 *
 * <p>There is deliberately no findByPreferredShopId. "Who has chosen my
 * shop?" is a question a merchant would like answered and one this system
 * does not answer - it is a list of named customers' shopping habits, and a
 * competitor's share of them, handed over for free.
 */
public interface PreferredShopRepository extends JpaRepository<PreferredShop, Long> {

    List<PreferredShop> findByCustomerIdOrderByCategoryIdAscSlotAsc(Long customerId);

    List<PreferredShop> findByCustomerIdAndCategoryIdOrderBySlotAsc(Long customerId, Long categoryId);

    @Modifying
    @Query("DELETE FROM PreferredShop p WHERE p.customerId = :customerId "
            + "AND p.categoryId = :categoryId")
    int deleteForCategory(@Param("customerId") Long customerId,
                          @Param("categoryId") Long categoryId);
}
