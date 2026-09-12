package com.gpstore.rating;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Every read here is shop-scoped by the filter, never by an argument.
 *
 * <p>NOT ONE METHOD TAKES A SHOP ID, and that is the design: a repository
 * method with a shopId parameter is a method some caller will eventually
 * hand a shop id that came off a request. The scope comes from the
 * credential, and a merchant reading their own ratings and a customer
 * reading a storefront's are the same query under two different scopes.
 */
public interface ShopRatingRepository extends JpaRepository<ShopRating, Long> {

    Optional<ShopRating> findByOrderId(Long orderId);

    Page<ShopRating> findByHiddenAtIsNullOrderByCreatedAtDesc(Pageable pageable);

    Page<ShopRating> findByOrderByCreatedAtDesc(Pageable pageable);

    List<ShopRating> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    /**
     * The numbers behind the badge (§19), in one query rather than five.
     *
     * <p>COUNTED OVER THE ROWS THAT COUNT, which is not the same as the rows
     * that are shown - see {@link ShopRating#countsTowardsTheAverage}. A
     * shop's average must not be improvable by moderating.
     *
     * <p>TWO METHODS, NOT ONE WITH A NULLABLE PARAMETER. The obvious shape
     * is a single query with {@code (:since IS NULL OR r.createdAt >= :since)},
     * and Postgres rejects it at prepare time: a bare parameter compared only
     * to NULL has no inferable type, and the driver gets "could not determine
     * data type of parameter $2". Casting round it would work and would leave
     * a piece of dialect-specific noise in a query that reads perfectly well
     * as two.
     */
    @Query("""
            SELECT new com.gpstore.rating.RatingTally(
                       COUNT(r), COALESCE(AVG(CAST(r.rating AS double)), 0.0),
                       SUM(CASE WHEN r.rating = 5 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN r.rating = 4 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN r.rating = 3 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN r.rating = 2 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN r.rating = 1 THEN 1 ELSE 0 END))
            FROM ShopRating r
            WHERE (r.hiddenAt IS NULL OR r.hiddenReason NOT IN
                     (com.gpstore.rating.HideReason.SPAM,
                      com.gpstore.rating.HideReason.IMPERSONATION))
            """)
    RatingTally tallyForever();

    @Query("""
            SELECT new com.gpstore.rating.RatingTally(
                       COUNT(r), COALESCE(AVG(CAST(r.rating AS double)), 0.0),
                       SUM(CASE WHEN r.rating = 5 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN r.rating = 4 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN r.rating = 3 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN r.rating = 2 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN r.rating = 1 THEN 1 ELSE 0 END))
            FROM ShopRating r
            WHERE (r.hiddenAt IS NULL OR r.hiddenReason NOT IN
                     (com.gpstore.rating.HideReason.SPAM,
                      com.gpstore.rating.HideReason.IMPERSONATION))
              AND r.createdAt >= :since
            """)
    RatingTally tallySince(@Param("since") LocalDateTime since);

    /**
     * How often each reason was given (§18), so a shopkeeper can act on it.
     */
    @Query("""
            SELECT reason, COUNT(r)
            FROM ShopRating r JOIN r.reasons reason
            WHERE (r.hiddenAt IS NULL OR r.hiddenReason NOT IN
                     (com.gpstore.rating.HideReason.SPAM,
                      com.gpstore.rating.HideReason.IMPERSONATION))
              AND r.createdAt >= :since
            GROUP BY reason
            ORDER BY COUNT(r) DESC
            """)
    List<Object[]> reasonCounts(@Param("since") LocalDateTime since);

    Page<ShopRating> findByReportedAtIsNotNullOrderByReportedAtDesc(Pageable pageable);
}
