package com.gpstore.engagement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Reads the merchant's own engagement rows.
 *
 * <p>NO SHOP PARAMETER ANYWHERE, because the entity is ShopOwned and the
 * tenant filter narrows these to the shop in scope. A merchant asking about
 * their listings cannot accidentally - or deliberately - be handed another
 * shop's interest.
 */
public interface ListingEngagementRepository extends JpaRepository<ListingEngagementEvent, Long> {

    /**
     * How many of each kind, per listing, in this window.
     *
     * <p>Grouped in the database rather than counted in Java: a busy shop's
     * month is a lot of rows and none of them need to travel.
     */
    @Query("""
            SELECT e.productVariantId, e.kind, e.commerceMode, count(e)
              FROM ListingEngagementEvent e
             WHERE e.occurredAt >= :from AND e.occurredAt < :to
             GROUP BY e.productVariantId, e.kind, e.commerceMode
            """)
    List<Object[]> countByListing(@Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to);

    /** The same totals for the shop as a whole, without the per-listing split. */
    @Query("""
            SELECT e.kind, e.commerceMode, count(e)
              FROM ListingEngagementEvent e
             WHERE e.occurredAt >= :from AND e.occurredAt < :to
             GROUP BY e.kind, e.commerceMode
            """)
    List<Object[]> countForShop(@Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to);
}
